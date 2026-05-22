package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.SuggestedOrderDTO;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.PortfolioTarget;
import com.rick1135.Valora.entity.User;
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

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MIN_TARGET_TOTAL = new BigDecimal("99.90");
    private static final BigDecimal MAX_TARGET_TOTAL = new BigDecimal("100.10");

    @Transactional(readOnly = true)
    public List<SuggestedOrderDTO> calculateSuggestedOrders(User user, UUID portfolioId, BigDecimal contributionAmount) {
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

        List<SuggestedOrderDTO> orders = new ArrayList<>();
        BigDecimal remainingContribution = contributionAmount;

        // Calculate initial gaps
        List<GapAnalysis> gaps = targets.stream()
                .map(target -> {
                    String category = target.getAssetCategory().name();
                    AssetAllocationDTO allocation = allocationsByCategory.get(category);
                    BigDecimal currentAmount = allocation == null ? BigDecimal.ZERO : allocation.totalValue();
                    BigDecimal idealAmount = targetPatrimony.multiply(target.getTargetPercentage())
                            .divide(HUNDRED, 2, RoundingMode.HALF_UP);
                    BigDecimal gapAmount = idealAmount.subtract(currentAmount);
                    BigDecimal currentPrice = allocation == null ? BigDecimal.ZERO : allocation.currentPrice();
                    boolean marketPriceReliable = allocation != null && allocation.marketPriceReliable();
                    return new GapAnalysis(category, currentAmount, idealAmount, gapAmount, currentPrice, marketPriceReliable);
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
            CategoryQuantityProjection quantityProjection = calculateCategoryQuantityProjection(investment, gap.currentPrice);

            orders.add(new SuggestedOrderDTO(
                    gap.category,
                    gap.currentAmount,
                    gap.idealAmount,
                    gap.gapAmount,
                    investment,
                    gap.currentPrice,
                    quantityProjection.theoreticalQuantity,
                    quantityProjection.actualInvestmentAmount,
                    quantityProjection.residualCashAmount,
                    gap.marketPriceReliable
            ));
        }

        return orders;
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
         * weighted average price for the category. This quantity is intentionally
         * theoretical and should not be interpreted as an executable ticker-level
         * order until Phase 9 introduces individual ticker targets/recommendations.
         *
         * RoundingMode.DOWN preserves market constraints for standard stocks/FIIs:
         * fractional shares are not suggested and the remainder is explicitly exposed
         * as residualCashAmount instead of being silently ignored.
         */
        Long theoreticalQuantity = suggestedInvestment
                .divide(currentPrice, 0, RoundingMode.DOWN)
                .longValue();
        BigDecimal actualInvestmentAmount = currentPrice.multiply(BigDecimal.valueOf(theoreticalQuantity));
        BigDecimal residualCashAmount = suggestedInvestment.subtract(actualInvestmentAmount);

        return new CategoryQuantityProjection(theoreticalQuantity, actualInvestmentAmount, residualCashAmount);
    }

    private BigDecimal defaultBigDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional
    public void saveTargets(User user, UUID portfolioId, Map<String, BigDecimal> targetsMap) {
        Portfolio portfolio = portfolioService.resolveOwnedPortfolio(user, portfolioId);
        
        BigDecimal total = targetsMap.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Relaxed validation: between 99.90 and 100.10
        if (total.compareTo(MIN_TARGET_TOTAL) < 0 || total.compareTo(MAX_TARGET_TOTAL) > 0) {
            throw new IllegalArgumentException("A soma das metas deve ser aproximadamente 100%. Atual: " + total + "%");
        }

        targetRepository.deleteByPortfolio(portfolio);

        targetsMap.forEach((category, percentage) -> {
            PortfolioTarget target = new PortfolioTarget();
            target.setPortfolio(portfolio);
            target.setAssetCategory(com.rick1135.Valora.entity.AssetCategory.valueOf(category));
            target.setTargetPercentage(percentage);
            targetRepository.save(target);
        });
    }

    private record GapAnalysis(
            String category,
            BigDecimal currentAmount,
            BigDecimal idealAmount,
            BigDecimal gapAmount,
            BigDecimal currentPrice,
            boolean marketPriceReliable
    ) {}

    private record CategoryQuantityProjection(
            Long theoreticalQuantity,
            BigDecimal actualInvestmentAmount,
            BigDecimal residualCashAmount
    ) {}
}
