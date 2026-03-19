package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.request.AssetRequestDTO;
import com.rick1135.Valora.dto.response.AssetResponseDTO;
import com.rick1135.Valora.service.AssetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequestMapping("/assets")
public class AssetController {
    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @PostMapping
    public ResponseEntity<AssetResponseDTO> registerAsset(@Valid @RequestBody AssetRequestDTO assetRequestDTO) {
        AssetResponseDTO response = assetService.createAsset(assetRequestDTO);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping
    public ResponseEntity<List<AssetResponseDTO>> getAllAssets(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page deve ser >= 0.") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "size deve ser >= 1.") @Max(value = 100, message = "size deve ser <= 100.") int size,
            @RequestParam(defaultValue = "ticker") String sort,
            @RequestParam(defaultValue = "asc") String direction
    ) {
        return ResponseEntity.ok(assetService.getAllAssets(page, size, sort, direction));
    }

    @GetMapping("/search")
    public ResponseEntity<List<AssetResponseDTO>> searchAssets(
            @RequestParam @NotBlank(message = "ticker e obrigatorio.") String ticker
    ) {
        return ResponseEntity.ok(assetService.searchAssets(ticker));
    }
}
