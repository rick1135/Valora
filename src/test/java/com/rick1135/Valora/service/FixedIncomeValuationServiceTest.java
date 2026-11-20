package com.rick1135.Valora.service;

import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkValue;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.FixedIncomeRateType;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.repository.BenchmarkRepository;
import com.rick1135.Valora.repository.BenchmarkValueRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FixedIncomeValuationServiceTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private BenchmarkRepository benchmarkRepository;
    @Mock
    private BenchmarkValueRepository benchmarkValueRepository;

    private MarketCalendar marketCalendar;
    private FixedIncomeYieldCalculator yieldCalculator;
    private FixedIncomeValuationService valuationService;

    @BeforeEach
    void setUp() {
        marketCalendar = new MarketCalendar(Clock.fixed(Instant.parse("2026-01-01T15:00:00Z"), ZoneOffset.UTC));
        yieldCalculator = new FixedIncomeYieldCalculator(marketCalendar);
        valuationService = new FixedIncomeValuationService(yieldCalculator, marketCalendar, transactionRepository);
    }

    @Test
    void valuatePositionShouldEvaluateMultipleLotsSeparatelyByTransactionDate() {
        Asset asset = preAsset("CDB_PRE_2028", "10.00", LocalDate.of(2028, 1, 1));
        Position position = position(asset, "2", "1000.00", LocalDate.of(2025, 1, 1));

        // Lot 1: 2025-01-01 -> 365 days -> 1000 * 1.10 = 1100.00
        // Lot 2: 2026-01-01 -> 0 days -> 1000.00
        // Total must be 2100.00 (NOT 2200.00 from using position.purchaseDate for both lots)
        List<Transaction> txs = List.of(
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2025-01-01T15:00:00Z")),
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2026-01-01T15:00:00Z"))
        );

        FixedIncomeValuationService.FixedIncomeValuationResult result = valuationService.valuatePosition(position, txs);

        assertThat(result.reliable()).isTrue();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("2100.00000000");
        assertThat(result.currentUnitPrice()).isEqualByComparingTo("1050.00000000");
        assertThat(result.valuedThrough()).isNull();
        assertThat(result.pendingLatestObservation()).isFalse();
    }

    @Test
    void valuatePositionShouldConsumeOldestLotsFifoOnPartialSell() {
        Asset asset = preAsset("CDB_FIFO", "10.00", LocalDate.of(2028, 1, 1));
        Position position = position(asset, "1", "1000.00", LocalDate.of(2025, 1, 1));

        // Buy 1 @ 1000 on 2025-01-01, Buy 1 @ 1000 on 2026-01-01, Sell 1 on 2026-01-01
        // FIFO consumes the 2025-01-01 lot, leaving only the 2026-01-01 lot (0 days -> 1000.00)
        List<Transaction> txs = List.of(
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2025-01-01T15:00:00Z")),
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2026-01-01T15:00:00Z")),
                tx(asset, TransactionType.SELL, "1", "1050.00", Instant.parse("2026-01-01T16:00:00Z"))
        );

        FixedIncomeValuationService.FixedIncomeValuationResult result = valuationService.valuatePosition(position, txs);

        assertThat(result.reliable()).isTrue();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("1000.00000000");
        assertThat(result.currentUnitPrice()).isEqualByComparingTo("1000.00000000");
    }

    @Test
    void valuatePositionShouldCapAtExpirationDateAcrossLots() {
        // Today is 2026-01-01, expirationDate is 2025-07-02 (182 days from 2025-01-01)
        LocalDate expiration = LocalDate.of(2025, 7, 2);
        Asset asset = preAsset("CDB_VENCIDO", "10.00", expiration);
        Position position = position(asset, "1", "1000.00", LocalDate.of(2025, 1, 1));

        List<Transaction> txs = List.of(
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2025-01-01T15:00:00Z"))
        );

        BigDecimal expectedAtExpiration = yieldCalculator.calculateCurrentValue(
                new BigDecimal("1000.00"),
                BigDecimal.ONE,
                LocalDate.of(2025, 1, 1),
                new BigDecimal("10.00"),
                expiration
        );

        FixedIncomeValuationService.FixedIncomeValuationResult result = valuationService.valuatePosition(position, txs);

        assertThat(result.reliable()).isTrue();
        assertThat(result.currentTotalValue()).isEqualByComparingTo(expectedAtExpiration);
    }

    @Test
    void valuatePositionShouldMarkPostFixedIndexersUnreliableWithoutRateType() {
        for (FixedIncomeIndexer indexer : List.of(FixedIncomeIndexer.CDI, FixedIncomeIndexer.IPCA, FixedIncomeIndexer.SELIC)) {
            Asset asset = preAsset("RF_" + indexer.name(), "110.00", LocalDate.of(2028, 1, 1));
            asset.setIndexer(indexer);
            asset.setRateType(null);
            Position position = position(asset, "2", "1000.00", LocalDate.of(2025, 1, 1));

            FixedIncomeValuationService.FixedIncomeValuationResult result =
                    valuationServiceWithBenchmarks().valuatePosition(position, List.of());

            assertThat(result.reliable()).isFalse();
            assertThat(result.currentTotalValue()).isEqualByComparingTo("2000.00000000");
            assertThat(result.currentUnitPrice()).isEqualByComparingTo("1000.00000000");
        }
        verifyNoInteractions(benchmarkRepository, benchmarkValueRepository);
    }

    @Test
    void valuatePositionsShouldBatchCdiAcrossPositionsAndApplyPartialSaleFifo() {
        LocalDate firstOpenDay = LocalDate.of(2025, 12, 15);
        LocalDate secondOpenDay = firstOpenDay.plusDays(1);
        LocalDate expiration = secondOpenDay.plusDays(1);
        Asset cdb110 = postFixedAsset(FixedIncomeIndexer.CDI, FixedIncomeRateType.PERCENTAGE_OF_INDEX, "110", expiration);
        Asset cdb100 = postFixedAsset(FixedIncomeIndexer.CDI, FixedIncomeRateType.PERCENTAGE_OF_INDEX, "100", expiration);
        Position multipleLots = position(cdb110, "2", "950.00", firstOpenDay.minusWeeks(1));
        Position singleLot = position(cdb100, "1", "1000.00", secondOpenDay);
        List<Transaction> transactions = List.of(
                tx(cdb110, TransactionType.BUY, "1", "800.00", Instant.parse("2025-12-08T15:00:00Z")),
                tx(cdb110, TransactionType.BUY, "2", "1000.00", Instant.parse("2025-12-15T15:00:00Z")),
                tx(cdb110, TransactionType.BUY, "1", "900.00", Instant.parse("2025-12-16T15:00:00Z")),
                tx(cdb110, TransactionType.SELL, "2", "1100.00", Instant.parse("2025-12-16T16:00:00Z"))
        );
        Benchmark cdi = benchmark(FixedIncomeIndexer.CDI);
        when(benchmarkValueRepository.findByBenchmarkInRange(cdi, firstOpenDay, secondOpenDay))
                .thenReturn(List.of(rate(cdi, firstOpenDay, "0.055"), rate(cdi, secondOpenDay, "0.055")));

        var results = valuationServiceWithBenchmarks().valuatePositions(List.of(multipleLots, singleLot), transactions);

        assertThat(results.get(multipleLots).reliable()).isTrue();
        assertThat(results.get(multipleLots).currentTotalValue()).isEqualByComparingTo("1901.75486603");
        assertThat(results.get(multipleLots).currentUnitPrice()).isEqualByComparingTo("950.87743302");
        assertThat(results.get(multipleLots).valuedThrough()).isEqualTo(secondOpenDay);
        assertThat(results.get(multipleLots).pendingLatestObservation()).isFalse();
        assertThat(results.get(singleLot).reliable()).isTrue();
        assertThat(results.get(singleLot).currentTotalValue()).isEqualByComparingTo("1000.55000000");
        assertThat(results.get(singleLot).valuedThrough()).isEqualTo(secondOpenDay);
        assertThat(results.get(singleLot).pendingLatestObservation()).isFalse();
        verify(benchmarkRepository).findByTicker("CDI");
        verify(benchmarkValueRepository).findByBenchmarkInRange(cdi, firstOpenDay, secondOpenDay);
        verifyNoMoreInteractions(benchmarkRepository, benchmarkValueRepository);
    }

    @Test
    void valuatePositionShouldApplyTreasurySelicAndSpread() {
        LocalDate start = LocalDate.of(2025, 12, 15);
        LocalDate end = start.plusDays(1);
        Asset asset = postFixedAsset(FixedIncomeIndexer.SELIC, FixedIncomeRateType.SPREAD, "0.15", end.plusDays(1));
        Benchmark selic = benchmark(FixedIncomeIndexer.SELIC);
        when(benchmarkValueRepository.findByBenchmarkInRange(selic, start, end))
                .thenReturn(List.of(rate(selic, start, "0.055"), rate(selic, end, "0.055")));

        var result = valuationServiceWithBenchmarks().valuatePosition(position(asset, "1", "1000.00", start), List.of());

        assertThat(result.reliable()).isTrue();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("1001.11221150");
        assertThat(result.valuedThrough()).isEqualTo(end);
    }

    @ParameterizedTest
    @EnumSource(value = FixedIncomeIndexer.class, names = {"CDI", "SELIC"})
    void valuatePositionShouldReportCoverageAndPendingD1WhenAnyOpenLotIsPending(FixedIncomeIndexer indexer) {
        LocalDate lastCoveredDate = LocalDate.of(2025, 4, 16);
        var calendar = new MarketCalendar(Clock.fixed(Instant.parse("2025-04-22T15:00:00Z"), ZoneOffset.UTC));
        var service = new FixedIncomeValuationService(new FixedIncomeYieldCalculator(calendar), calendar,
                transactionRepository, new PostFixedIncomeYieldCalculator(calendar), benchmarkRepository, benchmarkValueRepository);
        Asset asset = postFixedAsset(indexer, FixedIncomeRateType.PERCENTAGE_OF_INDEX, "100", LocalDate.of(2026, 1, 1));
        Position position = position(asset, "2", "1000.00", lastCoveredDate);
        List<Transaction> transactions = List.of(
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2025-04-16T15:00:00Z")),
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2025-04-22T15:00:00Z"))
        );
        Benchmark benchmark = benchmark(indexer);
        when(benchmarkValueRepository.findByBenchmarkInRange(benchmark, lastCoveredDate, calendar.today().minusDays(1)))
                .thenReturn(List.of(rate(benchmark, lastCoveredDate, "0.055")));

        var result = service.valuatePosition(position, transactions);

        assertThat(result.reliable()).isTrue();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("2000.55000000");
        assertThat(result.valuedThrough()).isEqualTo(lastCoveredDate);
        assertThat(result.pendingLatestObservation()).isTrue();
    }

    @Test
    void valuatePositionShouldFallBackForIpcaWithoutQueryingBenchmarks() {
        LocalDate start = LocalDate.of(2025, 11, 17);
        LocalDate expiration = LocalDate.of(2025, 12, 1);
        Asset asset = postFixedAsset(FixedIncomeIndexer.IPCA, FixedIncomeRateType.SPREAD, "6.5", expiration);

        var result = valuationServiceWithBenchmarks().valuatePosition(position(asset, "2", "1000.00", start), List.of());

        assertThat(result.reliable()).isFalse();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("2000.00000000");
        assertThat(result.currentUnitPrice()).isEqualByComparingTo("1000.00000000");
        assertThat(result.valuedThrough()).isNull();
        assertThat(result.pendingLatestObservation()).isFalse();
        verifyNoInteractions(benchmarkRepository, benchmarkValueRepository);
    }

    @Test
    void valuatePositionShouldFallBackForAllLotsWhenOneLacksBenchmarkCoverage() {
        LocalDate firstDay = LocalDate.of(2025, 12, 15);
        LocalDate secondDay = firstDay.plusDays(1);
        Asset asset = postFixedAsset(FixedIncomeIndexer.CDI, FixedIncomeRateType.PERCENTAGE_OF_INDEX, "110", secondDay.plusDays(1));
        Position position = position(asset, "2", "950.00", firstDay);
        List<Transaction> transactions = List.of(
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2025-12-15T15:00:00Z")),
                tx(asset, TransactionType.BUY, "1", "900.00", Instant.parse("2025-12-16T15:00:00Z"))
        );
        Benchmark cdi = benchmark(FixedIncomeIndexer.CDI);
        when(benchmarkValueRepository.findByBenchmarkInRange(cdi, firstDay, secondDay))
                .thenReturn(List.of(rate(cdi, secondDay, "0.055")));

        var result = valuationServiceWithBenchmarks().valuatePosition(position, transactions);

        assertThat(result.reliable()).isFalse();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("1900.00000000");
        assertThat(result.currentUnitPrice()).isEqualByComparingTo("950.00000000");
        assertThat(result.valuedThrough()).isNull();
    }

    @Test
    void valuatePositionShouldFallBackWhenBenchmarkIsAbsent() {
        LocalDate start = LocalDate.of(2025, 12, 15);
        Asset asset = postFixedAsset(FixedIncomeIndexer.CDI, FixedIncomeRateType.PERCENTAGE_OF_INDEX, "110", start.plusDays(2));
        when(benchmarkRepository.findByTicker("CDI")).thenReturn(Optional.empty());

        var result = valuationServiceWithBenchmarks().valuatePosition(position(asset, "1", "1000.00", start), List.of());

        assertThat(result.reliable()).isFalse();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("1000.00000000");
        verifyNoInteractions(benchmarkValueRepository);
    }

    @Test
    void valuatePositionShouldMarkLegacyPositionWithoutPurchaseDateUnreliable() {
        Asset asset = preAsset("CDB_LEGACY", "10.00", LocalDate.of(2028, 1, 1));
        Position legacyPosition = position(asset, "2", "1000.00", null);

        FixedIncomeValuationService.FixedIncomeValuationResult result = valuationService.valuatePosition(legacyPosition, List.of());

        assertThat(result.reliable()).isFalse();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("2000.00000000");
        assertThat(result.currentUnitPrice()).isEqualByComparingTo("1000.00000000");
    }

    @Test
    void valuatePositionShouldMarkInconsistentTransactionHistoryUnreliable() {
        Asset asset = preAsset("CDB_MISMATCH", "10.00", LocalDate.of(2028, 1, 1));
        Position position = position(asset, "5", "1000.00", LocalDate.of(2025, 1, 1));

        // Transaction history only accounts for 1 unit, while position has 5
        List<Transaction> txs = List.of(
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2025-01-01T15:00:00Z"))
        );

        FixedIncomeValuationService.FixedIncomeValuationResult result = valuationService.valuatePosition(position, txs);

        assertThat(result.reliable()).isFalse();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("5000.00000000");
    }

    private Asset preAsset(String ticker, String annualRate, LocalDate expirationDate) {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker(ticker);
        asset.setName(ticker);
        asset.setCategory(AssetCategory.RENDA_FIXA);
        asset.setIndexer(FixedIncomeIndexer.PRE);
        asset.setAnnualRate(new BigDecimal(annualRate));
        asset.setExpirationDate(expirationDate);
        return asset;
    }

    private FixedIncomeValuationService valuationServiceWithBenchmarks() {
        return new FixedIncomeValuationService(yieldCalculator, marketCalendar, transactionRepository,
                new PostFixedIncomeYieldCalculator(marketCalendar), benchmarkRepository, benchmarkValueRepository);
    }

    private Asset postFixedAsset(FixedIncomeIndexer indexer, FixedIncomeRateType rateType, String rate, LocalDate expiration) {
        Asset asset = preAsset("RF_" + indexer, rate, expiration);
        asset.setIndexer(indexer);
        asset.setRateType(rateType);
        if (rateType == FixedIncomeRateType.SPREAD) {
            asset.setAnnualRate(null);
            asset.setSpreadRate(new BigDecimal(rate));
        }
        return asset;
    }

    private Benchmark benchmark(FixedIncomeIndexer indexer) {
        Benchmark benchmark = new Benchmark();
        benchmark.setTicker(indexer.name());
        when(benchmarkRepository.findByTicker(indexer.name())).thenReturn(Optional.of(benchmark));
        return benchmark;
    }

    private BenchmarkValue rate(Benchmark benchmark, LocalDate date, String value) {
        return new BenchmarkValue(null, benchmark, date, new BigDecimal(value));
    }

    private Position position(Asset asset, String quantity, String averagePrice, LocalDate purchaseDate) {
        Position position = new Position();
        position.setId(UUID.randomUUID());
        position.setPortfolio(new Portfolio());
        position.setAsset(asset);
        position.setQuantity(new BigDecimal(quantity));
        position.setAveragePrice(new BigDecimal(averagePrice));
        position.setPurchaseDate(purchaseDate);
        return position;
    }

    private Transaction tx(Asset asset, TransactionType type, String quantity, String unitPrice, Instant date) {
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());
        tx.setAsset(asset);
        tx.setType(type);
        tx.setQuantity(new BigDecimal(quantity));
        tx.setUnitPrice(new BigDecimal(unitPrice));
        tx.setTransactionDate(date);
        return tx;
    }
}
