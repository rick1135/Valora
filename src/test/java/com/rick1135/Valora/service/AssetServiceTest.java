package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.AssetRequestDTO;
import com.rick1135.Valora.dto.response.AssetResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.FixedIncomeRateType;
import com.rick1135.Valora.exception.AssetAlreadyExistsException;
import com.rick1135.Valora.mapper.AssetMapper;
import com.rick1135.Valora.repository.AssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        AssetRequestDTO request = new AssetRequestDTO(" petr4 ", "Petrobras", AssetCategory.ACOES, null, null, null, null);
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
        when(assetMapper.toResponse(savedAsset)).thenReturn(new AssetResponseDTO(savedAsset.getId(), "PETR4", "Petrobras", AssetCategory.ACOES, null, null, null, null));

        AssetResponseDTO response = assetService.createAsset(request);

        ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).saveAndFlush(captor.capture());
        Asset persisted = captor.getValue();

        assertThat(persisted.getTicker()).isEqualTo("PETR4");
        assertThat(response.ticker()).isEqualTo("PETR4");
    }

    @Test
    void createAssetThrowsConflictWhenTickerExists() {
        AssetRequestDTO request = new AssetRequestDTO("PETR4", "Petrobras", AssetCategory.ACOES, null, null, null, null);
        when(assetRepository.findByTickerIgnoreCase("PETR4")).thenReturn(Optional.of(new Asset()));

        assertThatThrownBy(() -> assetService.createAsset(request))
                .isInstanceOf(AssetAlreadyExistsException.class)
                .hasMessageContaining("PETR4");
    }

    @Test
    void createAssetConvertsDataIntegrityViolationToDomainConflict() {
        AssetRequestDTO request = new AssetRequestDTO("PETR4", "Petrobras", AssetCategory.ACOES, null, null, null, null);
        Asset mappedAsset = new Asset();
        when(assetRepository.findByTickerIgnoreCase("PETR4")).thenReturn(Optional.empty());
        when(assetMapper.toEntity(request)).thenReturn(mappedAsset);
        when(assetRepository.saveAndFlush(any(Asset.class))).thenThrow(new DataIntegrityViolationException("duplicate", new java.sql.SQLException("duplicate key value violates unique constraint", "23505")));

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
        when(assetMapper.toResponse(asset)).thenReturn(new AssetResponseDTO(asset.getId(), "ITSA4", "Itausa", AssetCategory.ACOES, null, null, null, null));

        var result = assetService.getAllAssets(0, 20, "name", "desc");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ticker()).isEqualTo("ITSA4");
    }

    @Test
    void createAssetValidatesFixedIncomeFields() {
        AssetRequestDTO missingIndexer = new AssetRequestDTO("CDB", "CDB BMG", AssetCategory.RENDA_FIXA, null, new java.math.BigDecimal("110.0"), "Banco BMG", java.time.LocalDate.now().plusDays(10));
        assertThatThrownBy(() -> assetService.createAsset(missingIndexer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Indexador, taxa anual, emissor e data de vencimento sao obrigatorios");

        AssetRequestDTO missingRate = new AssetRequestDTO("CDB", "CDB BMG", AssetCategory.RENDA_FIXA, com.rick1135.Valora.entity.FixedIncomeIndexer.CDI, null, "Banco BMG", java.time.LocalDate.now().plusDays(10));
        assertThatThrownBy(() -> assetService.createAsset(missingRate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Indexador, taxa anual, emissor e data de vencimento sao obrigatorios");

        AssetRequestDTO missingIssuer = new AssetRequestDTO("CDB", "CDB BMG", AssetCategory.RENDA_FIXA, com.rick1135.Valora.entity.FixedIncomeIndexer.CDI, new java.math.BigDecimal("110.0"), null, java.time.LocalDate.now().plusDays(10));
        assertThatThrownBy(() -> assetService.createAsset(missingIssuer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Indexador, taxa anual, emissor e data de vencimento sao obrigatorios");

        AssetRequestDTO missingExpiration = new AssetRequestDTO("CDB", "CDB BMG", AssetCategory.RENDA_FIXA, com.rick1135.Valora.entity.FixedIncomeIndexer.CDI, new java.math.BigDecimal("110.0"), "Banco BMG", null);
        assertThatThrownBy(() -> assetService.createAsset(missingExpiration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Indexador, taxa anual, emissor e data de vencimento sao obrigatorios");

        AssetRequestDTO pastExpiration = new AssetRequestDTO("CDB", "CDB BMG", AssetCategory.RENDA_FIXA, com.rick1135.Valora.entity.FixedIncomeIndexer.CDI, new java.math.BigDecimal("110.0"), "Banco BMG", java.time.LocalDate.now().minusDays(1));
        assertThatThrownBy(() -> assetService.createAsset(pastExpiration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A data de vencimento nao pode estar no passado");

        AssetRequestDTO negativeRate = new AssetRequestDTO("CDB", "CDB BMG", AssetCategory.RENDA_FIXA, com.rick1135.Valora.entity.FixedIncomeIndexer.CDI, new java.math.BigDecimal("-1.0"), "Banco BMG", java.time.LocalDate.now().plusDays(10));
        assertThatThrownBy(() -> assetService.createAsset(negativeRate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A taxa anual nao pode ser negativa");
    }

    @Test
    void createAssetPersistsFixedIncomeSuccessfully() {
        java.math.BigDecimal rate = new java.math.BigDecimal("110.0000");
        java.time.LocalDate expiration = java.time.LocalDate.now().plusDays(365);
        AssetRequestDTO request = new AssetRequestDTO("CDB", "CDB BMG", AssetCategory.RENDA_FIXA, com.rick1135.Valora.entity.FixedIncomeIndexer.CDI, rate, "Banco BMG", expiration);
        
        when(assetRepository.findByTickerIgnoreCase("CDB")).thenReturn(Optional.empty());

        Asset mappedAsset = new Asset();
        mappedAsset.setCategory(AssetCategory.RENDA_FIXA);
        mappedAsset.setIndexer(com.rick1135.Valora.entity.FixedIncomeIndexer.CDI);
        mappedAsset.setAnnualRate(rate);
        mappedAsset.setIssuer("Banco BMG");
        mappedAsset.setExpirationDate(expiration);
        
        when(assetMapper.toEntity(request)).thenReturn(mappedAsset);

        Asset savedAsset = new Asset();
        savedAsset.setId(UUID.randomUUID());
        savedAsset.setTicker("CDB");
        savedAsset.setName("CDB BMG");
        savedAsset.setCategory(AssetCategory.RENDA_FIXA);
        savedAsset.setIndexer(com.rick1135.Valora.entity.FixedIncomeIndexer.CDI);
        savedAsset.setAnnualRate(rate);
        savedAsset.setIssuer("Banco BMG");
        savedAsset.setExpirationDate(expiration);
        
        when(assetRepository.saveAndFlush(any(Asset.class))).thenReturn(savedAsset);
        when(assetMapper.toResponse(savedAsset)).thenReturn(new AssetResponseDTO(savedAsset.getId(), "CDB", "CDB BMG", AssetCategory.RENDA_FIXA, com.rick1135.Valora.entity.FixedIncomeIndexer.CDI, rate, "Banco BMG", expiration));

        AssetResponseDTO response = assetService.createAsset(request);

        ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).saveAndFlush(captor.capture());
        Asset persisted = captor.getValue();

        assertThat(persisted.getTicker()).isEqualTo("CDB");
        assertThat(persisted.getIndexer()).isEqualTo(com.rick1135.Valora.entity.FixedIncomeIndexer.CDI);
        assertThat(persisted.getAnnualRate()).isEqualTo(rate);
        assertThat(persisted.getRateType()).isEqualTo(FixedIncomeRateType.PERCENTAGE_OF_INDEX);
        assertThat(persisted.getIssuer()).isEqualTo("Banco BMG");
        assertThat(persisted.getExpirationDate()).isEqualTo(expiration);
        assertThat(response.indexer()).isEqualTo(com.rick1135.Valora.entity.FixedIncomeIndexer.CDI);
        assertThat(response.annualRate()).isEqualTo(rate);
        assertThat(response.issuer()).isEqualTo("Banco BMG");
        assertThat(response.expirationDate()).isEqualTo(expiration);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "CDB 110% CDI, CDI, PERCENTAGE_OF_INDEX, 110, , PERCENTAGE_OF_INDEX, ",
            "Tesouro SELIC + 0.15%, SELIC, SPREAD, , 0.15, SPREAD, 0.15",
            "Tesouro IPCA + 6.5%, IPCA, SPREAD, , 6.5, SPREAD, 6.5",
            "CDB PRE 13.5%, PRE, FIXED, 13.5, , FIXED, ",
            "CDB PRE sem tipo de taxa, PRE, , 13.5, , FIXED, ",
            "CDB CDI com spread sem tipo de taxa, CDI, , , 0.15, SPREAD, 0.15",
            "Tesouro IPCA com taxa anual legada, IPCA, , 6.5, , SPREAD, 6.5",
            "Tesouro SELIC com spread zero, SELIC, SPREAD, , 0, SPREAD, 0",
            "CDB PRE com taxa e spread zero, PRE, FIXED, 0, 0, FIXED, 0"
    })
    void createAssetPersistsAndReturnsRateModel(String name, FixedIncomeIndexer indexer,
                                               FixedIncomeRateType requestedType, BigDecimal annualRate,
                                               BigDecimal requestedSpread, FixedIncomeRateType expectedType,
                                               BigDecimal expectedSpread) {
        AssetRequestDTO request = new AssetRequestDTO("RF1", name, AssetCategory.RENDA_FIXA, indexer,
                annualRate, "Emissor", LocalDate.now().plusDays(30), requestedType, requestedSpread);
        AssetService service = new AssetService(assetRepository, Mappers.getMapper(AssetMapper.class));
        when(assetRepository.saveAndFlush(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AssetResponseDTO response = service.createAsset(request);

        ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).saveAndFlush(captor.capture());
        Asset persisted = captor.getValue();
        assertThat(persisted.getRateType()).isEqualTo(expectedType);
        assertThat(persisted.getAnnualRate()).isEqualTo(annualRate);
        assertThat(persisted.getSpreadRate()).isEqualTo(expectedSpread);
        assertThat(response.rateType()).isEqualTo(expectedType);
        assertThat(response.annualRate()).isEqualTo(annualRate);
        assertThat(response.spreadRate()).isEqualTo(expectedSpread);
    }

    @ParameterizedTest
    @CsvSource({
            "IPCA, PERCENTAGE_OF_INDEX, 110, , CDI ou SELIC",
            "PRE, SPREAD, , 0.15, 'CDI, SELIC ou IPCA'",
            "CDI, FIXED, 13.5, , indexador PRE",
            "PRE, FIXED, 13.5, 0.15, spread nulo ou zero",
            "PRE, FIXED, -1, , taxa anual nao pode ser negativa",
            "CDI, PERCENTAGE_OF_INDEX, 0, , taxa anual deve ser maior que zero",
            "IPCA, SPREAD, , -1, spread e obrigatorio e nao pode ser negativo",
            "IPCA, SPREAD, , , spread e obrigatorio e nao pode ser negativo"
    })
    void createAssetRejectsInvalidRateModel(FixedIncomeIndexer indexer, FixedIncomeRateType rateType,
                                            BigDecimal annualRate, BigDecimal spreadRate, String errorMessage) {
        AssetRequestDTO request = new AssetRequestDTO("RF1", "Renda fixa", AssetCategory.RENDA_FIXA, indexer,
                annualRate, "Emissor", LocalDate.now().plusDays(30), rateType, spreadRate);

        assertThatThrownBy(() -> assetService.createAsset(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(errorMessage);
        verifyNoInteractions(assetRepository, assetMapper);
    }
}
