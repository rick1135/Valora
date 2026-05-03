package com.rick1135.Valora.dto.request;

import com.rick1135.Valora.entity.AssetCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AssetRequestDTO(
        @NotBlank(message = "Ticker e obrigatorio.")
        @Size(max = 20, message = "Ticker deve ter no maximo 20 caracteres.")
        @Pattern(
                regexp = "^[A-Za-z0-9.\\-]{1,20}$",
                message = "Ticker deve conter apenas letras, numeros, ponto ou hifen."
        )
        String ticker,
        @NotBlank(message = "Nome e obrigatorio.")
        @Size(max = 255, message = "Nome deve ter no maximo 255 caracteres.")
        String name,
        @NotNull(message = "Categoria e obrigatoria.")
        AssetCategory category,
        com.rick1135.Valora.entity.FixedIncomeIndexer indexer,
        java.math.BigDecimal annualRate,
        String issuer,
        java.time.LocalDate expirationDate
) {
}
