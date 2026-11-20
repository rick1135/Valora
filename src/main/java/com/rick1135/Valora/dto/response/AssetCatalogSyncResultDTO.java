package com.rick1135.Valora.dto.response;

public record AssetCatalogSyncResultDTO(int fetched, int inserted, int skipped, boolean complete, String status) {
    public AssetCatalogSyncResultDTO(int fetched, int inserted, int skipped) {
        this(fetched, inserted, skipped, true, "COMPLETED");
    }
}
