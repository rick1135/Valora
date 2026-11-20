package com.rick1135.Valora.dto.brapi;

public record BrapiListedAssetDTO(String stock, String name, String type, String subType) {
    public BrapiListedAssetDTO(String stock, String name, String type) {
        this(stock, name, type, null);
    }
}
