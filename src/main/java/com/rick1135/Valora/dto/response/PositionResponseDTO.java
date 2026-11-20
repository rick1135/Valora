package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        BigDecimal profitability,
        LocalDate valuedThrough,
        boolean pendingLatestObservation
) {
    public PositionResponseDTO(
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
        this(assetId, ticker, assetName, quantity, averagePrice, totalCost, currentPrice, currentTotalValue,
                profitability, null, false);
    }
}
