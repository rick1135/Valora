package com.rick1135.Valora.dto.request;

import com.rick1135.Valora.entity.AssetCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AssetImportRequestDTO(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9]{4,12}$", message = "Ticker invalido. Use entre 4 e 12 caracteres alfanumericos.")
        String ticker,
        AssetCategory category
) {
}
