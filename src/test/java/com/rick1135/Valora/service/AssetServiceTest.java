package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import com.rick1135.Valora.dto.request.AssetImportRequestDTO;
import com.rick1135.Valora.dto.request.AssetRequestDTO;
import com.rick1135.Valora.dto.request.FixedIncomeAssetRequestDTO;
import com.rick1135.Valora.dto.response.AssetResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.FixedIncomeRateType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.AssetAlreadyExistsException;
import com.rick1135.Valora.exception.AssetNotFoundException;
import com.rick1135.Valora.mapper.AssetMapper;
import com.rick1135.Valora.repository.AssetRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private AssetMapper assetMapper;

    @Mock
    private BrapiClient brapiClient;

    @InjectMocks
    private AssetService assetService;

    @ParameterizedTest
    @CsvSource({
            "HGLG11, ' Fundo Imobiliario ', HGLG, , Fundo Imobiliario, FII",
            "IVVB11, ' ', iShares S&P 500, , iShares S&P 500, ETF",
            "BOVA11, ISHARES IBOVESPA FUNDO DE INDICE, , , ISHARES IBOVESPA FUNDO DE INDICE, ETF",
            "TEST11, FUNDO DE ÍNDICE IMOBILIÁRIO, , , FUNDO DE ÍNDICE IMOBILIÁRIO, ETF",
            "KNRI11, KINEA RENDA IMOBILIARIA FII, , , KINEA RENDA IMOBILIARIA FII, FII",
            "PETR4, , ' ', , PETR4, ACOES",
            "IVVB11, iShares, , ACOES, iShares, ACOES"
    })
    void importsNewAssetWithResolvedNameAndCategory(String ticker, String longName, String shortName,
                                                    AssetCategory requestedCategory, String expectedName,
                                                    AssetCategory expectedCategory) {
        when(brapiClient.getQuote(ticker, "token")).thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO(
                " " + ticker.toLowerCase(Locale.ROOT) + " ", BigDecimal.TEN,
                null, null, null, null, null, null, null, shortName, longName))));
        when(assetRepository.saveAndFlush(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AssetResponseDTO response = configuredBrapiService()
                .importAssetFromBrapi(new AssetImportRequestDTO(ticker.toLowerCase(Locale.ROOT), requestedCategory));

        assertThat(response.ticker()).isEqualTo(ticker);
        assertThat(response.name()).isEqualTo(expectedName);
        assertThat(response.category()).isEqualTo(expectedCategory);
        ArgumentCaptor<Asset> saved = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOwnerUser()).isNull();
    }

    @Test
    void returnsExistingAssetWithoutCallingBrapiOrSaving() {
        Asset existing = new Asset();
        AssetResponseDTO response = new AssetResponseDTO(UUID.randomUUID(), "PETR4", "Petrobras",
                AssetCategory.ACOES, null, null, null, null);
        when(assetRepository.findByTickerIgnoreCaseAndOwnerUserIsNull("PETR4")).thenReturn(Optional.of(existing));
        when(assetMapper.toResponse(existing)).thenReturn(response);

        assertThat(assetService.importAssetFromBrapi(new AssetImportRequestDTO("petr4", null))).isSameAs(response);

        verifyNoInteractions(brapiClient);
        verify(assetRepository, never()).saveAndFlush(any(Asset.class));
    }

    @Test
    void returnsConcurrentInsertAfterUniqueViolation() {
        Asset existing = new Asset();
        existing.setTicker("PETR4");
        existing.setName("Petrobras");
        existing.setCategory(AssetCategory.ACOES);
        existing.setId(UUID.randomUUID());
        when(assetRepository.findByTickerIgnoreCaseAndOwnerUserIsNull("PETR4"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(brapiClient.getQuote("PETR4", "token"))
                .thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO("PETR4", BigDecimal.TEN))));
        when(assetRepository.saveAndFlush(any(Asset.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate", new SQLException("duplicate", "23505")));

        assertThat(configuredBrapiService().importAssetFromBrapi(new AssetImportRequestDTO("PETR4", null)).id())
                .isEqualTo(existing.getId());
    }

    @Test
    void rejectsMissingBrapiResultsAndHttpNotFound() {
        when(brapiClient.getQuote("PETR4", "token"))
                .thenReturn(new BrapiResponseDTO(List.of()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null));
        AssetService service = configuredBrapiService();
        AssetImportRequestDTO request = new AssetImportRequestDTO("PETR4", null);

        assertThatThrownBy(() -> service.importAssetFromBrapi(request))
                .isInstanceOf(AssetNotFoundException.class).hasMessageContaining("PETR4");
        assertThatThrownBy(() -> service.importAssetFromBrapi(request))
                .isInstanceOf(AssetNotFoundException.class).hasMessageContaining("PETR4");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PETR4,VALE3", " PETR4 ", "PET R4", "PETR4/VALE3", "PETR4?x=y", "ABC", "ABCDEFGHIJKL1"})
    void rejectsInvalidTickerInDtoAndService(String ticker) {
        AssetImportRequestDTO request = new AssetImportRequestDTO(ticker, null);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("ticker"));
        }
        assertThatThrownBy(() -> configuredBrapiService().importAssetFromBrapi(request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ticker invalido");
        verifyNoInteractions(assetRepository, brapiClient);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "VALE3")
    void rejectsMissingOrMismatchedBrapiSymbol(String symbol) {
        when(brapiClient.getQuote("PETR4", "token"))
                .thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO(symbol, BigDecimal.TEN))));

        assertThatThrownBy(() -> configuredBrapiService().importAssetFromBrapi(new AssetImportRequestDTO("PETR4", null)))
                .isInstanceOf(AssetNotFoundException.class).hasMessageContaining("PETR4");
        verify(assetRepository, never()).saveAndFlush(any());
    }

    @Test
    void injectsBrapiTokenFromEnvironment() {
        when(brapiClient.getQuote("PETR4", "configured-token"))
                .thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO("PETR4", BigDecimal.TEN))));
        when(assetRepository.saveAndFlush(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("test-env", Map.of("BRAPI_TOKEN", "configured-token"))))
                .withBean(AssetRepository.class, () -> assetRepository)
                .withBean(AssetMapper.class, () -> Mappers.getMapper(AssetMapper.class))
                .withBean(MarketCalendar.class, () -> new MarketCalendar(Clock.systemUTC()))
                .withBean(BrapiClient.class, () -> brapiClient)
                .withUserConfiguration(AssetService.class)
                .run(context -> assertThat(context.getBean(AssetService.class)
                        .importAssetFromBrapi(new AssetImportRequestDTO("PETR4", null)).ticker()).isEqualTo("PETR4"));

        verify(brapiClient).getQuote("PETR4", "configured-token");
    }

    @Test
    void reportsBrapiConnectionError() {
        when(brapiClient.getQuote("PETR4", "token")).thenThrow(new RestClientException("connection error"));

        assertThatThrownBy(() -> configuredBrapiService().importAssetFromBrapi(new AssetImportRequestDTO("PETR4", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("consultar").hasMessageContaining("BRAPI");
    }

    @Test
    void rejectsImportWhenBrapiIsNotConfigured() {
        AssetService service = new AssetService(assetRepository, assetMapper, null, brapiClient, " ");

        assertThatThrownBy(() -> service.importAssetFromBrapi(new AssetImportRequestDTO("PETR4", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("token da BRAPI");
        verifyNoInteractions(brapiClient);
    }

    @Test
    void directsFixedIncomeImportsToTheirOwnEndpoint() {
        assertThatThrownBy(() -> assetService.importAssetFromBrapi(new AssetImportRequestDTO("CDB", AssetCategory.RENDA_FIXA)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/assets/fixed-income");
        verifyNoInteractions(assetRepository, brapiClient);
    }

    @Test
    void createsFixedIncomeWithExistingRateValidation() {
        LocalDate expiration = LocalDate.now().plusDays(30);
        BigDecimal spread = new BigDecimal("6.5");
        FixedIncomeAssetRequestDTO request = new FixedIncomeAssetRequestDTO(" rf1 ", " Tesouro IPCA ",
                FixedIncomeIndexer.IPCA, FixedIncomeRateType.SPREAD, null, spread, "Tesouro Nacional", expiration);
        AssetService service = new AssetService(assetRepository, Mappers.getMapper(AssetMapper.class));
        when(assetRepository.saveAndFlush(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        User owner = new User();
        owner.setId(UUID.randomUUID());

        AssetResponseDTO result = service.createFixedIncomeAsset(owner, request);

        ArgumentCaptor<Asset> saved = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).findByTickerIgnoreCaseAndOwnerUser("RF1", owner);
        verify(assetRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOwnerUser()).isSameAs(owner);
        assertThat(result.ticker()).isEqualTo("RF1");
        assertThat(result.name()).isEqualTo("Tesouro IPCA");
        assertThat(result.category()).isEqualTo(AssetCategory.RENDA_FIXA);
        assertThat(result.indexer()).isEqualTo(FixedIncomeIndexer.IPCA);
        assertThat(result.rateType()).isEqualTo(FixedIncomeRateType.SPREAD);
        assertThat(result.spreadRate()).isEqualTo(spread);
        assertThat(result.issuer()).isEqualTo("Tesouro Nacional");
        assertThat(result.expirationDate()).isEqualTo(expiration);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsFixedIncomeTickerAlreadyInGlobalCatalogOrOwnedByUser(boolean global) {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        FixedIncomeAssetRequestDTO request = new FixedIncomeAssetRequestDTO("RF01", "Renda fixa",
                FixedIncomeIndexer.PRE, FixedIncomeRateType.FIXED, BigDecimal.TEN, null,
                "Emissor", LocalDate.now().plusDays(30));
        Asset existing = new Asset();
        existing.setTicker("RF01");
        if (global) {
            when(assetRepository.findByTickerIgnoreCaseAndOwnerUserIsNull("RF01")).thenReturn(Optional.of(existing));
        } else {
            existing.setOwnerUser(owner);
            when(assetRepository.findByTickerIgnoreCaseAndOwnerUser("RF01", owner)).thenReturn(Optional.of(existing));
        }

        assertThatThrownBy(() -> assetService.createFixedIncomeAsset(owner, request))
                .isInstanceOf(AssetAlreadyExistsException.class).hasMessageContaining("RF01");
        verify(assetRepository, never()).saveAndFlush(any());
    }

    private AssetService configuredBrapiService() {
        return new AssetService(assetRepository, Mappers.getMapper(AssetMapper.class), null, brapiClient, "token");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void createAssetNormalizesTickerAndSaves(String issuer) {
        AssetRequestDTO request = new AssetRequestDTO(" petr4 ", "Petrobras", AssetCategory.ACOES, null, null, issuer, null);
        when(assetRepository.findByTickerIgnoreCaseAndOwnerUserIsNull("PETR4")).thenReturn(Optional.empty());

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
        assertThat(persisted.getOwnerUser()).isNull();
        assertThat(response.ticker()).isEqualTo("PETR4");
    }

    @Test
    void createAssetThrowsConflictWhenTickerExists() {
        AssetRequestDTO request = new AssetRequestDTO("PETR4", "Petrobras", AssetCategory.ACOES, null, null, null, null);
        when(assetRepository.findByTickerIgnoreCaseAndOwnerUserIsNull("PETR4")).thenReturn(Optional.of(new Asset()));

        assertThatThrownBy(() -> assetService.createAsset(request))
                .isInstanceOf(AssetAlreadyExistsException.class)
                .hasMessageContaining("PETR4");
    }

    @Test
    void createAssetConvertsDataIntegrityViolationToDomainConflict() {
        AssetRequestDTO request = new AssetRequestDTO("PETR4", "Petrobras", AssetCategory.ACOES, null, null, null, null);
        Asset mappedAsset = new Asset();
        when(assetRepository.findByTickerIgnoreCaseAndOwnerUserIsNull("PETR4")).thenReturn(Optional.empty());
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
    void listsAndSearchesOnlyAssetsVisibleToTheUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        Asset privateAsset = new Asset();
        privateAsset.setId(UUID.randomUUID());
        privateAsset.setOwnerUser(user);
        AssetResponseDTO response = new AssetResponseDTO(privateAsset.getId(), "RF01", "Renda fixa",
                AssetCategory.RENDA_FIXA, null, null, null, null);
        when(assetRepository.findVisibleToUser(user, PageRequest.of(0, 20, Sort.by("ticker"))))
                .thenReturn(new PageImpl<>(List.of(privateAsset)));
        when(assetRepository.searchVisibleToUser(user, "RF")).thenReturn(List.of(privateAsset));
        when(assetMapper.toResponse(privateAsset)).thenReturn(response);

        assertThat(assetService.getAllAssets(user, 0, 20, "ticker", "asc")).containsExactly(response);
        assertThat(assetService.searchAssets(user, " rf ")).containsExactly(response);
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
    void createAssetPersistsLegacyCdiWithoutRateType() {
        java.math.BigDecimal rate = new java.math.BigDecimal("110.0000");
        java.time.LocalDate expiration = java.time.LocalDate.now().plusDays(365);
        AssetRequestDTO request = new AssetRequestDTO("CDB", "CDB BMG", AssetCategory.RENDA_FIXA, com.rick1135.Valora.entity.FixedIncomeIndexer.CDI, rate, "Banco BMG", expiration);
        
        when(assetRepository.findByTickerIgnoreCaseAndOwnerUserIsNull("CDB")).thenReturn(Optional.empty());

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
        assertThat(persisted.getRateType()).isNull();
        assertThat(persisted.getIssuer()).isEqualTo("Banco BMG");
        assertThat(persisted.getExpirationDate()).isEqualTo(expiration);
        assertThat(response.indexer()).isEqualTo(com.rick1135.Valora.entity.FixedIncomeIndexer.CDI);
        assertThat(response.annualRate()).isEqualTo(rate);
        assertThat(response.rateType()).isNull();
        assertThat(response.issuer()).isEqualTo("Banco BMG");
        assertThat(response.expirationDate()).isEqualTo(expiration);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "CDB 110% CDI, CDI, PERCENTAGE_OF_INDEX, 110, , PERCENTAGE_OF_INDEX, ",
            "Tesouro SELIC com percentual e spread zero, SELIC, PERCENTAGE_OF_INDEX, 100, 0, PERCENTAGE_OF_INDEX, 0",
            "CDB CDI com spread explicito, CDI, SPREAD, , 0.15, SPREAD, 0.15",
            "Tesouro SELIC + 0.15%, SELIC, SPREAD, , 0.15, SPREAD, 0.15",
            "Tesouro IPCA + 6.5%, IPCA, SPREAD, , 6.5, SPREAD, 6.5",
            "CDB PRE 13.5%, PRE, FIXED, 13.5, , FIXED, ",
            "CDB PRE sem tipo de taxa, PRE, , 13.5, , FIXED, ",
            "CDB CDI com taxa anual legada, CDI, , 0.15, , , ",
            "Tesouro SELIC com taxa anual legada, SELIC, , 0.15, , , ",
            "Tesouro IPCA com taxa anual legada, IPCA, , 0.15, , , ",
            "CDB CDI com taxa anual legada zero, CDI, , 0, , , ",
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
            "PRE, FIXED, , , sao obrigatorios para ativos de renda fixa",
            "PRE, FIXED, -1, , taxa anual nao pode ser negativa",
            "CDI, PERCENTAGE_OF_INDEX, , , sao obrigatorios para ativos de renda fixa",
            "CDI, PERCENTAGE_OF_INDEX, -1, , taxa anual nao pode ser negativa",
            "CDI, PERCENTAGE_OF_INDEX, 0, , taxa anual deve ser maior que zero",
            "CDI, PERCENTAGE_OF_INDEX, 110, 6.5, Percentual do indexador nao pode ser combinado com spread",
            "SELIC, PERCENTAGE_OF_INDEX, 110, -0.15, Percentual do indexador nao pode ser combinado com spread",
            "IPCA, SPREAD, , -1, spread e obrigatorio e nao pode ser negativo",
            "IPCA, SPREAD, , , spread e obrigatorio e nao pode ser negativo",
            "IPCA, SPREAD, 6.5, 6.5, Informe apenas spreadRate",
            "IPCA, SPREAD, 7, 6.5, Informe apenas spreadRate",
            "IPCA, SPREAD, 6.5, , Informe apenas spreadRate",
            "CDI, , , 0.15, Informe rateType",
            "SELIC, , 0.15, 0, Informe rateType",
            "IPCA, , 6.5, 6.5, Informe rateType"
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

    @ParameterizedTest
    @CsvSource({
            "ACOES, CDI, , , , , ",
            "ACOES, , 0, , , , ",
            "ACOES, , , Emissor, , , ",
            "ACOES, , , , 2030-01-01, , ",
            "ACOES, , , , , FIXED, ",
            "ACOES, , , , , , 0",
            "FII, CDI, , , , , ",
            "FII, , 0, , , , ",
            "FII, , , Emissor, , , ",
            "FII, , , , 2030-01-01, , ",
            "FII, , , , , FIXED, ",
            "FII, , , , , , 0"
    })
    void createAssetRejectsFixedIncomeFieldsForOtherCategories(AssetCategory category, FixedIncomeIndexer indexer,
                                                               BigDecimal annualRate, String issuer,
                                                               LocalDate expirationDate, FixedIncomeRateType rateType,
                                                               BigDecimal spreadRate) {
        AssetRequestDTO request = new AssetRequestDTO("ATIVO1", "Ativo", category, indexer, annualRate, issuer,
                expirationDate, rateType, spreadRate);

        assertThatThrownBy(() -> assetService.createAsset(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Campos de renda fixa so podem ser informados para ativos da categoria RENDA_FIXA.");
        verifyNoInteractions(assetRepository, assetMapper);
    }
}
