package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioSummaryDTO(
        BigDecimal totalPatrimony,
        BigDecimal totalInvested,
        BigDecimal totalProvents,
        BigDecimal absoluteProfitLoss,
        BigDecimal percentageProfitLoss,
        List<AssetAllocationDTO> allocations
) {
}
