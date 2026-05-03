package com.rick1135.Valora.dto.response;

import com.rick1135.Valora.entity.AssetCategory;

import java.util.UUID;

public record AssetResponseDTO(
        UUID id, 
        String ticker, 
        String name, 
        AssetCategory category,
        com.rick1135.Valora.entity.FixedIncomeIndexer indexer,
        java.math.BigDecimal annualRate,
        String issuer,
        java.time.LocalDate expirationDate
) {
}
