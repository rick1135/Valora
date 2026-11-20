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

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
        Set<String> storedTickers = new HashSet<>(Set.of("PETR4"));
        when(assetRepository.findAllGlobalTickers()).thenReturn(storedTickers);
        when(brapiClient.listQuotes(null, 100, 1, "token")).thenReturn(page(1, true,
                new BrapiListedAssetDTO(" itsa4 ", " Itausa ", "stock"),
                new BrapiListedAssetDTO("ITSA4F", "Itausa fracionario", "stock"),
                new BrapiListedAssetDTO("PETR4", "Petrobras", "stock")));
        when(brapiClient.listQuotes(null, 100, 2, "token")).thenReturn(page(2, false,
                new BrapiListedAssetDTO("SPXB11", "S&P 500", "fund"),
                new BrapiListedAssetDTO("itsa4", "Duplicado", "stock"),
                new BrapiListedAssetDTO("ABCD3", " ", "stock")));

        when(assetRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<Asset> batch = invocation.getArgument(0);
            for (Asset asset : batch) {
                assertThat(storedTickers.add(asset.getTicker())).isTrue();
            }
            return batch;
        });
        var batches = ArgumentCaptor.<List<Asset>>captor();
        AssetCatalogSyncService service = service("token");

        assertThat(service.syncB3Catalog()).isEqualTo(new AssetCatalogSyncResultDTO(6, 3, 3));
        assertThat(service.syncB3Catalog()).isEqualTo(new AssetCatalogSyncResultDTO(6, 0, 6));

        verify(assetRepository, times(2)).saveAll(batches.capture());
        assertThat(batches.getAllValues().getFirst())
                .extracting(Asset::getTicker, Asset::getName, Asset::getCategory, Asset::getOwnerUser)
                .containsExactly(tuple("ITSA4", "Itausa", AssetCategory.ACOES, null));
        assertThat(batches.getAllValues().getLast())
                .extracting(Asset::getTicker, Asset::getName, Asset::getCategory, Asset::getOwnerUser)
                .containsExactly(tuple("SPXB11", "S&P 500", AssetCategory.ETF, null),
                        tuple("ABCD3", "ABCD3", AssetCategory.ACOES, null));
        verify(brapiClient, times(2)).listQuotes(null, 100, 1, "token");
        verify(brapiClient, times(2)).listQuotes(null, 100, 2, "token");
        verifyNoMoreInteractions(brapiClient);
    }

    @Test
    void syncRetriesIndividuallyAfterDuplicateRollsBackBatch() {
        when(assetRepository.findAllGlobalTickers()).thenReturn(Set.of());
        when(brapiClient.listQuotes(null, 100, 1, "token")).thenReturn(page(1, false,
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
    void startupSyncRunsInBackgroundWhenGlobalCatalogIsSmall() {
        when(assetRepository.countByOwnerUserIsNull()).thenReturn(49L);
        when(assetRepository.findAllGlobalTickers()).thenReturn(Set.of());
        when(brapiClient.listQuotes(null, 100, 1, "token")).thenReturn(page(1, true));

        service("token").onApplicationReady();

        verify(brapiClient, timeout(3000)).listQuotes(null, 100, 1, "token");
    }

    @ParameterizedTest
    @ValueSource(longs = {50L, 51L})
    void startupSyncSkipsCatalogAtOrAboveThreshold(long count) {
        when(assetRepository.countByOwnerUserIsNull()).thenReturn(count);

        service("token").onApplicationReady();

        verify(assetRepository).countByOwnerUserIsNull();
        verify(assetRepository, after(100).never()).findAllGlobalTickers();
        verifyNoInteractions(brapiClient);
    }

    @Test
    void syncWithoutTokenSkipsExternalAndRepositoryCalls() {
        assertThat(service(" ").syncB3Catalog()).isEqualTo(new AssetCatalogSyncResultDTO(0, 0, 0));
        verifyNoInteractions(assetRepository, brapiClient);
    }

    private AssetCatalogSyncService service(String token) {
        return new AssetCatalogSyncService(assetRepository, brapiClient, new AssetService(assetRepository, null),
                token, true, true, 50);
    }

    private BrapiQuoteListResponseDTO page(int page, boolean hasNextPage, BrapiListedAssetDTO... stocks) {
        return new BrapiQuoteListResponseDTO(List.of(stocks), page, 2, hasNextPage);
    }
}
