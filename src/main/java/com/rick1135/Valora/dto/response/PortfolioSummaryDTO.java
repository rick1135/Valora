package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioSummaryDTO(
        BigDecimal totalPatrimony,
        BigDecimal totalInvested,
        BigDecimal totalProvents,
        ProfitabilityDTO profitability,
        List<AssetAllocationDTO> allocations,
        boolean fallbackQuoteUsed,
        List<String> fallbackTickers
) {
    public record ProfitabilityDTO(
            BigDecimal absoluteProfit,
            BigDecimal totalPercentage,
            BigDecimal dayAbsoluteVariation,
            BigDecimal dayPercentageVariation,
            boolean dayChangeAvailable
    ) {
    }
}
