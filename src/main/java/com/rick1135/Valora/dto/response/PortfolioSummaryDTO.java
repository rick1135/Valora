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
            BigDecimal totalPercentage,
            BigDecimal dayAbsoluteVariation,
            BigDecimal dayPercentageVariation,
            boolean dayChangeAvailable,
            List<BenchmarkComparisonDTO> benchmarkComparisons
    ) {
    }

    public record BenchmarkComparisonDTO(
            String name,
            BigDecimal percentage
    ) {
    }
}
