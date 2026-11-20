package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.request.AssetImportRequestDTO;
import com.rick1135.Valora.dto.request.AssetRequestDTO;
import com.rick1135.Valora.dto.request.FixedIncomeAssetRequestDTO;
import com.rick1135.Valora.dto.response.AssetCatalogSyncResultDTO;
import com.rick1135.Valora.dto.response.AssetResponseDTO;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.service.AssetCatalogSyncService;
import com.rick1135.Valora.service.AssetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequestMapping("/assets")
public class AssetController {
    private final AssetService assetService;
    private final @Nullable AssetCatalogSyncService assetCatalogSyncService;

    public AssetController(AssetService assetService) {
        this(assetService, null);
    }

    @Autowired
    public AssetController(AssetService assetService, @Nullable AssetCatalogSyncService assetCatalogSyncService) {
        this.assetService = assetService;
        this.assetCatalogSyncService = assetCatalogSyncService;
    }

    @PostMapping
    public ResponseEntity<AssetResponseDTO> registerAsset(@Valid @RequestBody AssetRequestDTO assetRequestDTO) {
        AssetResponseDTO response = assetService.createAsset(assetRequestDTO);
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/import")
    public ResponseEntity<AssetResponseDTO> importAsset(@Valid @RequestBody AssetImportRequestDTO request) {
        return ResponseEntity.ok(assetService.importAssetFromBrapi(request));
    }

    @PostMapping("/sync")
    public ResponseEntity<AssetCatalogSyncResultDTO> syncAssets() {
        AssetCatalogSyncResultDTO result = assetCatalogSyncService == null
                ? new AssetCatalogSyncResultDTO(0, 0, 0)
                : assetCatalogSyncService.syncB3Catalog();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/fixed-income")
    public ResponseEntity<AssetResponseDTO> registerFixedIncomeAsset(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody FixedIncomeAssetRequestDTO request
    ) {
        return ResponseEntity.status(201).body(assetService.createFixedIncomeAsset(user, request));
    }

    @GetMapping
    public ResponseEntity<List<AssetResponseDTO>> getAllAssets(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page deve ser >= 0.") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "size deve ser >= 1.") @Max(value = 100, message = "size deve ser <= 100.") int size,
            @RequestParam(defaultValue = "ticker") String sort,
            @RequestParam(defaultValue = "asc") String direction
    ) {
        return ResponseEntity.ok(assetService.getAllAssets(user, page, size, sort, direction));
    }

    @GetMapping("/search")
    public ResponseEntity<List<AssetResponseDTO>> searchAssets(
            @AuthenticationPrincipal User user,
            @RequestParam @NotBlank(message = "ticker e obrigatorio.") String ticker,
            @RequestParam(defaultValue = "true") boolean includeExternal
    ) {
        return ResponseEntity.ok(assetService.searchAssets(user, ticker, includeExternal));
    }
}
