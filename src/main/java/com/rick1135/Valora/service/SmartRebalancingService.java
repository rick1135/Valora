package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.AssetPositionSummaryDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.PortfolioTargetResponseDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import com.rick1135.Valora.dto.response.SuggestedOrderDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.PortfolioTarget;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.repository.AssetRepository;
import com.rick1135.Valora.repository.PortfolioTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SmartRebalancingService {

    private final PortfolioService portfolioService;
    private final PortfolioTargetRepository targetRepository;
    private final AssetRepository assetRepository;
    private final QuoteService quoteService;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MIN_TARGET_TOTAL = new BigDecimal("99.90");
    private static final BigDecimal MAX_TARGET_TOTAL = new BigDecimal("100.10");

    @Transactional(readOnly = true)
    public List<PortfolioTargetResponseDTO> getTargets(User user, UUID portfolioId) {
        Portfolio portfolio = portfolioService.resolveOwnedPortfolio(user, portfolioId);
        return targetRepository.findByPortfolio(portfolio).stream()
                .map(target -> new PortfolioTargetResponseDTO(
                        target.getId(),
                        target.getAssetCategory() != null ? target.getAssetCategory().name() : null,
                        target.getAsset() != null ? target.getAsset().getId() : null,
                        target.getAsset() != null ? target.getAsset().getTicker() : null,
                        target.getAsset() != null ? target.getAsset().getName() : null,
                        target.getTargetPercentage()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SuggestedOrderDTO> calculateSuggestedOrders(User user, UUID portfolioId, BigDecimal contributionAmount) {
        if (contributionAmount == null || contributionAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("O valor do aporte nao pode ser negativo.");
        }

        Portfolio portfolio = portfolioService.resolveOwnedPortfolio(user, portfolioId);
        PortfolioSummaryDTO summary = portfolioService.getPortfolioSummary(user, portfolioId);
        List<PortfolioTarget> targets = targetRepository.findByPortfolio(portfolio);

        if (targets.isEmpty()) {
            return Collections.emptyList();
        }

        BigDecimal currentPatrimony = summary.totalPatrimony();
        BigDecimal targetPatrimony = currentPatrimony.add(contributionAmount);

        Map<String, AssetAllocationDTO> allocationsByCategory = summary.allocations().stream()
                .collect(Collectors.toMap(AssetAllocationDTO::category, allocation -> allocation));
        Map<UUID, AssetPositionSummaryDTO> positionsByAssetId = summary.positions().stream()
                .filter(position -> position.assetId() != null)
                .collect(Collectors.toMap(AssetPositionSummaryDTO::assetId, position -> position));
        Map<String, List<AssetPositionSummaryDTO>> positionsByCategory = summary.positions().stream()
                .collect(Collectors.groupingBy(AssetPositionSummaryDTO::category));
        Set<UUID> individuallyTargetedAssetIds = targets.stream()
                .map(PortfolioTarget::getAsset)
                .filter(Objects::nonNull)
                .map(Asset::getId)
                .collect(Collectors.toSet());

        List<SuggestedOrderDTO> orders = new ArrayList<>();
        BigDecimal remainingContribution = contributionAmount;

        // Calculate initial gaps
        List<GapAnalysis> gaps = targets.stream()
                .map(target -> {
                    TargetSnapshot snapshot = resolveTargetSnapshot(
                            target,
                            allocationsByCategory,
                            positionsByAssetId,
                            positionsByCategory,
                            individuallyTargetedAssetIds
                    );
                    BigDecimal idealAmount = targetPatrimony.multiply(target.getTargetPercentage())
                            .divide(HUNDRED, 2, RoundingMode.HALF_UP);
                    BigDecimal gapAmount = idealAmount.subtract(snapshot.currentAmount());
                    return new GapAnalysis(
                            snapshot.category(),
                            snapshot.assetId(),
                            snapshot.ticker(),
                            snapshot.currentAmount(),
                            idealAmount,
                            gapAmount,
                            snapshot.currentPrice(),
                            snapshot.marketPriceReliable(),
                            snapshot.assetId() != null
                    );
                })
                .sorted(Comparator.comparing(GapAnalysis::gapAmount).reversed())
                .collect(Collectors.toList());

        // Simple allocation strategy: Distribute contribution to close the largest positive gaps first
        for (GapAnalysis gap : gaps) {
            BigDecimal investment = BigDecimal.ZERO;
            if (remainingContribution.compareTo(BigDecimal.ZERO) > 0 && gap.gapAmount.compareTo(BigDecimal.ZERO) > 0) {
                investment = gap.gapAmount.min(remainingContribution);
                remainingContribution = remainingContribution.subtract(investment);
            }
            CategoryQuantityProjection quantityProjection = gap.assetTarget
                    ? calculateCategoryQuantityProjection(investment, gap.currentPrice)
                    : CategoryQuantityProjection.emptyForCategoryTarget();

            orders.add(new SuggestedOrderDTO(
                    gap.category,
                    gap.assetId,
                    gap.ticker,
                    gap.currentAmount,
                    gap.idealAmount,
                    gap.gapAmount,
                    investment,
                    gap.currentPrice,
                    quantityProjection.suggestedQuantity,
                    quantityProjection.actualInvestmentAmount,
                    quantityProjection.residualCashAmount,
                    gap.marketPriceReliable
            ));
        }

        return orders;
    }

    private TargetSnapshot resolveTargetSnapshot(
            PortfolioTarget target,
            Map<String, AssetAllocationDTO> allocationsByCategory,
            Map<UUID, AssetPositionSummaryDTO> positionsByAssetId,
            Map<String, List<AssetPositionSummaryDTO>> positionsByCategory,
            Set<UUID> individuallyTargetedAssetIds
    ) {
        if (target.getAsset() != null) {
            UUID assetId = target.getAsset().getId();
            AssetPositionSummaryDTO position = positionsByAssetId.get(assetId);
            if (position == null) {
                return resolveNewAssetTargetSnapshot(target.getAsset());
            }

            return new TargetSnapshot(
                    target.getAsset().getCategory().name(),
                    assetId,
                    target.getAsset().getTicker(),
                    position == null ? BigDecimal.ZERO : position.totalValue(),
                    position == null ? BigDecimal.ZERO : position.currentPrice(),
                    position != null && position.marketPriceReliable()
            );
        }

        String category = target.getAssetCategory().name();
        List<AssetPositionSummaryDTO> allCategoryPositions = positionsByCategory.getOrDefault(category, List.of());
        List<AssetPositionSummaryDTO> categoryPositions = allCategoryPositions.stream()
                .filter(position -> !individuallyTargetedAssetIds.contains(position.assetId()))
                .toList();

        if (!allCategoryPositions.isEmpty()) {
            BigDecimal currentAmount = categoryPositions.stream()
                    .map(AssetPositionSummaryDTO::totalValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal currentPrice = categoryPositions.size() == 1
                    ? defaultBigDecimal(categoryPositions.getFirst().currentPrice())
                    : BigDecimal.ZERO;
            boolean marketPriceReliable = categoryPositions.stream().allMatch(AssetPositionSummaryDTO::marketPriceReliable);

            return new TargetSnapshot(category, null, null, currentAmount, currentPrice, marketPriceReliable);
        }

        AssetAllocationDTO allocation = allocationsByCategory.get(category);
        return new TargetSnapshot(
                category,
                null,
                null,
                allocation == null ? BigDecimal.ZERO : allocation.totalValue(),
                allocation == null ? BigDecimal.ZERO : allocation.currentPrice(),
                allocation != null && allocation.marketPriceReliable()
        );
    }

    private TargetSnapshot resolveNewAssetTargetSnapshot(Asset asset) {
        BigDecimal currentPrice = quoteService.getCurrentQuote(asset.getTicker())
                .map(QuoteDTO::price)
                .filter(price -> price.compareTo(BigDecimal.ZERO) > 0)
                .orElse(BigDecimal.ZERO);

        return new TargetSnapshot(
                asset.getCategory().name(),
                asset.getId(),
                asset.getTicker(),
                BigDecimal.ZERO,
                currentPrice,
                currentPrice.compareTo(BigDecimal.ZERO) > 0
        );
    }

    private CategoryQuantityProjection calculateCategoryQuantityProjection(BigDecimal suggestedInvestment, BigDecimal currentPrice) {
        if (suggestedInvestment == null || currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return new CategoryQuantityProjection(0L, BigDecimal.ZERO, defaultBigDecimal(suggestedInvestment));
        }
        if (suggestedInvestment.compareTo(BigDecimal.ZERO) <= 0) {
            return new CategoryQuantityProjection(0L, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        /*
         * Current portfolio targets are defined by AssetCategory, not by ticker.
         * Therefore currentPrice is a category reference price. When there is more
         * than one asset in the category, PortfolioPerformanceCalculator provides a
         * weighted average price for the category. Asset-level targets, introduced
         * in Phase 9, use the ticker price exposed in PortfolioSummaryDTO and produce
         * a definitive suggested quantity for that ticker.
         *
         * RoundingMode.DOWN preserves market constraints for standard stocks/FIIs:
         * fractional shares are not suggested and the remainder is explicitly exposed
         * as residualCashAmount instead of being silently ignored.
         */
        Long suggestedQuantity = suggestedInvestment
                .divide(currentPrice, 0, RoundingMode.DOWN)
                .longValue();
        BigDecimal actualInvestmentAmount = currentPrice.multiply(BigDecimal.valueOf(suggestedQuantity));
        BigDecimal residualCashAmount = suggestedInvestment.subtract(actualInvestmentAmount);

        return new CategoryQuantityProjection(suggestedQuantity, actualInvestmentAmount, residualCashAmount);
    }

    private BigDecimal defaultBigDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional
    public void saveTargets(User user, UUID portfolioId, Map<String, BigDecimal> targetsMap) {
        if (targetsMap == null || targetsMap.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos uma meta de alocacao.");
        }
        List<PortfolioTargetInput> targets = targetsMap.entrySet().stream()
                .map(entry -> PortfolioTargetInput.forCategory(entry.getKey(), entry.getValue()))
                .toList();
        saveTargets(user, portfolioId, targets);
    }

    @Transactional
    public void saveTargets(User user, UUID portfolioId, List<PortfolioTargetInput> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos uma meta de alocacao.");
        }

        Set<AssetCategory> seenCategories = new HashSet<>();
        Set<UUID> seenAssetIds = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;

        List<ValidatedTarget> validatedTargets = new ArrayList<>(targets.size());
        for (PortfolioTargetInput input : targets) {
            if (input == null) {
                throw new IllegalArgumentException("Item de meta invalido.");
            }

            String normalizedCategory = input.assetCategory() != null ? input.assetCategory().trim() : null;
            boolean hasCategory = normalizedCategory != null && !normalizedCategory.isEmpty();
            boolean hasAsset = input.assetId() != null;

            if (hasCategory == hasAsset) {
                throw new IllegalArgumentException("A meta deve informar categoria ou ativo, exclusivamente.");
            }

            BigDecimal percentage = input.targetPercentage();
            if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0 || percentage.compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException("O percentual da meta deve ser maior que 0 e menor ou igual a 100.");
            }

            AssetCategory category = null;
            UUID assetId = null;
            if (hasCategory) {
                try {
                    category = AssetCategory.valueOf(normalizedCategory.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Categoria de ativo invalida: " + normalizedCategory);
                }
                if (!seenCategories.add(category)) {
                    throw new IllegalArgumentException("Categoria duplicada nas metas: " + category.name());
                }
            } else {
                assetId = input.assetId();
                if (!seenAssetIds.add(assetId)) {
                    throw new IllegalArgumentException("Ativo duplicado nas metas.");
                }
            }

            total = total.add(percentage);
            validatedTargets.add(new ValidatedTarget(category, assetId, percentage));
        }

        if (total.compareTo(MIN_TARGET_TOTAL) < 0 || total.compareTo(MAX_TARGET_TOTAL) > 0) {
            throw new IllegalArgumentException("A soma das metas deve ser aproximadamente 100%. Atual: " + total + "%");
        }

        Portfolio portfolio = portfolioService.resolveOwnedPortfolio(user, portfolioId);

        List<PortfolioTarget> entities = new ArrayList<>(validatedTargets.size());
        for (ValidatedTarget validated : validatedTargets) {
            PortfolioTarget target = new PortfolioTarget();
            target.setPortfolio(portfolio);
            target.setTargetPercentage(validated.targetPercentage());

            if (validated.assetId() != null) {
                Asset asset = assetRepository.findById(validated.assetId())
                        .orElseThrow(() -> new IllegalArgumentException("Ativo nao encontrado para meta."));
                target.setAsset(asset);
                target.setAssetCategory(null);
            } else {
                target.setAssetCategory(validated.category());
                target.setAsset(null);
            }
            entities.add(target);
        }

        targetRepository.deleteByPortfolio(portfolio);
        targetRepository.flush();
        entities.forEach(targetRepository::save);
    }

    private record ValidatedTarget(
            AssetCategory category,
            UUID assetId,
            BigDecimal targetPercentage
    ) {}

    private record GapAnalysis(
            String category,
            UUID assetId,
            String ticker,
            BigDecimal currentAmount,
            BigDecimal idealAmount,
            BigDecimal gapAmount,
            BigDecimal currentPrice,
            boolean marketPriceReliable,
            boolean assetTarget
    ) {}

    private record TargetSnapshot(
            String category,
            UUID assetId,
            String ticker,
            BigDecimal currentAmount,
            BigDecimal currentPrice,
            boolean marketPriceReliable
    ) {}

    private record CategoryQuantityProjection(
            Long suggestedQuantity,
            BigDecimal actualInvestmentAmount,
            BigDecimal residualCashAmount
    ) {
        private static CategoryQuantityProjection emptyForCategoryTarget() {
            return new CategoryQuantityProjection(null, null, null);
        }
    }

    public record PortfolioTargetInput(
            String assetCategory,
            UUID assetId,
            BigDecimal targetPercentage
    ) {
        public static PortfolioTargetInput forCategory(String assetCategory, BigDecimal targetPercentage) {
            return new PortfolioTargetInput(assetCategory, null, targetPercentage);
        }

        public static PortfolioTargetInput forAsset(UUID assetId, BigDecimal targetPercentage) {
            return new PortfolioTargetInput(null, assetId, targetPercentage);
        }
    }
}
