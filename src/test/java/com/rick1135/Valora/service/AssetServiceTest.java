package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.AssetRequestDTO;
import com.rick1135.Valora.dto.response.AssetResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.exception.AssetAlreadyExistsException;
import com.rick1135.Valora.mapper.AssetMapper;
import com.rick1135.Valora.repository.AssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private AssetMapper assetMapper;

    @InjectMocks
    private AssetService assetService;

    @Test
    void createAssetNormalizesTickerAndSaves() {
        AssetRequestDTO request = new AssetRequestDTO(" petr4 ", "Petrobras", AssetCategory.ACOES);
        when(assetRepository.findByTickerIgnoreCase("PETR4")).thenReturn(Optional.empty());

        Asset mappedAsset = new Asset();
        mappedAsset.setCategory(AssetCategory.ACOES);
        when(assetMapper.toEntity(request)).thenReturn(mappedAsset);

        Asset savedAsset = new Asset();
        savedAsset.setId(UUID.randomUUID());
        savedAsset.setTicker("PETR4");
        savedAsset.setName("Petrobras");
        savedAsset.setCategory(AssetCategory.ACOES);
        when(assetRepository.saveAndFlush(any(Asset.class))).thenReturn(savedAsset);
        when(assetMapper.toResponse(savedAsset)).thenReturn(new AssetResponseDTO(savedAsset.getId(), "PETR4", "Petrobras", AssetCategory.ACOES));

        AssetResponseDTO response = assetService.createAsset(request);

        ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).saveAndFlush(captor.capture());
        Asset persisted = captor.getValue();

        assertThat(persisted.getTicker()).isEqualTo("PETR4");
        assertThat(response.ticker()).isEqualTo("PETR4");
    }

    @Test
    void createAssetThrowsConflictWhenTickerExists() {
        AssetRequestDTO request = new AssetRequestDTO("PETR4", "Petrobras", AssetCategory.ACOES);
        when(assetRepository.findByTickerIgnoreCase("PETR4")).thenReturn(Optional.of(new Asset()));

        assertThatThrownBy(() -> assetService.createAsset(request))
                .isInstanceOf(AssetAlreadyExistsException.class)
                .hasMessageContaining("PETR4");
    }

    @Test
    void createAssetConvertsDataIntegrityViolationToDomainConflict() {
        AssetRequestDTO request = new AssetRequestDTO("PETR4", "Petrobras", AssetCategory.ACOES);
        Asset mappedAsset = new Asset();
        when(assetRepository.findByTickerIgnoreCase("PETR4")).thenReturn(Optional.empty());
        when(assetMapper.toEntity(request)).thenReturn(mappedAsset);
        when(assetRepository.saveAndFlush(any(Asset.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> assetService.createAsset(request))
                .isInstanceOf(AssetAlreadyExistsException.class)
                .hasMessageContaining("PETR4");
    }

    @Test
    void getAllAssetsUsesPaginationAndSort() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("ITSA4");
        asset.setName("Itausa");
        asset.setCategory(AssetCategory.ACOES);

        when(assetRepository.findAll(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "name"))))
                .thenReturn(new PageImpl<>(List.of(asset)));
        when(assetMapper.toResponse(asset)).thenReturn(new AssetResponseDTO(asset.getId(), "ITSA4", "Itausa", AssetCategory.ACOES));

        var result = assetService.getAllAssets(0, 20, "name", "desc");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ticker()).isEqualTo("ITSA4");
    }
}
