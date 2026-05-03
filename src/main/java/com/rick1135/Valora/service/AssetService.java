package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.AssetRequestDTO;
import com.rick1135.Valora.dto.response.AssetResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.exception.AssetAlreadyExistsException;
import com.rick1135.Valora.mapper.AssetMapper;
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
    private final AssetMapper assetMapper;

    public AssetService(AssetRepository assetRepository, AssetMapper assetMapper) {
        this.assetRepository = assetRepository;
        this.assetMapper = assetMapper;
    }

    public AssetResponseDTO createAsset(AssetRequestDTO request) {
        if (request.category() == com.rick1135.Valora.entity.AssetCategory.RENDA_FIXA) {
            if (request.indexer() == null || request.annualRate() == null || request.issuer() == null || request.expirationDate() == null) {
                throw new IllegalArgumentException("Indexador, taxa anual, emissor e data de vencimento sao obrigatorios para ativos de renda fixa.");
            }
            if (request.expirationDate().isBefore(java.time.LocalDate.now())) {
                throw new IllegalArgumentException("A data de vencimento nao pode estar no passado.");
            }
            if (request.annualRate().compareTo(java.math.BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("A taxa anual nao pode ser negativa.");
            }
        }

        String normalizedTicker = normalizeTicker(request.ticker());

        if (assetRepository.findByTickerIgnoreCase(normalizedTicker).isPresent()) {
            throw new AssetAlreadyExistsException("Ativo com ticker '" + normalizedTicker + "' ja cadastrado.");
        }

        Asset asset = assetMapper.toEntity(request);
        asset.setTicker(normalizedTicker);
        asset.setName(request.name().trim());

        try {
            Asset savedAsset = assetRepository.saveAndFlush(asset);
            return assetMapper.toResponse(savedAsset);
        } catch (DataIntegrityViolationException exception) {
            Throwable rootCause = exception.getMostSpecificCause();
            if (rootCause instanceof java.sql.SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if ("23505".equals(sqlState)) {
                    throw new AssetAlreadyExistsException("Ativo com ticker '" + normalizedTicker + "' ja cadastrado.");
                }
            }
            throw new IllegalArgumentException("Erro de integridade de dados ao salvar o ativo: " + (rootCause != null ? rootCause.getMessage() : ""));
        }
    }

    @Transactional(readOnly = true)
    public List<AssetResponseDTO> getAllAssets(int page, int size, String sort, String direction) {
        Sort.Direction sortDirection = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        String safeSortField = isSortableField(sort) ? sort : "ticker";
        PageRequest pageable = PageRequest.of(page, size, Sort.by(sortDirection, safeSortField));
        return assetRepository.findAll(pageable)
                .stream()
                .map(assetMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssetResponseDTO> searchAssets(String ticker) {
        String normalizedTicker = normalizeTicker(ticker);
        return assetRepository.findByTickerContainingIgnoreCase(normalizedTicker)
                .stream()
                .map(assetMapper::toResponse)
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
