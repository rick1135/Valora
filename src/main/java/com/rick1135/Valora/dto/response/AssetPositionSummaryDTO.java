package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AssetPositionSummaryDTO(
        UUID assetId,
        String ticker,
        String category,
        BigDecimal totalValue,
        BigDecimal quantity,
        BigDecimal currentPrice,
        boolean marketPriceReliable,
        LocalDate valuedThrough,
        boolean pendingLatestObservation
) {
    public AssetPositionSummaryDTO(
            UUID assetId,
            String ticker,
            String category,
            BigDecimal totalValue,
            BigDecimal quantity,
            BigDecimal currentPrice,
            boolean marketPriceReliable
    ) {
        this(assetId, ticker, category, totalValue, quantity, currentPrice, marketPriceReliable, null, false);
    }
}
