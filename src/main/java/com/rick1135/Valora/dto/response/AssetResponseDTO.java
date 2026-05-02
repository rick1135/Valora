package com.rick1135.Valora.dto.response;

import com.rick1135.Valora.entity.AssetCategory;

import java.util.UUID;

public record AssetResponseDTO(UUID id, String ticker, String name, AssetCategory category) {
}
