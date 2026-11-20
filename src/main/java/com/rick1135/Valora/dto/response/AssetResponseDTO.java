package com.rick1135.Valora.dto.response;

import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.FixedIncomeRateType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AssetResponseDTO(
        UUID id, 
        String ticker, 
        String name, 
        AssetCategory category,
        FixedIncomeIndexer indexer,
        BigDecimal annualRate,
        String issuer,
        LocalDate expirationDate,
        FixedIncomeRateType rateType,
        BigDecimal spreadRate,
        boolean persisted
) {
    public AssetResponseDTO(UUID id, String ticker, String name, AssetCategory category, FixedIncomeIndexer indexer,
                            BigDecimal annualRate, String issuer, LocalDate expirationDate) {
        this(id, ticker, name, category, indexer, annualRate, issuer, expirationDate, null, null, true);
    }

    public AssetResponseDTO(UUID id, String ticker, String name, AssetCategory category, FixedIncomeIndexer indexer,
                            BigDecimal annualRate, String issuer, LocalDate expirationDate,
                            FixedIncomeRateType rateType, BigDecimal spreadRate) {
        this(id, ticker, name, category, indexer, annualRate, issuer, expirationDate, rateType, spreadRate, true);
    }
}
