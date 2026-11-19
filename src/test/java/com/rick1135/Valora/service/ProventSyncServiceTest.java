package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiCashDividendDTO;
import com.rick1135.Valora.dto.brapi.BrapiDividendsDataDTO;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import com.rick1135.Valora.dto.request.ProventRequestDTO;
import com.rick1135.Valora.dto.response.ProventSyncResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.ProventRateBasis;
import com.rick1135.Valora.entity.ProventType;
import com.rick1135.Valora.exception.ProventAlreadyExistsException;
import com.rick1135.Valora.repository.PositionRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProventSyncServiceTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private PositionRepository positionRepository;
    @Mock
    private BrapiClient brapiClient;
    @Mock
    private ProventService proventService;
    @Mock
    private Clock clock;
    @Mock
    private MarketCalendar marketCalendar;

    @InjectMocks
    private ProventSyncService proventSyncService;

    @Test
    void syncShouldQueryAssetsUsingTheConfiguredLookbackWindow() {
        Instant fixedNow = Instant.parse("2026-03-20T12:00:00Z");
        ReflectionTestUtils.setField(proventSyncService, "brapiToken", "token-ok");
        ReflectionTestUtils.setField(proventSyncService, "assetLookbackDays", 30L);
        when(clock.instant()).thenReturn(fixedNow);

        Asset recentAsset = new Asset();
        recentAsset.setId(UUID.randomUUID());
        recentAsset.setTicker("ITSA4");
        recentAsset.setCategory(AssetCategory.ACOES);

        when(transactionRepository.findDistinctAssetsWithTransactionsSince(Instant.parse("2026-02-18T12:00:00Z")))
                .thenReturn(List.of(recentAsset));
        when(positionRepository.findDistinctAssetsByQuantityGreaterThan(BigDecimal.ZERO))
                .thenReturn(List.of());
        when(brapiClient.getQuoteWithDividends("ITSA4", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of()));

        ProventSyncResponseDTO response = proventSyncService.syncFromCurrentPortfolioAssets();

        assertThat(response.assetsScanned()).isEqualTo(1);
        verify(transactionRepository).findDistinctAssetsWithTransactionsSince(Instant.parse("2026-02-18T12:00:00Z"));
        verify(positionRepository).findDistinctAssetsByQuantityGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void syncShouldCreateAndIgnoreDuplicatesUsingRecentAssets() {
        ReflectionTestUtils.setField(proventSyncService, "brapiToken", "token-ok");
        when(clock.instant()).thenReturn(Instant.parse("2026-03-20T12:00:00Z"));

        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("ITSA4");
        asset.setName("Itausa");
        asset.setCategory(AssetCategory.ACOES);

        when(transactionRepository.findDistinctAssetsWithTransactionsSince(any(Instant.class)))
                .thenReturn(List.of(asset));
        when(positionRepository.findDistinctAssetsByQuantityGreaterThan(BigDecimal.ZERO))
                .thenReturn(List.of());

        BrapiCashDividendDTO validDividend = new BrapiCashDividendDTO(
                "ITSA4",
                Instant.parse("2026-04-10T00:00:00Z"),
                new BigDecimal("0.50000000"),
                "ON",
                Instant.parse("2026-03-25T00:00:00Z"),
                "BRITSAACNPR6",
                "DIVIDENDO",
                Instant.parse("2026-03-30T00:00:00Z"),
                null
        );
        BrapiCashDividendDTO validJcp = new BrapiCashDividendDTO(
                "ITSA4",
                Instant.parse("2026-04-20T00:00:00Z"),
                new BigDecimal("0.70000000"),
                "ON",
                Instant.parse("2026-03-26T00:00:00Z"),
                "BRITSAACNPR6",
                "JCP",
                Instant.parse("2026-03-31T00:00:00Z"),
                null
        );

        when(brapiClient.getQuoteWithDividends("ITSA4", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of(
                        new BrapiResultDTO("ITSA4", new BigDecimal("10.00"), new BrapiDividendsDataDTO(List.of(validDividend, validJcp)))
                )));
        when(marketCalendar.financialDate(Instant.parse("2026-03-30T00:00:00Z")))
                .thenReturn(LocalDate.of(2026, 3, 30));
        when(marketCalendar.financialDate(Instant.parse("2026-04-10T00:00:00Z")))
                .thenReturn(LocalDate.of(2026, 4, 10));
        when(marketCalendar.financialDate(Instant.parse("2026-03-31T00:00:00Z")))
                .thenReturn(LocalDate.of(2026, 3, 31));
        when(marketCalendar.financialDate(Instant.parse("2026-04-20T00:00:00Z")))
                .thenReturn(LocalDate.of(2026, 4, 20));

        lenient().doThrow(new ProventAlreadyExistsException("duplicate"))
                .when(proventService)
                .createProvent(argThat((ProventRequestDTO req) -> req.type().name().equals("JCP")), any(Asset.class), any(ProventOriginMetadata.class));

        ProventSyncResponseDTO response = proventSyncService.syncFromCurrentPortfolioAssets();

        assertThat(response.assetsScanned()).isEqualTo(1);
        assertThat(response.eventsReceived()).isEqualTo(2);
        assertThat(response.proventsCreated()).isEqualTo(1);
        assertThat(response.duplicatesIgnored()).isEqualTo(1);
        assertThat(response.invalidEventsIgnored()).isEqualTo(0);
        assertThat(response.integrationErrors()).isEqualTo(0);
        verify(proventService, times(2)).createProvent(any(ProventRequestDTO.class), any(Asset.class), any(ProventOriginMetadata.class));
        verify(proventService, times(1)).reconcileProvisionsForExistingProvent(any(ProventOriginMetadata.class));
    }

    @Test
    void syncShouldPreserveGrossJcpRateAndPassGrossOriginBasis() {
        ReflectionTestUtils.setField(proventSyncService, "brapiToken", "token-ok");
        ReflectionTestUtils.setField(proventSyncService, "jcpRateIsGross", true);
        when(clock.instant()).thenReturn(Instant.parse("2026-03-20T12:00:00Z"));

        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("BBAS3");
        asset.setCategory(AssetCategory.ACOES);

        when(transactionRepository.findDistinctAssetsWithTransactionsSince(any(Instant.class)))
                .thenReturn(List.of(asset));
        when(positionRepository.findDistinctAssetsByQuantityGreaterThan(BigDecimal.ZERO))
                .thenReturn(List.of());

        BrapiCashDividendDTO grossJcp = new BrapiCashDividendDTO(
                "BBAS3",
                Instant.parse("2026-04-20T00:00:00Z"),
                new BigDecimal("1.00000000"),
                "ON",
                Instant.parse("2026-03-26T00:00:00Z"),
                "BRBBASACNOR3",
                "JCP",
                Instant.parse("2026-03-31T00:00:00Z"),
                null
        );

        when(brapiClient.getQuoteWithDividends("BBAS3", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of(
                        new BrapiResultDTO("BBAS3", new BigDecimal("25.00"), new BrapiDividendsDataDTO(List.of(grossJcp)))
                )));
        when(marketCalendar.financialDate(Instant.parse("2026-03-31T00:00:00Z")))
                .thenReturn(LocalDate.of(2026, 3, 31));
        when(marketCalendar.financialDate(Instant.parse("2026-04-20T00:00:00Z")))
                .thenReturn(LocalDate.of(2026, 4, 20));

        ProventSyncResponseDTO response = proventSyncService.syncFromCurrentPortfolioAssets();

        assertThat(response.proventsCreated()).isEqualTo(1);

        ArgumentCaptor<ProventRequestDTO> requestCaptor = ArgumentCaptor.forClass(ProventRequestDTO.class);
        ArgumentCaptor<ProventOriginMetadata> metadataCaptor = ArgumentCaptor.forClass(ProventOriginMetadata.class);
        verify(proventService).createProvent(requestCaptor.capture(), eq(asset), metadataCaptor.capture());

        assertThat(requestCaptor.getValue().type()).isEqualTo(ProventType.JCP);
        assertThat(requestCaptor.getValue().amountPerShare()).isEqualByComparingTo("1.00000000");
        assertThat(metadataCaptor.getValue().sourceRateBasis()).isEqualTo(ProventRateBasis.GROSS);
    }

    @Test
    void syncShouldPreserveNetJcpRateAndPassNetOriginBasisAndBoundaryDatesWhenConfiguredAsNet() {
        ReflectionTestUtils.setField(proventSyncService, "brapiToken", "token-ok");
        ReflectionTestUtils.setField(proventSyncService, "jcpRateIsGross", false);
        when(clock.instant()).thenReturn(Instant.parse("2026-01-05T12:00:00Z"));

        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("BBAS3");
        asset.setCategory(AssetCategory.ACOES);

        when(transactionRepository.findDistinctAssetsWithTransactionsSince(any(Instant.class)))
                .thenReturn(List.of(asset));
        when(positionRepository.findDistinctAssetsByQuantityGreaterThan(BigDecimal.ZERO))
                .thenReturn(List.of());

        BrapiCashDividendDTO netJcp = new BrapiCashDividendDTO(
                "BBAS3",
                Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("0.82500000"),
                "ON",
                Instant.parse("2025-12-15T00:00:00Z"),
                "BRBBASACNOR3",
                "JCP",
                Instant.parse("2025-12-20T00:00:00Z"),
                null
        );

        when(brapiClient.getQuoteWithDividends("BBAS3", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of(
                        new BrapiResultDTO("BBAS3", new BigDecimal("25.00"), new BrapiDividendsDataDTO(List.of(netJcp)))
                )));
        when(marketCalendar.financialDate(Instant.parse("2025-12-20T00:00:00Z")))
                .thenReturn(LocalDate.of(2025, 12, 20));
        when(marketCalendar.financialDate(Instant.parse("2026-01-01T00:00:00Z")))
                .thenReturn(LocalDate.of(2026, 1, 1));

        ProventSyncResponseDTO response = proventSyncService.syncFromCurrentPortfolioAssets();

        assertThat(response.proventsCreated()).isEqualTo(1);

        ArgumentCaptor<ProventRequestDTO> requestCaptor = ArgumentCaptor.forClass(ProventRequestDTO.class);
        ArgumentCaptor<ProventOriginMetadata> metadataCaptor = ArgumentCaptor.forClass(ProventOriginMetadata.class);
        verify(proventService).createProvent(requestCaptor.capture(), eq(asset), metadataCaptor.capture());

        assertThat(requestCaptor.getValue().type()).isEqualTo(ProventType.JCP);
        assertThat(requestCaptor.getValue().amountPerShare()).isEqualByComparingTo("0.82500000");
        assertThat(requestCaptor.getValue().comDate()).isEqualTo(LocalDate.of(2025, 12, 20));
        assertThat(requestCaptor.getValue().paymentDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(metadataCaptor.getValue().sourceRateBasis()).isEqualTo(ProventRateBasis.NET);
    }

    @Test
    void syncShouldCountInvalidEventsSeparatelyFromIntegrationErrors() {
        ReflectionTestUtils.setField(proventSyncService, "brapiToken", "token-ok");
        when(clock.instant()).thenReturn(Instant.parse("2026-03-20T12:00:00Z"));

        Asset assetOne = new Asset();
        assetOne.setId(UUID.randomUUID());
        assetOne.setTicker("ITSA4");
        assetOne.setCategory(AssetCategory.ACOES);

        Asset assetTwo = new Asset();
        assetTwo.setId(UUID.randomUUID());
        assetTwo.setTicker("PETR4");
        assetTwo.setCategory(AssetCategory.ACOES);

        when(transactionRepository.findDistinctAssetsWithTransactionsSince(any(Instant.class)))
                .thenReturn(List.of(assetOne, assetTwo));
        when(positionRepository.findDistinctAssetsByQuantityGreaterThan(BigDecimal.ZERO))
                .thenReturn(List.of());

        BrapiCashDividendDTO invalidDividend = new BrapiCashDividendDTO(
                "ITSA4",
                Instant.parse("2026-04-10T00:00:00Z"),
                new BigDecimal("0.50000000"),
                "ON",
                Instant.parse("2026-03-25T00:00:00Z"),
                "BRITSAACNPR6",
                null,
                Instant.parse("2026-03-30T00:00:00Z"),
                null
        );

        when(brapiClient.getQuoteWithDividends("ITSA4", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of(
                        new BrapiResultDTO("ITSA4", new BigDecimal("10.00"), new BrapiDividendsDataDTO(List.of(invalidDividend)))
                )));
        when(brapiClient.getQuoteWithDividends("PETR4", "token-ok"))
                .thenThrow(new IllegalStateException("brapi down"));

        ProventSyncResponseDTO response = proventSyncService.syncFromCurrentPortfolioAssets();

        assertThat(response.assetsScanned()).isEqualTo(2);
        assertThat(response.eventsReceived()).isEqualTo(1);
        assertThat(response.proventsCreated()).isEqualTo(0);
        assertThat(response.duplicatesIgnored()).isEqualTo(0);
        assertThat(response.invalidEventsIgnored()).isEqualTo(1);
        assertThat(response.integrationErrors()).isEqualTo(1);
        verify(proventService, never()).createProvent(any(ProventRequestDTO.class), any(Asset.class), any(ProventOriginMetadata.class));
    }

    @Test
    void syncShouldIncludeRecentlyTradedAssetsEvenWithoutOpenPosition() {
        ReflectionTestUtils.setField(proventSyncService, "brapiToken", "token-ok");
        when(clock.instant()).thenReturn(Instant.parse("2026-03-20T12:00:00Z"));

        Asset recentlyTradedAsset = new Asset();
        recentlyTradedAsset.setId(UUID.randomUUID());
        recentlyTradedAsset.setTicker("WEGE3");
        recentlyTradedAsset.setCategory(AssetCategory.ACOES);

        when(transactionRepository.findDistinctAssetsWithTransactionsSince(any(Instant.class)))
                .thenReturn(List.of(recentlyTradedAsset));
        when(positionRepository.findDistinctAssetsByQuantityGreaterThan(BigDecimal.ZERO))
                .thenReturn(List.of());
        when(brapiClient.getQuoteWithDividends("WEGE3", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of()));

        ProventSyncResponseDTO response = proventSyncService.syncFromCurrentPortfolioAssets();

        assertThat(response.assetsScanned()).isEqualTo(1);
        verify(brapiClient).getQuoteWithDividends("WEGE3", "token-ok");
    }

    @Test
    void syncShouldSkipWhenTokenIsMissing() {
        ReflectionTestUtils.setField(proventSyncService, "brapiToken", "");

        ProventSyncResponseDTO response = proventSyncService.syncFromCurrentPortfolioAssets();

        assertThat(response.assetsScanned()).isZero();
        assertThat(response.integrationErrors()).isZero();
        verifyNoInteractions(transactionRepository, positionRepository, brapiClient, proventService, marketCalendar);
    }
}
