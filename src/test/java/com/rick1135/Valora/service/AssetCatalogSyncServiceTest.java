package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiListedAssetDTO;
import com.rick1135.Valora.dto.brapi.BrapiQuoteListResponseDTO;
import com.rick1135.Valora.dto.response.AssetCatalogSyncResultDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.repository.AssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.client.RestClientException;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetCatalogSyncServiceTest {
    @Mock
    private AssetRepository assetRepository;

    @Mock
    private BrapiClient brapiClient;

    @Test
    void syncTraversesPagesFiltersTickersAndIsIdempotent() {
        Asset legacyEtf = new Asset();
        legacyEtf.setId(UUID.randomUUID());
        legacyEtf.setTicker("BOVV11");
        legacyEtf.setTickerNormalized("BOVV11");
        legacyEtf.setName("Nome persistido");
        legacyEtf.setCategory(AssetCategory.FII);
        Map<String, Asset> storedAssets = new HashMap<>(Map.of("BOVV11", legacyEtf));
        when(assetRepository.findByOwnerUserIsNullAndTickerNormalizedIn(anyCollection())).thenAnswer(invocation -> {
            Collection<String> tickers = invocation.getArgument(0);
            return tickers.stream().filter(storedAssets::containsKey).map(storedAssets::get).toList();
        });
        when(brapiClient.listQuotes(null, 100, 1, "name", "asc", "token")).thenReturn(page(1, true,
                new BrapiListedAssetDTO(" itsa4 ", " Itausa ", "stock"),
                new BrapiListedAssetDTO("itsa4", "Duplicado", "stock"),
                new BrapiListedAssetDTO("ITSA4F", "Itausa fracionario", "stock"),
                new BrapiListedAssetDTO("BOVV11", "BOVV11", "fund", "etf")));
        when(brapiClient.listQuotes(null, 100, 2, "name", "asc", "token")).thenReturn(page(2, false,
                new BrapiListedAssetDTO("TAEE11", "TAEE11", "fund", "unit"),
                new BrapiListedAssetDTO("SANB11", "SANB11", "fund", "UNIT"),
                new BrapiListedAssetDTO("KLBN11", "KLBN11", "fund", "unit"),
                new BrapiListedAssetDTO("ABCD3", " ", "stock")));

        when(assetRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<Asset> batch = invocation.getArgument(0);
            for (Asset asset : batch) {
                asset.setTickerNormalized(asset.getTicker());
                storedAssets.put(asset.getTicker(), asset);
            }
            return batch;
        });
        var batches = ArgumentCaptor.<List<Asset>>captor();
        AssetCatalogSyncService service = service("token");

        assertThat(service.syncB3Catalog()).isEqualTo(new AssetCatalogSyncResultDTO(8, 5, 3, true, "COMPLETED"));
        assertThat(service.syncB3Catalog()).isEqualTo(new AssetCatalogSyncResultDTO(8, 0, 8, true, "COMPLETED"));

        verify(assetRepository, times(3)).saveAll(batches.capture());
        assertThat(batches.getAllValues().getFirst()).containsExactly(legacyEtf);
        assertThat(legacyEtf.getCategory()).isEqualTo(AssetCategory.ETF);
        assertThat(legacyEtf.getName()).isEqualTo("Nome persistido");
        assertThat(batches.getAllValues().get(1))
                .extracting(Asset::getTicker, Asset::getName, Asset::getCategory, Asset::getOwnerUser)
                .containsExactly(tuple("ITSA4", "Itausa", AssetCategory.ACOES, null));
        assertThat(batches.getAllValues().getLast())
                .extracting(Asset::getTicker, Asset::getName, Asset::getCategory, Asset::getOwnerUser)
                .containsExactly(tuple("TAEE11", "TAEE11", AssetCategory.ACOES, null),
                        tuple("SANB11", "SANB11", AssetCategory.ACOES, null),
                        tuple("KLBN11", "KLBN11", AssetCategory.ACOES, null),
                        tuple("ABCD3", "ABCD3", AssetCategory.ACOES, null));
        verify(brapiClient, times(2)).listQuotes(null, 100, 1, "name", "asc", "token");
        verify(brapiClient, times(2)).listQuotes(null, 100, 2, "name", "asc", "token");
        verifyNoMoreInteractions(brapiClient);
    }

    @Test
    void syncRetriesIndividuallyAfterDuplicateRollsBackBatch() {
        when(brapiClient.listQuotes(null, 100, 1, "name", "asc", "token")).thenReturn(page(1, false,
                new BrapiListedAssetDTO("ITSA4", "Itausa", "stock"),
                new BrapiListedAssetDTO("SPXB11", "S&P 500", "fund")));
        var duplicate = new DataIntegrityViolationException("duplicate", new SQLException("duplicate", "23505"));
        when(assetRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<Asset> batch = invocation.getArgument(0);
            batch.forEach(asset -> asset.setId(UUID.randomUUID()));
            throw duplicate;
        });
        when(assetRepository.saveAndFlush(any(Asset.class))).thenAnswer(invocation -> {
            Asset asset = invocation.getArgument(0);
            assertThat(asset.getId()).isNull();
            if ("ITSA4".equals(asset.getTicker())) {
                throw duplicate;
            }
            return asset;
        });

        assertThat(service("token").syncB3Catalog()).isEqualTo(new AssetCatalogSyncResultDTO(2, 1, 1));
    }

    @Test
    void syncPreservesCountsWhenAnIntermediatePageFails() {
        when(brapiClient.listQuotes(null, 100, 1, "name", "asc", "token")).thenReturn(page(1, true,
                new BrapiListedAssetDTO("BOVV11", "BOVV11", "fund", "etf")));
        when(brapiClient.listQuotes(null, 100, 2, "name", "asc", "token"))
                .thenThrow(new RestClientException("upstream unavailable"));
        var batch = ArgumentCaptor.<List<Asset>>captor();

        assertThat(service("token").syncB3Catalog())
                .isEqualTo(new AssetCatalogSyncResultDTO(1, 1, 0, false, "PARTIAL"));
        verify(assetRepository).saveAll(batch.capture());
        assertThat(batch.getValue()).extracting(Asset::getCategory).containsExactly(AssetCategory.ETF);
        verifyNoMoreInteractions(brapiClient);
    }

    @Test
    void syncReportsPartialAtPageLimitEvenWhenPagesAreEmpty() {
        when(brapiClient.listQuotes(isNull(), eq(100), anyInt(), eq("name"), eq("asc"), eq("token")))
                .thenAnswer(invocation -> page(invocation.getArgument(2), true));

        assertThat(service("token").syncB3Catalog())
                .isEqualTo(new AssetCatalogSyncResultDTO(0, 0, 0, false, "PARTIAL"));
        verify(brapiClient, times(50)).listQuotes(isNull(), eq(100), anyInt(), eq("name"), eq("asc"), eq("token"));
        verify(brapiClient).listQuotes(null, 100, 50, "name", "asc", "token");
    }

    @Test
    void syncReportsAlreadyRunningWhileAnotherSyncWaitsForBrapi() throws Exception {
        var requested = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(brapiClient.listQuotes(null, 100, 1, "name", "asc", "token")).thenAnswer(invocation -> {
            requested.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return page(1, false);
        });
        var service = service("token");
        var running = new FutureTask<>(service::syncB3Catalog);
        Thread.ofVirtual().start(running);

        try {
            assertThat(requested.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.syncB3Catalog())
                    .isEqualTo(new AssetCatalogSyncResultDTO(0, 0, 0, false, "ALREADY_RUNNING"));
        } finally {
            release.countDown();
        }
        assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(new AssetCatalogSyncResultDTO(0, 0, 0));
        verify(brapiClient).listQuotes(null, 100, 1, "name", "asc", "token");
    }

    @Test
    void startupSyncRunsInBackgroundWhenGlobalCatalogIsSmall() {
        when(assetRepository.countByOwnerUserIsNull()).thenReturn(49L);
        when(brapiClient.listQuotes(null, 100, 1, "name", "asc", "token")).thenReturn(page(1, false));

        service("token").onApplicationReady();

        verify(brapiClient, timeout(3000)).listQuotes(null, 100, 1, "name", "asc", "token");
    }

    @ParameterizedTest
    @ValueSource(longs = {50L, 51L})
    void startupSyncSkipsCatalogAtOrAboveThreshold(long count) {
        when(assetRepository.countByOwnerUserIsNull()).thenReturn(count);

        service("token").onApplicationReady();

        verify(assetRepository).countByOwnerUserIsNull();
        verify(assetRepository, after(100).never()).findByOwnerUserIsNullAndTickerNormalizedIn(anyCollection());
        verifyNoInteractions(brapiClient);
    }

    @Test
    void syncWithoutTokenSkipsExternalAndRepositoryCalls() {
        assertThat(service(" ").syncB3Catalog())
                .isEqualTo(new AssetCatalogSyncResultDTO(0, 0, 0, false, "SKIPPED_NO_TOKEN"));
        var withoutClient = new AssetCatalogSyncService(assetRepository, null, null, "token", true, true, 50);
        assertThat(withoutClient.syncB3Catalog())
                .isEqualTo(new AssetCatalogSyncResultDTO(0, 0, 0, false, "SKIPPED_NO_TOKEN"));
        verifyNoInteractions(assetRepository, brapiClient);
    }

    @Test
    void syncSkipsUnsupportedFundSubTypesInsteadOfSavingAsAcoes() {
        when(brapiClient.listQuotes(null, 100, 1, "name", "asc", "token")).thenReturn(page(1, false,
                new BrapiListedAssetDTO("JURO11", "Fundo Infra", "fund", "fi-infra"),
                new BrapiListedAssetDTO("KNCA11", "Fiagro", "fund", "fi-agro"),
                new BrapiListedAssetDTO("FIPX11", "FIP", "fund", "fip"),
                new BrapiListedAssetDTO("FIDC11", "FIDC", "fund", "fidc"),
                new BrapiListedAssetDTO("MYST11", "Outro", "fund", "other-fund"),
                new BrapiListedAssetDTO("BOVV11", "BOVV11", "fund", "etf"),
                new BrapiListedAssetDTO("TAEE11", "Taesa", "stock", "unit")
        ));
        when(assetRepository.findByOwnerUserIsNullAndTickerNormalizedIn(anyCollection())).thenReturn(List.of());
        when(assetRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AssetCatalogSyncResultDTO result = service("token").syncB3Catalog();

        assertThat(result).isEqualTo(new AssetCatalogSyncResultDTO(7, 2, 5, true, "COMPLETED"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Asset>> saved = ArgumentCaptor.forClass(List.class);
        verify(assetRepository).saveAll(saved.capture());
        assertThat(saved.getValue())
                .extracting(Asset::getTicker, Asset::getCategory)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("BOVV11", AssetCategory.ETF),
                        org.assertj.core.groups.Tuple.tuple("TAEE11", AssetCategory.ACOES)
                );
    }

    @Test
    void syncMarksPartialWhenPagesOverlapOrTotalPagesMismatch() {
        when(brapiClient.listQuotes(null, 100, 1, "name", "asc", "token")).thenReturn(
                new BrapiQuoteListResponseDTO(List.of(
                        new BrapiListedAssetDTO("ITSA4", "Itausa", "stock", "stock")
                ), 1, 2, true));
        when(brapiClient.listQuotes(null, 100, 2, "name", "asc", "token")).thenReturn(
                new BrapiQuoteListResponseDTO(List.of(
                        new BrapiListedAssetDTO("ITSA4", "Itausa", "stock", "stock")
                ), 2, 2, false));
        when(assetRepository.findByOwnerUserIsNullAndTickerNormalizedIn(anyCollection())).thenReturn(List.of());
        when(assetRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AssetCatalogSyncResultDTO result = service("token").syncB3Catalog();

        assertThat(result.complete()).isFalse();
        assertThat(result.status()).isEqualTo("PARTIAL");
    }

    private AssetCatalogSyncService service(String token) {
        return new AssetCatalogSyncService(assetRepository, brapiClient, new AssetService(assetRepository, null),
                token, true, true, 50);
    }

    private BrapiQuoteListResponseDTO page(int page, boolean hasNextPage, BrapiListedAssetDTO... stocks) {
        return new BrapiQuoteListResponseDTO(List.of(stocks), page, hasNextPage ? page + 1 : page, hasNextPage);
    }
}
