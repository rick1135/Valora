package com.rick1135.Valora.dto.request;

import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.FixedIncomeRateType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FixedIncomeAssetRequestDTO(
        @NotBlank String ticker,
        @NotBlank String name,
        @NotNull FixedIncomeIndexer indexer,
        FixedIncomeRateType rateType,
        BigDecimal annualRate,
        BigDecimal spreadRate,
        @NotBlank String issuer,
        @NotNull LocalDate expirationDate
) {
}
