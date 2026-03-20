package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record PositionResponseDTO(
        UUID assetId,
        String ticker,
        String assetName,
        BigDecimal quantity,
        BigDecimal averagePrice,
        BigDecimal totalCost,
        BigDecimal currentPrice,
        BigDecimal currentTotalValue,
        BigDecimal profitability
) {
}
