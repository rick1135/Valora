package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiHistoricalDataDTO;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import com.rick1135.Valora.dto.response.PortfolioHistoryResponseDTO.BenchmarkPointDTO;
import com.rick1135.Valora.dto.response.PortfolioHistoryResponseDTO.HistoryPointDTO;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkType;
import com.rick1135.Valora.entity.BenchmarkValue;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.PortfolioDailySnapshot;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.repository.BenchmarkRepository;
import com.rick1135.Valora.repository.BenchmarkValueRepository;
import com.rick1135.Valora.repository.PortfolioDailySnapshotRepository;
import com.rick1135.Valora.repository.PortfolioDailySnapshotRepository.PaidProventTotal;
import com.rick1135.Valora.repository.PortfolioDailySnapshotRepository.SnapshotTrade;
import com.rick1135.Valora.repository.PortfolioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioSnapshotServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 2, 20);

    @Mock private PortfolioRepository portfolios;
    @Mock private PortfolioDailySnapshotRepository snapshots;
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
        service = new PortfolioSnapshotService(portfolios, snapshots, benchmarks,
                benchmarkValues, calendar, brapi, new PortfolioTwrCalculator(), transactionManager, "test-token");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void usesThePriorSnapshotAndItsPricingQualityForTheRange(boolean priorPricingComplete) {
        LocalDate start = TODAY.minusMonths(1);
        PortfolioDailySnapshot first = snapshot(start, "1.10", true);
        PortfolioDailySnapshot last = snapshot(TODAY, "1.21", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(last));
        when(snapshots.findFirstByPortfolioAndSnapshotDateLessThanOrderBySnapshotDateDesc(portfolio, start))
                .thenReturn(Optional.of(snapshot(calendar.previousBusinessDay(start), "1", priorPricingComplete)));
        when(snapshots.findByPortfolioAndSnapshotDateBetweenOrderBySnapshotDateAsc(portfolio, start, TODAY))
                .thenReturn(List.of(first, last));

        var history = service.getPortfolioHistory(user, portfolio.getId(), " 1m ");

        assertThat(history.range()).isEqualTo("1M");
        assertThat(history.startDate()).isEqualTo(start);
        assertThat(history.endDate()).isEqualTo(TODAY);
        assertThat(history.points()).extracting(HistoryPointDTO::twrPercentage)
                .containsExactly(new BigDecimal("10.0000"), new BigDecimal("21.0000"));
        assertThat(history.twrPercentage()).isEqualTo(new BigDecimal("21.0000"));
        assertThat(history.points()).allMatch(HistoryPointDTO::pricingComplete);
        assertThat(history.pricingComplete()).isEqualTo(priorPricingComplete);
        verifyNoInteractions(brapi, transactionManager);
    }

    @Test
    void preservesReturnOnTheFirstTradingDayOfYtd() {
        LocalDate firstTradingDay = LocalDate.of(2026, 1, 2);
        PortfolioDailySnapshot first = snapshot(firstTradingDay, "1.25", true);
        PortfolioDailySnapshot last = snapshot(TODAY, "1.50", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(last));
        when(snapshots.findFirstByPortfolioAndSnapshotDateLessThanOrderBySnapshotDateDesc(portfolio, firstTradingDay))
                .thenReturn(Optional.of(snapshot(LocalDate.of(2025, 12, 31), "1.20", true)));
        when(snapshots.findByPortfolioAndSnapshotDateBetweenOrderBySnapshotDateAsc(portfolio, TODAY.withDayOfYear(1), TODAY))
                .thenReturn(List.of(first, last));

        var history = service.getPortfolioHistory(user, portfolio.getId(), "ytd");

        assertThat(history.range()).isEqualTo("YTD");
        assertThat(history.points()).extracting(HistoryPointDTO::date).containsExactly(firstTradingDay, TODAY);
        assertThat(history.points().getFirst().twrPercentage()).isEqualByComparingTo("4.1667");
        assertThat(history.twrPercentage()).isEqualByComparingTo("25");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1M", "3M", "6M", "1Y", "YTD"})
    void usesTheInitialQuotaWhenThePortfolioStartsWithinTheRange(String range) {
        LocalDate start = switch (range) {
            case "1M" -> TODAY.minusMonths(1);
            case "3M" -> TODAY.minusMonths(3);
            case "6M" -> TODAY.minusMonths(6);
            case "1Y" -> TODAY.minusYears(1);
            default -> TODAY.withDayOfYear(1);
        };
        var latest = snapshot(TODAY, "1.10", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(latest));
        when(snapshots.findByPortfolioAndSnapshotDateBetweenOrderBySnapshotDateAsc(portfolio, start, TODAY))
                .thenReturn(List.of(latest));

        var history = service.getPortfolioHistory(user, portfolio.getId(), range);

        assertThat(history.twrPercentage()).isEqualByComparingTo("10");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void measuresARangeStartingAtOrAfterReentry(boolean afterReentry) {
        LocalDate start = TODAY.minusMonths(1);
        var latest = snapshot(TODAY, "1", true, 2);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(latest));
        when(snapshots.findByPortfolioAndSnapshotDateBetweenOrderBySnapshotDateAsc(portfolio, start, TODAY))
                .thenReturn(List.of(snapshot(start, "0.50", true, 2), latest));
        var prior = afterReentry
                ? snapshot(calendar.previousBusinessDay(start), "0.25", true, 2)
                : snapshot(calendar.previousBusinessDay(start), "0", false, 1);
        when(snapshots.findFirstByPortfolioAndSnapshotDateLessThanOrderBySnapshotDateDesc(portfolio, start))
                .thenReturn(Optional.of(prior));

        var history = service.getPortfolioHistory(user, portfolio.getId(), "1M");

        assertThat(history.points()).extracting(HistoryPointDTO::twrSegment).containsExactly(2, 2);
        assertThat(history.points().getFirst().twrPercentage()).isEqualByComparingTo(afterReentry ? "100" : "-50");
        assertThat(history.twrPercentage()).isEqualByComparingTo(afterReentry ? "300" : "0");
        assertThat(history.pricingComplete()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void doesNotCombineTheTwoSegmentsAcrossTotalLoss(boolean allHistory) {
        LocalDate lossDate = TODAY.minusMonths(1);
        var gain = snapshot(calendar.previousBusinessDay(lossDate), "2", true, 1);
        var loss = snapshot(lossDate, "0", true, 1);
        var reentry = snapshot(TODAY, "1", true, 2);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(reentry));
        if (allHistory) {
            when(snapshots.findByPortfolioOrderBySnapshotDateAsc(portfolio)).thenReturn(List.of(gain, loss, reentry));
        } else {
            when(snapshots.findFirstByPortfolioAndSnapshotDateLessThanOrderBySnapshotDateDesc(portfolio, lossDate))
                    .thenReturn(Optional.of(gain));
            when(snapshots.findByPortfolioAndSnapshotDateBetweenOrderBySnapshotDateAsc(portfolio, lossDate, TODAY))
                    .thenReturn(List.of(loss, reentry));
        }

        var history = service.getPortfolioHistory(user, portfolio.getId(), allHistory ? "ALL" : "1M");

        assertThat(history.twrPercentage()).isNull();
        assertThat(history.points().get(history.points().size() - 2).twrPercentage()).isEqualByComparingTo("-100");
        assertThat(history.points().getLast().twrPercentage()).isNull();
        assertThat(history.points().getLast().twrSegment()).isEqualTo(2);
        assertThat(service.getPortfolioTwrSummary(portfolio))
                .contains(new PortfolioSnapshotService.PortfolioTwrSummary(null, false, TODAY, true));
    }

    @Test
    void doesNotRebaseAZeroQuotaWhileStillInTheSameSegment() {
        LocalDate start = TODAY.minusMonths(1);
        var latest = snapshot(TODAY, "0", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(latest));
        when(snapshots.findByPortfolioAndSnapshotDateBetweenOrderBySnapshotDateAsc(portfolio, start, TODAY))
                .thenReturn(List.of(snapshot(start, "0", true), latest));
        when(snapshots.findFirstByPortfolioAndSnapshotDateLessThanOrderBySnapshotDateDesc(portfolio, start))
                .thenReturn(Optional.of(snapshot(calendar.previousBusinessDay(start), "0", true)));

        var history = service.getPortfolioHistory(user, portfolio.getId(), "1M");

        assertThat(history.twrPercentage()).isNull();
        assertThat(history.points()).allMatch(point -> point.twrPercentage() == null && point.twrSegment() == 1);
    }

    @Test
    void persistsTheNewSegmentWhenCapturingASuffixAfterTotalLoss() {
        var prior = snapshot(TODAY.minusDays(1), "0", true, 2);
        prior.setTotalPatrimony(BigDecimal.ZERO);
        ownedPortfolio();
        captureTransaction();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(prior));
        stubTrades(List.of(trade(TODAY, UUID.randomUUID(), "TEST3", AssetCategory.ACOES,
                TransactionType.BUY, "1", "100")));
        when(brapi.getHistoricalQuote("TEST3", "1y", "test-token")).thenReturn(quotes(price(TODAY, "100")));

        service.getPortfolioHistory(user, portfolio.getId(), "ALL");

        verify(snapshots).saveAll(savedSnapshots.capture());
        var reentry = savedSnapshots.getValue().getFirst();
        assertThat(reentry.getTwrQuotaValue()).isEqualByComparingTo("1");
        assertThat(reentry.getTwrSegment()).isEqualTo(3);
    }

    @Test
    void preservesTheFirstDayGainInAllAndInTheSummary() {
        PortfolioDailySnapshot first = snapshot(TODAY, "1.10", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(first));
        when(snapshots.findByPortfolioOrderBySnapshotDateAsc(portfolio)).thenReturn(List.of(first));

        var history = service.getPortfolioHistory(user, portfolio.getId(), " ");

        assertThat(history.range()).isEqualTo("ALL");
        assertThat(history.startDate()).isEqualTo(TODAY);
        assertThat(history.twrPercentage()).isEqualByComparingTo("10");
        assertThat(service.getPortfolioTwrPercentage(portfolio)).contains(new BigDecimal("10.0000"));
    }

    @Test
    void summaryReadsOnlyPersistedSnapshotsAndChecksEarlierPricingGaps() throws NoSuchMethodException {
        var annotation = PortfolioSnapshotService.class.getMethod("getPortfolioTwrSummary", Portfolio.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation.readOnly()).isTrue();
        assertThat(annotation.propagation().value()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio))
                .thenReturn(Optional.of(snapshot(TODAY, "1.10", true)));
        when(snapshots.existsByPortfolioAndPricingCompleteFalse(portfolio)).thenReturn(true);

        assertThat(service.getPortfolioTwrSummary(portfolio))
                .contains(new PortfolioSnapshotService.PortfolioTwrSummary(new BigDecimal("10.0000"), false, TODAY, true));

        verify(snapshots).findFirstByPortfolioOrderBySnapshotDateDesc(portfolio);
        verify(snapshots).existsByPortfolioAndPricingCompleteFalse(portfolio);
        verifyNoMoreInteractions(snapshots);
        verifyNoInteractions(brapi, transactionManager, portfolios, benchmarks, benchmarkValues);
    }

    @Test
    void datesAStaleSummaryAndMarksItUnreliableEvenWithCompletePrices() {
        LocalDate valuedThrough = LocalDate.of(2026, 2, 2);
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio))
                .thenReturn(Optional.of(snapshot(valuedThrough, "1.10", true)));

        assertThat(service.getPortfolioTwrSummary(portfolio))
                .contains(new PortfolioSnapshotService.PortfolioTwrSummary(
                        new BigDecimal("10.0000"), false, valuedThrough, false));
        verifyNoInteractions(brapi, transactionManager);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-02-21T01:29:59Z, 2026-02-19, true",
            "2026-02-21T01:30:00Z, 2026-02-19, false",
            "2026-02-21T01:30:00Z, 2026-02-20, true",
            "2026-02-22T01:30:00Z, 2026-02-20, true"
    })
    void usesTheSaoPauloCaptureCutoffAndBusinessDays(String now, LocalDate valuedThrough, boolean upToDate) {
        var evaluationCalendar = new MarketCalendar(Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
        service = new PortfolioSnapshotService(portfolios, snapshots, benchmarks,
                benchmarkValues, evaluationCalendar, brapi, new PortfolioTwrCalculator(), transactionManager, "test-token");
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio))
                .thenReturn(Optional.of(snapshot(valuedThrough, "1.10", true)));

        assertThat(service.getPortfolioTwrSummary(portfolio))
                .contains(new PortfolioSnapshotService.PortfolioTwrSummary(
                        new BigDecimal("10.0000"), upToDate, valuedThrough, upToDate));
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
        assertThat(history.benchmarksComplete()).isEmpty();
        assertThat(history.twrPercentage()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(service.getPortfolioTwrPercentage(portfolio)).isEmpty();
        verify(snapshots, never()).saveAll(any());
    }

    @Test
    void includesFirstDayReturnsAndToleratesOnlyTheLastMissingRate() {
        LocalDate start = TODAY.minusDays(2);
        LocalDate second = TODAY.minusDays(1);
        PortfolioDailySnapshot last = snapshot(TODAY, "1", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(last));
        when(snapshots.findByPortfolioOrderBySnapshotDateAsc(portfolio)).thenReturn(List.of(snapshot(start, "1", true), last));
        Benchmark cdi = benchmark("CDI", BenchmarkType.INTEREST_RATE);
        Benchmark selic = benchmark("SELIC", BenchmarkType.INTEREST_RATE);
        Benchmark ibov = benchmark("^BVSP", BenchmarkType.INDEX);
        when(benchmarkValues.findByBenchmarkInRange(cdi, start, TODAY)).thenReturn(List.of(
                value(cdi, start, "0.50"), value(cdi, second, "0.10")
        ));
        when(benchmarkValues.findByBenchmarkInRange(selic, start, TODAY)).thenReturn(List.of(
                value(selic, start, "0.50"), value(selic, TODAY, "0.10")
        ));
        LocalDate baseDate = calendar.previousBusinessDay(start);
        when(benchmarkValues.findLatestBeforeOrAt(ibov, baseDate))
                .thenReturn(Optional.of(value(ibov, baseDate, "100000")));
        when(benchmarkValues.findByBenchmarkInRange(ibov, start, TODAY)).thenReturn(List.of(
                value(ibov, start, "101000"), value(ibov, second, "103000"), value(ibov, TODAY, "99000")
        ));

        var history = service.getPortfolioHistory(user, portfolio.getId(), "all");
        var series = history.benchmarks();

        assertThat(history.benchmarksComplete()).isEqualTo(Map.of("CDI", true, "SELIC", false, "^BVSP", true, "IPCA", false));
        assertThat(series.keySet()).containsExactly("CDI", "SELIC", "^BVSP", "IPCA");
        assertThat(series.get("CDI")).containsExactly(
                new BenchmarkPointDTO(start, new BigDecimal("0.5000")),
                new BenchmarkPointDTO(second, new BigDecimal("0.6005")));
        assertThat(series.get("^BVSP")).containsExactly(
                new BenchmarkPointDTO(start, new BigDecimal("1.0000")),
                new BenchmarkPointDTO(second, new BigDecimal("3.0000")),
                new BenchmarkPointDTO(TODAY, new BigDecimal("-1.0000")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesToRebaseIbovespaWhenTheOpeningCloseIsMissingOrTooOld(boolean staleBase) {
        var last = snapshot(TODAY, "1", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(last));
        when(snapshots.findByPortfolioOrderBySnapshotDateAsc(portfolio)).thenReturn(List.of(last));
        Benchmark ibov = benchmark("^BVSP", BenchmarkType.INDEX);
        LocalDate baseDate = calendar.previousBusinessDay(TODAY);
        when(benchmarkValues.findLatestBeforeOrAt(ibov, baseDate)).thenReturn(staleBase
                ? Optional.of(value(ibov, baseDate.minusWeeks(2), "100000")) : Optional.empty());

        var history = service.getPortfolioHistory(user, portfolio.getId(), "ALL");

        assertThat(history.benchmarks()).containsEntry("^BVSP", List.of());
        assertThat(history.benchmarksComplete()).containsEntry("^BVSP", false);
        verify(benchmarkValues, never()).findByBenchmarkInRange(ibov, TODAY, TODAY);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 15})
    void reportsPartialIpcaCoverageAndAppliesOnlyWholeMonths(int dayOfMonth) {
        LocalDate firstDate = LocalDate.of(2025, 12, dayOfMonth);
        var last = snapshot(TODAY, "1", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(last));
        when(snapshots.findByPortfolioOrderBySnapshotDateAsc(portfolio))
                .thenReturn(List.of(snapshot(firstDate, "1", true), last));
        Benchmark ipca = benchmark("IPCA", BenchmarkType.INFLATION);
        LocalDate december = firstDate.withDayOfMonth(1);
        LocalDate january = december.plusMonths(1);
        when(benchmarkValues.findByBenchmarkInRange(ipca, december, TODAY)).thenReturn(List.of(
                value(ipca, december, "1"), value(ipca, january, "-0.20"), value(ipca, january.plusMonths(1), "9")));

        var history = service.getPortfolioHistory(user, portfolio.getId(), "ALL");

        assertThat(history.benchmarksComplete()).containsEntry("IPCA", false);
        var januaryClose = LocalDate.of(2026, 1, 31);
        if (dayOfMonth == 1) {
            assertThat(history.benchmarks().get("IPCA")).containsExactly(
                    new BenchmarkPointDTO(LocalDate.of(2025, 12, 31), new BigDecimal("1.0000")),
                    new BenchmarkPointDTO(januaryClose, new BigDecimal("0.7980")));
        } else {
            assertThat(history.benchmarks().get("IPCA")).containsExactly(
                    new BenchmarkPointDTO(januaryClose, new BigDecimal("-0.2000")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requiresPublishedIpcaWhenTheRangeEndsOnTheLastDayOfTheMonth(boolean published) {
        LocalDate december = LocalDate.of(2025, 12, 1);
        LocalDate end = december.withDayOfMonth(31);
        var monthEndCalendar = new MarketCalendar(Clock.fixed(instant(end), ZoneOffset.UTC));
        service = new PortfolioSnapshotService(portfolios, snapshots, benchmarks,
                benchmarkValues, monthEndCalendar, brapi, new PortfolioTwrCalculator(), transactionManager, "test-token");
        var last = snapshot(end, "1", true);
        ownedPortfolio();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(last));
        when(snapshots.findByPortfolioOrderBySnapshotDateAsc(portfolio))
                .thenReturn(List.of(snapshot(december, "1", true), last));
        Benchmark ipca = benchmark("IPCA", BenchmarkType.INFLATION);
        when(benchmarkValues.findByBenchmarkInRange(ipca, december, end))
                .thenReturn(published ? List.of(value(ipca, december, "0.50")) : List.of());

        var history = service.getPortfolioHistory(user, portfolio.getId(), "ALL");

        assertThat(history.benchmarksComplete()).containsEntry("IPCA", published);
        assertThat(history.benchmarks().get("IPCA")).isEqualTo(
                published ? List.of(new BenchmarkPointDTO(end, new BigDecimal("0.5000"))) : List.of());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsFixedIncomeAndMixedSnapshotsWithoutRepeatingCapture(boolean mixed) {
        var stored = new AtomicReference<List<PortfolioDailySnapshot>>(List.of());
        ownedPortfolio();
        captureTransaction();
        var fixedIncome = trade(TODAY, UUID.randomUUID(), "CDB-1", AssetCategory.RENDA_FIXA,
                TransactionType.BUY, "1", "100");
        if (mixed) {
            var stock = trade(TODAY, UUID.randomUUID(), "TEST3", AssetCategory.ACOES,
                    TransactionType.BUY, "1", "50");
            stubTrades(List.of(fixedIncome, stock));
            when(brapi.getHistoricalQuote("TEST3", "1y", "test-token")).thenReturn(quotes(price(TODAY, "55")));
        } else {
            stubTrades(List.of(fixedIncome));
        }
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenAnswer(call -> {
            var captured = stored.get();
            return captured.isEmpty() ? Optional.empty() : Optional.of(captured.getLast());
        });
        when(snapshots.findFirstByPortfolioAndRecoverablePricingGapTrueOrderBySnapshotDateAsc(portfolio))
                .thenAnswer(call -> stored.get().stream().filter(PortfolioDailySnapshot::isRecoverablePricingGap).findFirst());
        when(snapshots.findByPortfolioOrderBySnapshotDateAsc(portfolio)).thenAnswer(call -> stored.get());
        when(snapshots.saveAll(any())).thenAnswer(call -> {
            List<PortfolioDailySnapshot> captured = call.getArgument(0);
            stored.set(captured);
            return captured;
        });

        var initial = service.getPortfolioHistory(user, portfolio.getId(), "ALL");
        var repeated = service.getPortfolioHistory(user, portfolio.getId(), "ALL");

        assertThat(initial.pricingComplete()).isFalse();
        assertThat(repeated).isEqualTo(initial);
        assertThat(stored.get()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.isPricingComplete()).isFalse();
            assertThat(snapshot.isRecoverablePricingGap()).isFalse();
        });
        verify(snapshots).saveAll(any());
        verify(snapshots, times(2)).findSnapshotTrades(portfolio, calendar.endOfMarketDay(TODAY).plusNanos(1));
        verify(snapshots, never()).deleteByPortfolioAndSnapshotDateGreaterThanEqual(any(), any());
        if (mixed) {
            verify(brapi).getHistoricalQuote("TEST3", "1y", "test-token");
            verifyNoMoreInteractions(brapi);
        } else {
            verifyNoInteractions(brapi);
        }
    }

    @Test
    void rebuildsOnlyTheMissingSuffixWithCarryForwardAndPaidProvents() {
        LocalDate friday = TODAY.minusWeeks(1);
        LocalDate saturday = friday.plusDays(1);
        LocalDate wednesday = TODAY.minusDays(2);
        LocalDate thursday = TODAY.minusDays(1);
        UUID stock = UUID.randomUUID();
        UUID fixedIncome = UUID.randomUUID();
        PortfolioDailySnapshot prior = snapshot(friday, "1.10", true);
        prior.setTotalPatrimony(new BigDecimal("110"));
        captureTransaction();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)).thenReturn(Optional.of(prior));
        stubTrades(List.of(
                trade(friday, stock, "TEST3", AssetCategory.ACOES, TransactionType.BUY, "10", "10"),
                trade(saturday, stock, "TEST3", AssetCategory.ACOES, TransactionType.BUY, "10", "11"),
                trade(thursday, stock, "TEST3", AssetCategory.ACOES, TransactionType.SELL, "5", "12"),
                trade(TODAY, fixedIncome, "CDB-1", AssetCategory.RENDA_FIXA, TransactionType.BUY, "1", "50")
        ));
        var payments = List.of(paidProvent(saturday, "2"), paidProvent(friday, "100"));
        when(snapshots.findSnapshotPaidProvents(portfolio, friday, TODAY)).thenReturn(payments);
        when(brapi.getHistoricalQuote("TEST3", "1y", "test-token")).thenReturn(new BrapiResponseDTO(List.of(
                new BrapiResultDTO("TEST3", null, null, null, null, null, null, null, List.of(
                        new BrapiHistoricalDataDTO(instant(friday).getEpochSecond(), new BigDecimal("11")),
                        new BrapiHistoricalDataDTO(instant(thursday).getEpochSecond(), new BigDecimal("12"))
                ))
        )));

        ownedPortfolio();
        service.getPortfolioHistory(user, portfolio.getId(), "ALL");

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
        assertThat(suffix).extracting(PortfolioDailySnapshot::isPricingComplete).containsExactly(false, true, false);
        assertThat(suffix).extracting(PortfolioDailySnapshot::isRecoverablePricingGap).containsExactly(true, false, true);
        assertThat(suffix.getLast().getTwrQuotaValue()).isEqualByComparingTo("1.210909090909");
        verify(brapi).getHistoricalQuote("TEST3", "1y", "test-token");
    }

    @Test
    void replacesTheIncompleteSuffixWhenBrapiRecovers() {
        LocalDate tradeDate = TODAY.minusDays(2);
        LocalDate incompleteDate = TODAY.minusDays(1);
        var prior = snapshot(tradeDate, "1", true);
        var stored = new AtomicReference<>(List.of(prior));
        ownedPortfolio();
        TransactionStatus status = captureTransaction();
        stubTrades(List.of(trade(tradeDate, UUID.randomUUID(), "TEST3", AssetCategory.ACOES,
                TransactionType.BUY, "10", "10")));
        when(snapshots.findByPortfolioOrderBySnapshotDateAsc(portfolio)).thenAnswer(call -> stored.get());
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio))
                .thenAnswer(call -> Optional.of(stored.get().getLast()));
        when(snapshots.findFirstByPortfolioAndRecoverablePricingGapTrueOrderBySnapshotDateAsc(portfolio))
                .thenAnswer(call -> stored.get().stream().filter(PortfolioDailySnapshot::isRecoverablePricingGap).findFirst());
        lenient().when(snapshots.findFirstByPortfolioAndSnapshotDateLessThanOrderBySnapshotDateDesc(portfolio, tradeDate))
                .thenReturn(Optional.empty());
        lenient().when(snapshots.findFirstByPortfolioAndSnapshotDateLessThanOrderBySnapshotDateDesc(portfolio, incompleteDate))
                .thenReturn(Optional.of(prior));
        when(snapshots.saveAll(any())).thenAnswer(call -> {
            List<PortfolioDailySnapshot> suffix = call.getArgument(0);
            stored.set(List.of(prior, suffix.getFirst(), suffix.getLast()));
            return suffix;
        });
        when(brapi.getHistoricalQuote("TEST3", "1y", "test-token"))
                .thenReturn(quotes(price(TODAY.minusDays(18), "12")))
                .thenThrow(new RestClientException("temporarily unavailable"))
                .thenReturn(quotes(price(incompleteDate, "11"), price(TODAY, "13")));

        var stale = service.getPortfolioHistory(user, portfolio.getId(), "ALL");
        var fallback = service.getPortfolioHistory(user, portfolio.getId(), "ALL");
        var recovered = service.getPortfolioHistory(user, portfolio.getId(), "ALL");

        assertThat(stale.pricingComplete()).isFalse();
        assertThat(stale.points().get(1).totalPatrimony()).isEqualByComparingTo("120");
        assertThat(fallback.pricingComplete()).isFalse();
        assertThat(fallback.points().get(1).totalPatrimony()).isEqualByComparingTo("100");
        assertThat(recovered.pricingComplete()).isTrue();
        assertThat(recovered.points().get(1).twrPercentage()).isEqualByComparingTo("10");
        assertThat(recovered.twrPercentage()).isEqualByComparingTo("30");

        var order = inOrder(brapi, transactionManager, snapshots);
        order.verify(brapi).getHistoricalQuote("TEST3", "1y", "test-token");
        order.verify(transactionManager).getTransaction(any());
        order.verify(snapshots).saveAll(any());
        order.verify(transactionManager).commit(status);
        for (int attempt = 0; attempt < 2; attempt++) {
            order.verify(brapi).getHistoricalQuote("TEST3", "1y", "test-token");
            order.verify(transactionManager).getTransaction(any());
            order.verify(snapshots).lockPortfolio(portfolio.getId());
            order.verify(snapshots).deleteByPortfolioAndSnapshotDateGreaterThanEqual(portfolio, incompleteDate);
            order.verify(snapshots).saveAll(any());
            order.verify(transactionManager).commit(status);
        }
    }

    @Test
    void retriesCaptureWhenTradesChangeDuringTheQuoteFetch() {
        UUID assetId = UUID.randomUUID();
        var before = trade(TODAY, assetId, "TEST3", AssetCategory.ACOES, TransactionType.BUY, "1", "10");
        var after = trade(TODAY, assetId, "TEST3", AssetCategory.ACOES, TransactionType.BUY, "1", "12");
        ownedPortfolio();
        captureTransaction();
        when(snapshots.findSnapshotTrades(portfolio, calendar.endOfMarketDay(TODAY).plusNanos(1)))
                .thenReturn(List.of(before), List.of(after));
        when(brapi.getHistoricalQuote("TEST3", "1y", "test-token")).thenReturn(quotes(price(TODAY, "13")));

        service.getPortfolioHistory(user, portfolio.getId(), "ALL");

        verify(snapshots).saveAll(savedSnapshots.capture());
        assertThat(savedSnapshots.getValue().getFirst().getNetCashFlow()).isEqualByComparingTo("12");
        assertThat(savedSnapshots.getValue().getFirst().getDailyReturnFactor()).isEqualByComparingTo("1.083333333333");
    }

    @Test
    void persistsNetCashFlowAndCalculatesSameDayBuysAndSalesSeparately() {
        LocalDate priorDate = TODAY.minusDays(1);
        UUID stock = UUID.randomUUID();
        ownedPortfolio();
        captureTransaction();
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio))
                .thenReturn(Optional.of(snapshot(priorDate, "1", true)));
        stubTrades(List.of(
                trade(priorDate, stock, "TEST3", AssetCategory.ACOES, TransactionType.BUY, "10", "10"),
                trade(TODAY, stock, "TEST3", AssetCategory.ACOES, TransactionType.BUY, "10", "10"),
                trade(TODAY, stock, "TEST3", AssetCategory.ACOES, TransactionType.SELL, "10", "11")));
        when(brapi.getHistoricalQuote("TEST3", "1y", "test-token")).thenReturn(quotes(price(TODAY, "11")));

        service.getPortfolioHistory(user, portfolio.getId(), "ALL");

        verify(snapshots).saveAll(savedSnapshots.capture());
        var result = savedSnapshots.getValue().getFirst();
        assertThat(result.getNetCashFlow()).isEqualByComparingTo("-10");
        assertThat(result.getTwrQuotaValue()).isEqualByComparingTo("1.10");
    }

    @Test
    void startsAtTheFirstTradeAndFallsBackToCostWithoutBrapiToken() {
        service = new PortfolioSnapshotService(portfolios, snapshots, benchmarks,
                benchmarkValues, calendar, brapi, new PortfolioTwrCalculator(), transactionManager, "");
        captureTransaction();
        ownedPortfolio();
        LocalDate tradeDate = TODAY.minusDays(1);
        stubTrades(List.of(
                trade(tradeDate, UUID.randomUUID(), "TEST3", AssetCategory.ACOES, TransactionType.BUY, "10", "12")
        ));

        service.getPortfolioHistory(user, portfolio.getId(), "ALL");

        verify(snapshots).saveAll(savedSnapshots.capture());
        assertThat(savedSnapshots.getValue()).extracting(PortfolioDailySnapshot::getSnapshotDate)
                .containsExactly(tradeDate, TODAY);
        assertThat(savedSnapshots.getValue()).allSatisfy(snapshot -> {
            assertThat(snapshot.getTotalPatrimony()).isEqualByComparingTo("120");
            assertThat(snapshot.isPricingComplete()).isFalse();
            assertThat(snapshot.isRecoverablePricingGap()).isTrue();
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
        TransactionStatus transactionStatus = captureTransaction();
        when(portfolios.findAll()).thenReturn(List.of(portfolio));
        when(snapshots.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio))
                .thenReturn(Optional.of(snapshot(TODAY, "1", true)));
        stubTrades(List.of(
                trade(TODAY, UUID.randomUUID(), "TEST3", AssetCategory.ACOES, TransactionType.BUY, "10", "12")
        ));

        service.captureDailySnapshotsForAllPortfolios();

        var order = inOrder(brapi, transactionManager, snapshots);
        order.verify(brapi).getHistoricalQuote("TEST3", "1y", "test-token");
        order.verify(transactionManager).getTransaction(any());
        order.verify(snapshots).lockPortfolio(portfolio.getId());
        order.verify(snapshots).deleteByPortfolioAndSnapshotDateGreaterThanEqual(portfolio, TODAY);
        order.verify(snapshots).saveAll(savedSnapshots.capture());
        order.verify(transactionManager).commit(transactionStatus);
        assertThat(savedSnapshots.getValue()).extracting(PortfolioDailySnapshot::getSnapshotDate).containsExactly(TODAY);
    }

    private void ownedPortfolio() {
        when(portfolios.findByIdAndUser(portfolio.getId(), user)).thenReturn(Optional.of(portfolio));
    }

    private TransactionStatus captureTransaction() {
        lockPortfolio();
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);
        return status;
    }

    private void lockPortfolio() {
        when(snapshots.lockPortfolio(portfolio.getId())).thenReturn(Optional.of(portfolio.getId()));
    }

    private PortfolioDailySnapshot snapshot(LocalDate date, String quota, boolean pricingComplete) {
        return snapshot(date, quota, pricingComplete, 1);
    }

    private PortfolioDailySnapshot snapshot(LocalDate date, String quota, boolean pricingComplete, int segment) {
        return new PortfolioDailySnapshot(null, portfolio, date, new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal(quota), pricingComplete, segment, false);
    }

    private Benchmark benchmark(String ticker, BenchmarkType type) {
        Benchmark benchmark = new Benchmark(UUID.randomUUID(), ticker, ticker, type, null, instant(TODAY));
        lenient().when(benchmarks.findByTicker(ticker)).thenReturn(Optional.of(benchmark));
        return benchmark;
    }

    private BenchmarkValue value(Benchmark benchmark, LocalDate date, String amount) {
        return new BenchmarkValue(null, benchmark, date, new BigDecimal(amount));
    }

    private void stubTrades(List<SnapshotTrade> trades) {
        when(snapshots.findSnapshotTrades(portfolio, calendar.endOfMarketDay(TODAY).plusNanos(1))).thenReturn(trades);
    }

    private SnapshotTrade trade(LocalDate date, UUID assetId, String ticker, AssetCategory category, TransactionType type,
                                String quantity, String price) {
        SnapshotTrade trade = mock(SnapshotTrade.class);
        when(trade.getId()).thenReturn(UUID.randomUUID());
        when(trade.getTransactionDate()).thenReturn(instant(date));
        when(trade.getType()).thenReturn(type);
        when(trade.getQuantity()).thenReturn(new BigDecimal(quantity));
        when(trade.getUnitPrice()).thenReturn(new BigDecimal(price));
        when(trade.getAssetId()).thenReturn(assetId);
        when(trade.getTicker()).thenReturn(ticker);
        when(trade.getCategory()).thenReturn(category);
        return trade;
    }

    private PaidProventTotal paidProvent(LocalDate date, String amount) {
        PaidProventTotal total = mock(PaidProventTotal.class);
        when(total.getPaymentDate()).thenReturn(date);
        when(total.getNetAmount()).thenReturn(new BigDecimal(amount));
        return total;
    }

    private BrapiResponseDTO quotes(BrapiHistoricalDataDTO... prices) {
        return new BrapiResponseDTO(List.of(
                new BrapiResultDTO("TEST3", null, null, null, null, null, null, null, List.of(prices))));
    }

    private BrapiHistoricalDataDTO price(LocalDate date, String close) {
        return new BrapiHistoricalDataDTO(instant(date).getEpochSecond(), new BigDecimal(close));
    }

    private static Instant instant(LocalDate date) {
        return date.atTime(15, 0).toInstant(ZoneOffset.UTC);
    }
}
