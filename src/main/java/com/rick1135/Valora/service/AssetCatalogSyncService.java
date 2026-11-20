package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiListedAssetDTO;
import com.rick1135.Valora.dto.response.AssetCatalogSyncResultDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.repository.AssetRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AssetCatalogSyncService {
    private static final Logger log = LoggerFactory.getLogger(AssetCatalogSyncService.class);
    private static final int MAX_PAGES = 50;
    private static final int PAGE_SIZE = 100;

    private final AssetRepository assetRepository;
    private final @Nullable BrapiClient brapiClient;
    private final AssetService assetService;
    private final String brapiToken;
    private final boolean syncEnabled;
    private final boolean startupEnabled;
    private final long minCatalogSize;
    private final AtomicBoolean syncing = new AtomicBoolean();

    public AssetCatalogSyncService(
            AssetRepository assetRepository,
            @Nullable BrapiClient brapiClient,
            AssetService assetService,
            @Value("${brapi.token:}") String brapiToken,
            @Value("${assets.catalog.sync.enabled:true}") boolean syncEnabled,
            @Value("${assets.catalog.sync.startup-enabled:true}") boolean startupEnabled,
            @Value("${assets.catalog.sync.min-catalog-size:50}") long minCatalogSize
    ) {
        this.assetRepository = assetRepository;
        this.brapiClient = brapiClient;
        this.assetService = assetService;
        this.brapiToken = brapiToken;
        this.syncEnabled = syncEnabled;
        this.startupEnabled = startupEnabled;
        this.minCatalogSize = minCatalogSize;
    }

    public AssetCatalogSyncResultDTO syncB3Catalog() {
        if (brapiClient == null || !StringUtils.hasText(brapiToken)) {
            log.warn("Sincronizacao do catalogo indisponivel sem cliente e token da BRAPI.");
            return new AssetCatalogSyncResultDTO(0, 0, 0);
        }
        if (!syncing.compareAndSet(false, true)) {
            return new AssetCatalogSyncResultDTO(0, 0, 0);
        }
        try {
            return syncPages(brapiClient);
        } finally {
            syncing.set(false);
        }
    }

    private AssetCatalogSyncResultDTO syncPages(BrapiClient client) {
        Set<String> existing = new HashSet<>(assetRepository.findAllGlobalTickers());
        int fetched = 0;
        int inserted = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            var response = client.listQuotes(null, PAGE_SIZE, page, brapiToken);
            if (response == null || response.stocks() == null) {
                throw new RestClientException("Resposta da BRAPI sem lista de ativos na pagina " + page + ".");
            }
            List<BrapiListedAssetDTO> stocks = response.stocks();
            if (stocks.isEmpty()) {
                break;
            }
            fetched += stocks.size();
            List<Asset> batch = new ArrayList<>();
            for (BrapiListedAssetDTO item : stocks) {
                if (item == null || !StringUtils.hasText(item.stock())) {
                    continue;
                }
                String ticker = AssetService.normalizeTicker(item.stock());
                if (assetService.isValidCatalogTicker(ticker) && existing.add(ticker)) {
                    String name = AssetService.catalogName(ticker, item.name());
                    Asset asset = new Asset();
                    asset.setTicker(ticker);
                    asset.setName(name);
                    asset.setCategory(assetService.inferCategoryForCatalog(ticker, name, item.type()));
                    batch.add(asset);
                }
            }
            inserted += saveBatch(batch);
            if (!Boolean.TRUE.equals(response.hasNextPage())) {
                break;
            }
            if (page == MAX_PAGES) {
                log.warn("Sincronizacao do catalogo atingiu o limite de {} paginas da BRAPI.", MAX_PAGES);
            }
        }
        return new AssetCatalogSyncResultDTO(fetched, inserted, fetched - inserted);
    }

    private int saveBatch(List<Asset> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            // The repository commits the batch before returning. After rollback, each retry gets its own transaction.
            assetRepository.saveAll(batch);
            return batch.size();
        } catch (DataIntegrityViolationException exception) {
            if (!AssetService.isUniqueViolation(exception)) {
                throw exception;
            }
        }

        int inserted = 0;
        for (Asset asset : batch) {
            // Hibernate can assign the ID even when the batch rolls back.
            asset.setId(null);
            try {
                assetRepository.saveAndFlush(asset);
                inserted++;
            } catch (DataIntegrityViolationException exception) {
                if (!AssetService.isUniqueViolation(exception)) {
                    throw exception;
                }
            }
        }
        return inserted;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (startupEnabled && brapiClient != null && StringUtils.hasText(brapiToken)
                && assetRepository.countByOwnerUserIsNull() < minCatalogSize) {
            CompletableFuture.runAsync(this::runSafeSync);
        }
    }

    @Scheduled(cron = "${assets.catalog.sync.cron:0 0 6 * * MON-FRI}", zone = "America/Sao_Paulo")
    public void scheduledSync() {
        if (syncEnabled) {
            runSafeSync();
        }
    }

    private void runSafeSync() {
        try {
            syncB3Catalog();
        } catch (RuntimeException exception) {
            log.warn("Falha ao sincronizar o catalogo de ativos da B3.", exception);
        }
    }
}
