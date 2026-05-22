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

        boolean marketPriceReliable = !summary.fallbackQuoteUsed();
        BigDecimal currentPatrimony = summary.totalPatrimony();
        BigDecimal targetPatrimony = currentPatrimony.add(contributionAmount);

        Map<String, BigDecimal> currentAllocations = summary.allocations().stream()
                .collect(Collectors.toMap(AssetAllocationDTO::category, AssetAllocationDTO::totalValue));

        List<SuggestedOrderDTO> orders = new ArrayList<>();
        BigDecimal remainingContribution = contributionAmount;

        // Calculate initial gaps
        List<GapAnalysis> gaps = targets.stream()
                .map(target -> {
                    String category = target.getAssetCategory().name();
                    BigDecimal currentAmount = currentAllocations.getOrDefault(category, BigDecimal.ZERO);
                    BigDecimal idealAmount = targetPatrimony.multiply(target.getTargetPercentage())
                            .divide(HUNDRED, 2, RoundingMode.HALF_UP);
                    BigDecimal gapAmount = idealAmount.subtract(currentAmount);
                    return new GapAnalysis(category, currentAmount, idealAmount, gapAmount);
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
            
            orders.add(new SuggestedOrderDTO(
                    gap.category,
                    gap.currentAmount,
                    gap.idealAmount,
                    gap.gapAmount,
                    investment,
                    marketPriceReliable
            ));
        }

        return orders;
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
            BigDecimal gapAmount
    ) {}
}
