package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.AssetRequestDTO;
import com.rick1135.Valora.dto.response.AssetResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.exception.AssetAlreadyExistsException;
import com.rick1135.Valora.repository.AssetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class AssetService {
    private final AssetRepository assetRepository;

    public AssetService(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public AssetResponseDTO createAsset(AssetRequestDTO request) {
        String normalizedTicker = normalizeTicker(request.ticker());

        if (assetRepository.findByTickerIgnoreCase(normalizedTicker).isPresent()) {
            throw new AssetAlreadyExistsException("Ativo com ticker '" + normalizedTicker + "' ja cadastrado.");
        }

        Asset asset = new Asset();
        asset.setTicker(normalizedTicker);
        asset.setName(request.name().trim());
        asset.setCategory(request.category());

        try {
            Asset savedAsset = assetRepository.saveAndFlush(asset);
            return new AssetResponseDTO(savedAsset);
        } catch (DataIntegrityViolationException exception) {
            throw new AssetAlreadyExistsException("Ativo com ticker '" + normalizedTicker + "' ja cadastrado.");
        }
    }

    @Transactional(readOnly = true)
    public List<AssetResponseDTO> getAllAssets(int page, int size, String sort, String direction) {
        Sort.Direction sortDirection = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        String safeSortField = isSortableField(sort) ? sort : "ticker";
        PageRequest pageable = PageRequest.of(page, size, Sort.by(sortDirection, safeSortField));
        return assetRepository.findAll(pageable)
                .stream()
                .map(AssetResponseDTO::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssetResponseDTO> searchAssets(String ticker) {
        String normalizedTicker = normalizeTicker(ticker);
        return assetRepository.findByTickerContainingIgnoreCase(normalizedTicker)
                .stream()
                .map(AssetResponseDTO::new)
                .toList();
    }

    private String normalizeTicker(String ticker) {
        return ticker.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isSortableField(String field) {
        return "ticker".equalsIgnoreCase(field)
                || "name".equalsIgnoreCase(field)
                || "category".equalsIgnoreCase(field);
    }
}
