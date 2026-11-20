package com.rick1135.Valora.dto.request;

import com.rick1135.Valora.entity.AssetCategory;
import jakarta.validation.constraints.NotBlank;

public record AssetImportRequestDTO(
        @NotBlank String ticker,
        AssetCategory category
) {
}
