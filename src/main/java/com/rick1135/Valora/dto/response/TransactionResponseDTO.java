package com.rick1135.Valora.dto.response;

import com.rick1135.Valora.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponseDTO(
        UUID transactionId,
        UUID assetId,
        String ticker,
        TransactionType type,
        BigDecimal quantity,
        BigDecimal unitPrice,
        Instant transactionDate,
        BigDecimal positionQuantity,
        BigDecimal averagePrice
) {
}
