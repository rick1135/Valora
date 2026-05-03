package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiCashDividendDTO;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import com.rick1135.Valora.dto.request.ProventRequestDTO;
import com.rick1135.Valora.dto.response.ProventSyncResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.ProventType;
import com.rick1135.Valora.exception.ProventAlreadyExistsException;
import com.rick1135.Valora.repository.PositionRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProventSyncService {
    private static final BigDecimal JCP_NET_FACTOR = new BigDecimal("0.85");

    private final TransactionRepository transactionRepository;
    private final PositionRepository positionRepository;
    private final BrapiClient brapiClient;
    private final ProventService proventService;
    private final Clock clock;

    @Value("${brapi.token:}")
    private String brapiToken;

    @Value("${provents.sync.asset-lookback-days:365}")
    private long assetLookbackDays = 365;

    @Value("${provents.sync.jcp-rate-is-gross:true}")
    private boolean jcpRateIsGross;

    @Scheduled(cron = "${provents.sync.cron:0 15 8 * * *}")
    public void scheduledSync() {
        ProventSyncResponseDTO response = syncFromCurrentPortfolioAssets();
        log.info("Provent sync finished: assetsScanned={}, eventsReceived={}, created={}, duplicatesIgnored={}, invalidIgnored={}, integrationErrors={}",
                response.assetsScanned(),
                response.eventsReceived(),
                response.proventsCreated(),
                response.duplicatesIgnored(),
                response.invalidEventsIgnored(),
                response.integrationErrors());
    }

    public ProventSyncResponseDTO syncFromCurrentPortfolioAssets() {
        if (brapiToken == null || brapiToken.isBlank()) {
            log.warn("Skipping provent sync because BRAPI token is not configured.");
            return new ProventSyncResponseDTO(0, 0, 0, 0, 0, 0);
        }

        List<Asset> assets = loadAssetsToSync();
        int assetsScanned = assets.size();
        int eventsReceived = 0;
        int proventsCreated = 0;
        int duplicatesIgnored = 0;
        int invalidEventsIgnored = 0;
        int integrationErrors = 0;

        for (Asset asset : assets) {
            List<BrapiCashDividendDTO> events;
            try {
                events = fetchCashDividendEvents(asset.getTicker());
            } catch (Exception exception) {
                integrationErrors++;
                log.error("Brapi integration failure while fetching dividend events for ticker={}: {}", asset.getTicker(), exception.getMessage(), exception);
                continue;
            }

            eventsReceived += events.size();

            for (BrapiCashDividendDTO event : events) {
                if (!isValidEvent(event)) {
                    invalidEventsIgnored++;
                    log.warn("Skipping invalid provent event for ticker={} label={} reason=missing-or-invalid-data",
                            asset.getTicker(),
                            event == null ? null : event.label());
                    continue;
                }

                ProventType type = mapType(event.label());
                BigDecimal amountPerShare = normalizeAmountPerShare(type, event.rate());
                boolean sourceRateIsGross = type == ProventType.JCP ? jcpRateIsGross : true;
                ProventRequestDTO request = new ProventRequestDTO(
                        asset.getId(),
                        type,
                        amountPerShare,
                        event.lastDatePrior(),
                        event.paymentDate()
                );

                ProventOriginMetadata originMetadata = ProventOriginMetadata.brapi(
                        asset.getTicker(),
                        type,
                        event,
                        sourceRateIsGross
                );

                try {
                    proventService.createProvent(request, asset, originMetadata);
                    proventsCreated++;
                } catch (ProventAlreadyExistsException duplicate) {
                    duplicatesIgnored++;
                } catch (IllegalArgumentException invalid) {
                    invalidEventsIgnored++;
                    log.warn("Skipping invalid provent event for ticker={} label={} reason={}",
                            asset.getTicker(),
                            event.label(),
                            invalid.getMessage());
                } catch (Exception exception) {
                    integrationErrors++;
                    log.error("Unexpected error while persisting provent for ticker={} label={}: {}",
                            asset.getTicker(),
                            event.label(),
                            exception.getMessage(),
                            exception);
                }
            }
        }

        return new ProventSyncResponseDTO(
                assetsScanned,
                eventsReceived,
                proventsCreated,
                duplicatesIgnored,
                invalidEventsIgnored,
                integrationErrors
        );
    }

    private List<Asset> loadAssetsToSync() {
        Instant cutoff = Instant.now(clock).minus(Math.max(assetLookbackDays, 1), ChronoUnit.DAYS);
        List<Asset> recentAssets = transactionRepository.findDistinctAssetsWithTransactionsSince(cutoff);
        List<Asset> openPositionAssets = positionRepository.findDistinctAssetsByQuantityGreaterThan(BigDecimal.ZERO);

        Map<UUID, Asset> assetsById = new LinkedHashMap<>();
        for (Asset asset : recentAssets) {
            assetsById.put(asset.getId(), asset);
        }
        for (Asset asset : openPositionAssets) {
            assetsById.putIfAbsent(asset.getId(), asset);
        }

        return new ArrayList<>(assetsById.values());
    }

    private List<BrapiCashDividendDTO> fetchCashDividendEvents(String ticker) {
        BrapiResponseDTO response = brapiClient.getQuoteWithDividends(ticker, brapiToken);
        if (response == null || response.results() == null || response.results().isEmpty()) {
            return Collections.emptyList();
        }

        BrapiResultDTO first = response.results().getFirst();
        if (first.dividendsData() == null || first.dividendsData().cashDividends() == null) {
            return Collections.emptyList();
        }

        return first.dividendsData().cashDividends();
    }

    private boolean isValidEvent(BrapiCashDividendDTO event) {
        return event != null
                && event.rate() != null
                && event.rate().compareTo(BigDecimal.ZERO) > 0
                && event.lastDatePrior() != null
                && event.paymentDate() != null
                && event.label() != null
                && !event.label().isBlank();
    }

    private ProventType mapType(String label) {
        String normalized = label.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("JCP")) {
            return ProventType.JCP;
        }
        return ProventType.DIVIDEND;
    }

    private BigDecimal normalizeAmountPerShare(ProventType type, BigDecimal originalRate) {
        if (type == ProventType.JCP && jcpRateIsGross) {
            return originalRate.multiply(JCP_NET_FACTOR).setScale(com.rick1135.Valora.common.FinancialConstants.EXTENDED_PRECISION_SCALE, com.rick1135.Valora.common.FinancialConstants.DEFAULT_ROUNDING);
        }
        return originalRate.setScale(com.rick1135.Valora.common.FinancialConstants.EXTENDED_PRECISION_SCALE, com.rick1135.Valora.common.FinancialConstants.DEFAULT_ROUNDING);
    }
}
