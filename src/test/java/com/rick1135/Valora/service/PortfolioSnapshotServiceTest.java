package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiHistoricalDataDTO;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import com.rick1135.Valora.dto.response.PortfolioHistoryResponseDTO.BenchmarkPointDTO;
import com.rick1135.Valora.dto.response.PortfolioHistoryResponseDTO.HistoryPointDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkType;
import com.rick1135.Valora.entity.BenchmarkValue;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.PortfolioDailySnapshot;
import com.rick1135.Valora.entity.Provent;
import com.rick1135.Valora.entity.ProventProvision;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.repository.BenchmarkRepository;
import com.rick1135.Valora.repository.BenchmarkValueRepository;
import com.rick1135.Valora.repository.PortfolioDailySnapshotRepository;
import com.rick1135.Valora.repository.PortfolioRepository;
import com.rick1135.Valora.repository.ProventProvisionRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioSnapshotServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 2, 20);

    @Mock private PortfolioRepository portfolios;
    @Mock private PortfolioDailySnapshotRepository snapshots;
    @Mock private TransactionRepository transactions;
    @Mock private ProventProvisionRepository provents;
    @Mock private BenchmarkRepository benchmarks;
    @Mock private BenchmarkValueRepository benchmarkValues;
    @Mock private BrapiClient brapi;
    @Mock private PlatformTransactionManager transactionManager;
    @Captor private ArgumentCaptor<List<PortfolioDailySnapshot>> savedSnapshots;

    private final User user = new User();
    private final Portfolio portfolio = new Portfolio();
    private final MarketCalendar calendar = new MarketCalendar(Clock.fixed(instant(TODAY), ZoneOffset.UTC));
    private PortfolioSnapshotService service;

    @BeforeEach
    void setUp() {
        portfolio.setId(UUID.randomUUID());
        portfolio.setUser(user);
        service = new PortfolioSnapshotService(portfolios, snapshots, transactions, provents, benchmarks,
                benchmarkValues, calendar, brapi, new PortfolioTwrCalculator(), transactionManager, "test-token");
    }

    @Test
    void returnsOneMonthAndRebasesItsFirstPoint() {
        LocalDate start = TODAY.minusMonths(1);
        PortfolioDailySnapshot first = snapshot(start, "1.10", true);
        PortfolioDailySnapshot last = snapshot(TODAY, "1.21", false);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(last));
        when(snapshots.findByPortfolioAndSnapshotDateBetweenOrderBySnapshotDateAsc(portfolio, start, TODAY))
                .thenReturn(List.of(first, last));

        var history = service.getPortfolioHistory(user, portfolio.getId(), " 1m ");

        assertThat(history.range()).isEqualTo("1M");
        assertThat(history.startDate()).isEqualTo(start);
        assertThat(history.endDate()).isEqualTo(TODAY);
        assertThat(history.points()).extracting(HistoryPointDTO::twrPercentage)
                .containsExactly(new BigDecimal("0.0000"), new BigDecimal("10.0000"));
        assertThat(history.twrPercentage()).isEqualTo(new BigDecimal("10.0000"));
        assertThat(history.pricingComplete()).isFalse();
        verifyNoInteractions(transactions);
    }

    @Test
    void filtersYtdFromJanuaryFirst() {
        LocalDate firstTradingDay = LocalDate.of(2026, 1, 2);
        PortfolioDailySnapshot first = snapshot(firstTradingDay, "1.25", true);
        PortfolioDailySnapshot last = snapshot(TODAY, "1.50", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(last));
        when(snapshots.findByPortfolioAndSnapshotDateBetweenOrderBySnapshotDateAsc(portfolio, TODAY.withDayOfYear(1), TODAY))
                .thenReturn(List.of(first, last));

        var history = service.getPortfolioHistory(user, portfolio.getId(), "ytd");

        assertThat(history.range()).isEqualTo("YTD");
        assertThat(history.points()).extracting(HistoryPointDTO::date).containsExactly(firstTradingDay, TODAY);
        assertThat(history.twrPercentage()).isEqualTo(new BigDecimal("20.0000"));
    }

    @Test
    void defaultsToAllAndKeepsLifetimeTwrSeparateFromChartBaseline() {
        LocalDate start = LocalDate.of(2024, 10, 1);
        PortfolioDailySnapshot first = snapshot(start, "1.20", true);
        PortfolioDailySnapshot last = snapshot(TODAY, "1.50", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(last));
        when(snapshots.findByPortfolioOrderBySnapshotDateAsc(portfolio)).thenReturn(List.of(first, last));

        var history = service.getPortfolioHistory(user, portfolio.getId(), " ");

        assertThat(history.range()).isEqualTo("ALL");
        assertThat(history.startDate()).isEqualTo(start);
        assertThat(history.twrPercentage()).isEqualTo(new BigDecimal("25.0000"));
        assertThat(service.getPortfolioTwrPercentage(portfolio)).contains(new BigDecimal("50.0000"));
    }

    @Test
    void rejectsInvalidRangeBeforeAccessingRepositories() {
        assertThatThrownBy(() -> service.getPortfolioHistory(user, portfolio.getId(), "5d"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(portfolios, snapshots);
    }

    @Test
    void rejectsPortfolioWithoutOwnership() {
        assertThatThrownBy(() -> service.getPortfolioHistory(user, portfolio.getId(), "ALL"))
                .isInstanceOf(PortfolioNotFoundException.class);
        verifyNoInteractions(snapshots);
    }

    @Test
    void returnsEmptyHistoryForPortfolioWithoutTransactions() {
        ownedPortfolio();

        var history = service.getPortfolioHistory(user, portfolio.getId(), null);

        assertThat(history.range()).isEqualTo("ALL");
        assertThat(history.startDate()).isNull();
        assertThat(history.points()).isEmpty();
        assertThat(history.benchmarks()).isEmpty();
        assertThat(history.twrPercentage()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(service.getPortfolioTwrPercentage(portfolio)).isEmpty();
        verify(snapshots, never()).saveAll(any());
    }

    @Test
    void compoundsCdiAndNormalizesIbovespaAgainstItsFirstClose() {
        LocalDate start = TODAY.minusDays(2);
        LocalDate second = TODAY.minusDays(1);
        PortfolioDailySnapshot last = snapshot(TODAY, "1", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(last));
        when(snapshots.findByPortfolioOrderBySnapshotDateAsc(portfolio)).thenReturn(List.of(snapshot(start, "1", true), last));
        Benchmark cdi = benchmark("CDI", BenchmarkType.INTEREST_RATE);
        Benchmark ibov = benchmark("^BVSP", BenchmarkType.INDEX);
        when(benchmarks.findByTicker("SELIC")).thenReturn(Optional.empty());
        when(benchmarks.findByTicker("IPCA")).thenReturn(Optional.empty());
        when(benchmarkValues.findByBenchmarkInRange(cdi, start, TODAY)).thenReturn(List.of(
                value(cdi, start, "0.50"), value(cdi, second, "0.10"), value(cdi, TODAY, "0.10")
        ));
        when(benchmarkValues.findByBenchmarkInRange(ibov, start, TODAY)).thenReturn(List.of(
                value(ibov, start, "100000"), value(ibov, second, "103000"), value(ibov, TODAY, "99000")
        ));

        var series = service.getPortfolioHistory(user, portfolio.getId(), "all").benchmarks();

        assertThat(series.keySet()).containsExactly("CDI", "SELIC", "^BVSP", "IPCA");
        assertThat(series.get("CDI")).containsExactly(
                new BenchmarkPointDTO(start, new BigDecimal("0.0000")),
                new BenchmarkPointDTO(second, new BigDecimal("0.1000")),
                new BenchmarkPointDTO(TODAY, new BigDecimal("0.2001")));
        assertThat(series.get("^BVSP")).containsExactly(
                new BenchmarkPointDTO(start, new BigDecimal("0.0000")),
                new BenchmarkPointDTO(second, new BigDecimal("3.0000")),
                new BenchmarkPointDTO(TODAY, new BigDecimal("-1.0000")));
    }

    @Test
    void rebuildsOnlyTheMissingSuffixWithCarryForwardAndPaidProvents() {
        LocalDate friday = TODAY.minusWeeks(1);
        LocalDate saturday = friday.plusDays(1);
        LocalDate wednesday = TODAY.minusDays(2);
        LocalDate thursday = TODAY.minusDays(1);
        Asset stock = asset("TEST3", AssetCategory.ACOES);
        Asset fixedIncome = asset("CDB-1", AssetCategory.RENDA_FIXA);
        PortfolioDailySnapshot prior = snapshot(friday, "1.10", true);
        prior.setTotalPatrimony(new BigDecimal("110"));
        lockPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(prior));
        when(transactions.findByPortfolioOrderByTransactionDateAscIdAsc(portfolio)).thenReturn(List.of(
                transaction(friday, stock, TransactionType.BUY, "10", "10"),
                transaction(saturday, stock, TransactionType.BUY, "10", "11"),
                transaction(thursday, stock, TransactionType.SELL, "5", "12"),
                transaction(TODAY, fixedIncome, TransactionType.BUY, "1", "50")
        ));
        when(provents.findByPortfolioOrderByProventPaymentDateDescProventComDateDesc(portfolio)).thenReturn(List.of(
                provision(thursday, ProventStatus.PENDING, "9"),
                provision(saturday, ProventStatus.PAID, "2"),
                provision(friday, ProventStatus.PAID, "100")
        ));
        when(brapi.getHistoricalQuote("TEST3", "1y", "test-token")).thenReturn(new BrapiResponseDTO(List.of(
                new BrapiResultDTO("TEST3", null, null, null, null, null, null, null, List.of(
                        new BrapiHistoricalDataDTO(instant(friday).getEpochSecond(), new BigDecimal("11")),
                        new BrapiHistoricalDataDTO(instant(thursday).getEpochSecond(), new BigDecimal("12"))
                ))
        )));

        var total = service.getPortfolioTwrPercentage(portfolio);

        verify(snapshots).saveAll(savedSnapshots.capture());
        List<PortfolioDailySnapshot> suffix = savedSnapshots.getValue();
        assertThat(suffix).extracting(PortfolioDailySnapshot::getSnapshotDate).containsExactly(wednesday, thursday, TODAY);
        assertThat(suffix.getFirst().getTotalPatrimony()).isEqualByComparingTo("220");
        assertThat(suffix.getFirst().getTotalInvested()).isEqualByComparingTo("210");
        assertThat(suffix.getFirst().getNetCashFlow()).isEqualByComparingTo("110");
        assertThat(suffix.getFirst().getProventsReceived()).isEqualByComparingTo("2");
        assertThat(suffix.get(1).getNetCashFlow()).isEqualByComparingTo("-60");
        assertThat(suffix.get(1).getProventsReceived()).isEqualByComparingTo("0");
        assertThat(suffix.getLast().getTotalPatrimony()).isEqualByComparingTo("230");
        assertThat(suffix).extracting(PortfolioDailySnapshot::isPricingComplete).containsExactly(true, true, false);
        assertThat(total).contains(new BigDecimal("21.0909"));
        verify(brapi).getHistoricalQuote("TEST3", "1y", "test-token");
    }

    @Test
    void startsAtTheFirstTradeAndFallsBackToCostWithoutBrapiToken() {
        service = new PortfolioSnapshotService(portfolios, snapshots, transactions, provents, benchmarks,
                benchmarkValues, calendar, brapi, new PortfolioTwrCalculator(), transactionManager, "");
        lockPortfolio();
        LocalDate tradeDate = TODAY.minusDays(1);
        Asset stock = asset("TEST3", AssetCategory.ACOES);
        when(transactions.findByPortfolioOrderByTransactionDateAscIdAsc(portfolio)).thenReturn(List.of(
                transaction(tradeDate, stock, TransactionType.BUY, "10", "12")
        ));

        assertThat(service.getPortfolioTwrPercentage(portfolio)).contains(new BigDecimal("0.0000"));

        verify(snapshots).saveAll(savedSnapshots.capture());
        assertThat(savedSnapshots.getValue()).extracting(PortfolioDailySnapshot::getSnapshotDate)
                .containsExactly(tradeDate, TODAY);
        assertThat(savedSnapshots.getValue()).allSatisfy(snapshot -> {
            assertThat(snapshot.getTotalPatrimony()).isEqualByComparingTo("120");
            assertThat(snapshot.isPricingComplete()).isFalse();
        });
        verifyNoInteractions(brapi);
    }

    @Test
    void invalidatesFromTheGivenDateAndIgnoresNullArguments() {
        lockPortfolio();
        service.invalidateSnapshotsFrom(null, TODAY.minusDays(1));
        service.invalidateSnapshotsFrom(portfolio, null);
        service.invalidateSnapshotsFrom(portfolio, TODAY.minusDays(1));

        verify(snapshots).lockPortfolio(portfolio.getId());
        verify(snapshots).deleteByPortfolioAndSnapshotDateGreaterThanEqual(portfolio, TODAY.minusDays(1));
    }

    @Test
    void scheduledCaptureRefreshesTheCurrentBusinessDayInOneTransaction() {
        Asset stock = asset("TEST3", AssetCategory.ACOES);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(portfolios.findAll()).thenReturn(List.of(portfolio));
        lockPortfolio();
        when(transactions.findByPortfolioOrderByTransactionDateAscIdAsc(portfolio)).thenReturn(List.of(
                transaction(TODAY, stock, TransactionType.BUY, "10", "12")
        ));

        service.captureDailySnapshotsForAllPortfolios();

        var order = inOrder(transactionManager, snapshots);
        order.verify(transactionManager).getTransaction(any());
        order.verify(snapshots).lockPortfolio(portfolio.getId());
        order.verify(snapshots).deleteByPortfolioAndSnapshotDateGreaterThanEqual(portfolio, TODAY);
        order.verify(snapshots).saveAll(savedSnapshots.capture());
        order.verify(transactionManager).commit(transactionStatus);
        assertThat(savedSnapshots.getValue()).extracting(PortfolioDailySnapshot::getSnapshotDate).containsExactly(TODAY);
    }

    private void ownedPortfolio() {
        when(portfolios.findByIdAndUser(portfolio.getId(), user)).thenReturn(Optional.of(portfolio));
        lockPortfolio();
    }

    private void lockPortfolio() {
        when(snapshots.lockPortfolio(portfolio.getId())).thenReturn(Optional.of(portfolio.getId()));
    }

    private PortfolioDailySnapshot snapshot(LocalDate date, String quota, boolean pricingComplete) {
        return new PortfolioDailySnapshot(null, portfolio, date, new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal(quota), pricingComplete);
    }

    private Benchmark benchmark(String ticker, BenchmarkType type) {
        Benchmark benchmark = new Benchmark(UUID.randomUUID(), ticker, ticker, type, null, instant(TODAY));
        when(benchmarks.findByTicker(ticker)).thenReturn(Optional.of(benchmark));
        return benchmark;
    }

    private BenchmarkValue value(Benchmark benchmark, LocalDate date, String amount) {
        return new BenchmarkValue(null, benchmark, date, new BigDecimal(amount));
    }

    private Asset asset(String ticker, AssetCategory category) {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker(ticker);
        asset.setCategory(category);
        return asset;
    }

    private Transaction transaction(LocalDate date, Asset asset, TransactionType type, String quantity, String price) {
        return new Transaction(UUID.randomUUID(), portfolio, asset, type, new BigDecimal(quantity),
                new BigDecimal(price), instant(date), null, null);
    }

    private ProventProvision provision(LocalDate date, ProventStatus status, String amount) {
        Provent provent = new Provent();
        provent.setPaymentDate(date);
        ProventProvision provision = new ProventProvision();
        provision.setProvent(provent);
        provision.setStatus(status);
        provision.setNetAmount(new BigDecimal(amount));
        return provision;
    }

    private static Instant instant(LocalDate date) {
        return date.atTime(15, 0).toInstant(ZoneOffset.UTC);
    }
}
