package com.rick1135.Valora.dto.response;

import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;

import java.util.UUID;

public record AssetResponseDTO(UUID id, String ticker, String name, AssetCategory category) {
    public AssetResponseDTO(Asset asset) {
        this(asset.getId(), asset.getTicker(), asset.getName(), asset.getCategory());
    }
}
