package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioSummaryDTO(
        BigDecimal totalPatrimony,
        BigDecimal totalInvested,
        BigDecimal totalProvents,
        ProfitabilityDTO profitability,
        List<AssetAllocationDTO> allocations,
        List<AssetPositionSummaryDTO> positions,
        boolean fallbackQuoteUsed,
        List<String> fallbackTickers
) {
    public PortfolioSummaryDTO(
            BigDecimal totalPatrimony,
            BigDecimal totalInvested,
            BigDecimal totalProvents,
            ProfitabilityDTO profitability,
            List<AssetAllocationDTO> allocations,
            boolean fallbackQuoteUsed,
            List<String> fallbackTickers
    ) {
        this(totalPatrimony, totalInvested, totalProvents, profitability, allocations, List.of(), fallbackQuoteUsed, fallbackTickers);
    }

    public record ProfitabilityDTO(
            BigDecimal absoluteProfit,
            BigDecimal unrealizedProfit,
            BigDecimal realizedProfit,
            BigDecimal totalPercentage,
            BigDecimal dayAbsoluteVariation,
            BigDecimal dayPercentageVariation,
            boolean dayChangeAvailable,
            List<BenchmarkComparisonDTO> benchmarkComparisons,
            BigDecimal twrPercentage,
            boolean twrReliable
    ) {
        public ProfitabilityDTO(
                BigDecimal absoluteProfit,
                BigDecimal unrealizedProfit,
                BigDecimal realizedProfit,
                BigDecimal totalPercentage,
                BigDecimal dayAbsoluteVariation,
                BigDecimal dayPercentageVariation,
                boolean dayChangeAvailable,
                List<BenchmarkComparisonDTO> benchmarkComparisons,
                BigDecimal twrPercentage
        ) {
            this(
                    absoluteProfit,
                    unrealizedProfit,
                    realizedProfit,
                    totalPercentage,
                    dayAbsoluteVariation,
                    dayPercentageVariation,
                    dayChangeAvailable,
                    benchmarkComparisons,
                    twrPercentage,
                    false
            );
        }

        public ProfitabilityDTO(
                BigDecimal absoluteProfit,
                BigDecimal unrealizedProfit,
                BigDecimal realizedProfit,
                BigDecimal totalPercentage,
                BigDecimal dayAbsoluteVariation,
                BigDecimal dayPercentageVariation,
                boolean dayChangeAvailable,
                List<BenchmarkComparisonDTO> benchmarkComparisons
        ) {
            this(
                    absoluteProfit,
                    unrealizedProfit,
                    realizedProfit,
                    totalPercentage,
                    dayAbsoluteVariation,
                    dayPercentageVariation,
                    dayChangeAvailable,
                    benchmarkComparisons,
                    null,
                    false
            );
        }

        public ProfitabilityDTO(
                BigDecimal absoluteProfit,
                BigDecimal totalPercentage,
                BigDecimal dayAbsoluteVariation,
                BigDecimal dayPercentageVariation,
                boolean dayChangeAvailable,
                List<BenchmarkComparisonDTO> benchmarkComparisons
        ) {
            this(
                    absoluteProfit,
                    absoluteProfit,
                    BigDecimal.ZERO,
                    totalPercentage,
                    dayAbsoluteVariation,
                    dayPercentageVariation,
                    dayChangeAvailable,
                    benchmarkComparisons,
                    null,
                    false
            );
        }
    }

    public record BenchmarkComparisonDTO(
            String name,
            BigDecimal percentage
    ) {
    }
}
