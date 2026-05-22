package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record AssetPositionSummaryDTO(
        UUID assetId,
        String ticker,
        String category,
        BigDecimal totalValue,
        BigDecimal quantity,
        BigDecimal currentPrice,
        boolean marketPriceReliable
) {
}
