package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BcbClient;
import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiHistoricalDataDTO;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import com.rick1135.Valora.dto.response.BenchmarkStatusResponseDTO;
import com.rick1135.Valora.dto.response.BenchmarkSyncResponseDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkProvider;
import com.rick1135.Valora.entity.BenchmarkType;
import com.rick1135.Valora.entity.BenchmarkValue;
import com.rick1135.Valora.repository.BenchmarkRepository;
import com.rick1135.Valora.repository.BenchmarkValueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BenchmarkSyncServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 20);
    private static final BigDecimal VALUE = new BigDecimal("0.055");

    @Mock
    private BenchmarkRepository benchmarkRepository;
    @Mock
    private BenchmarkValueRepository benchmarkValueRepository;
    @Mock
    private BcbClient bcbClient;
    @Mock
    private QuoteService quoteService;
    @Spy
    private MarketCalendar marketCalendar = new MarketCalendar(
            Clock.fixed(TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    @Mock
    private BrapiClient brapiClient;

    @InjectMocks
    private BenchmarkSyncService benchmarkSyncService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(benchmarkSyncService, "brapiToken", "test-token");
    }

    @Test
    void syncRangeUsesSelicSgs11AndCountsOnlyNewRecords() {
        Benchmark selic = benchmark("SELIC", BenchmarkType.INTEREST_RATE, BenchmarkProvider.BCB);
        LocalDate start = TODAY.minusDays(1);
        when(benchmarkRepository.findAll(any(Sort.class))).thenReturn(List.of(selic));
        when(bcbClient.getSeriesData(11, "19/03/2026", "20/03/2026"))
                .thenReturn(List.of(data("19/03/2026", "0.055"), data("20/03/2026", "0.055")));
        when(benchmarkValueRepository.insertIfAbsent(selic.getId(), start, VALUE)).thenReturn(0);
        when(benchmarkValueRepository.insertIfAbsent(selic.getId(), TODAY, VALUE)).thenReturn(1);

        assertThat(benchmarkSyncService.syncRange(start, TODAY))
                .isEqualTo(new BenchmarkSyncResponseDTO(start, TODAY, 1, 1, List.of()));
        verify(benchmarkValueRepository).insertIfAbsent(selic.getId(), start, VALUE);
    }

    @Test
    void syncRangeSplitsBcbRequestsIntoInclusive365DayChunksAndRetriesTransientFailure() {
        Benchmark cdi = benchmark("CDI", BenchmarkType.INTEREST_RATE, BenchmarkProvider.BCB);
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end = LocalDate.of(2025, 1, 2);
        when(benchmarkRepository.findAll(any(Sort.class))).thenReturn(List.of(cdi));
        when(bcbClient.getSeriesData(12, "01/01/2024", "30/12/2024"))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(List.of(data("30/12/2024", "0.055")));
        when(bcbClient.getSeriesData(12, "31/12/2024", "02/01/2025"))
                .thenReturn(List.of(data("31/12/2024", "0.055")));
        when(benchmarkValueRepository.insertIfAbsent(eq(cdi.getId()), any(), eq(VALUE))).thenReturn(1);

        assertThat(benchmarkSyncService.syncRange(start, end))
                .isEqualTo(new BenchmarkSyncResponseDTO(start, end, 1, 2, List.of()));
        var requests = inOrder(bcbClient);
        requests.verify(bcbClient, times(2)).getSeriesData(12, "01/01/2024", "30/12/2024");
        requests.verify(bcbClient).getSeriesData(12, "31/12/2024", "02/01/2025");
        verifyNoMoreInteractions(bcbClient);
    }

    @Test
    void syncRangeReportsExhaustedRetriesAndStillProcessesOtherBenchmarks() {
        Benchmark cdi = benchmark("CDI", BenchmarkType.INTEREST_RATE, BenchmarkProvider.BCB);
        Benchmark selic = benchmark("SELIC", BenchmarkType.INTEREST_RATE, BenchmarkProvider.BCB);
        when(benchmarkRepository.findAll(any(Sort.class))).thenReturn(List.of(cdi, selic));
        when(bcbClient.getSeriesData(12, "20/03/2026", "20/03/2026"))
                .thenThrow(new ResourceAccessException("timeout"));
        when(bcbClient.getSeriesData(11, "20/03/2026", "20/03/2026"))
                .thenReturn(List.of(data("20/03/2026", "0.055")));
        when(benchmarkValueRepository.insertIfAbsent(selic.getId(), TODAY, VALUE)).thenReturn(1);

        BenchmarkSyncResponseDTO result = benchmarkSyncService.syncRange(TODAY, TODAY);

        assertThat(result.benchmarksProcessed()).isEqualTo(2);
        assertThat(result.recordsInserted()).isEqualTo(1);
        assertThat(result.errors()).singleElement().asString().contains("CDI", TODAY.toString());
        verify(bcbClient, times(2)).getSeriesData(12, "20/03/2026", "20/03/2026");
    }

    @Test
    void scheduledSyncBackfillsInternalCdiGapAndFetchesNewDates() {
        Benchmark cdi = benchmark("CDI", BenchmarkType.INTEREST_RATE, BenchmarkProvider.BCB);
        LocalDate first = TODAY.minusDays(4);
        LocalDate gap = TODAY.minusDays(3);
        LocalDate latest = TODAY.minusDays(2);
        when(benchmarkRepository.findAll(any(Sort.class))).thenReturn(List.of(cdi));
        when(benchmarkValueRepository.findEarliestByBenchmark(cdi)).thenReturn(valueAt(cdi, first));
        when(benchmarkValueRepository.findLatestByBenchmark(cdi)).thenReturn(valueAt(cdi, latest));
        when(benchmarkValueRepository.findReferenceDatesByBenchmarkInRange(cdi, first, latest))
                .thenReturn(List.of(first, latest));
        when(bcbClient.getSeriesData(12, "17/03/2026", "17/03/2026"))
                .thenReturn(List.of(data("17/03/2026", "0.055")));
        when(bcbClient.getSeriesData(12, "19/03/2026", "20/03/2026"))
                .thenReturn(List.of(data("19/03/2026", "0.055"), data("20/03/2026", " ")));

        benchmarkSyncService.syncAllBenchmarks();

        var inserts = inOrder(benchmarkValueRepository);
        inserts.verify(benchmarkValueRepository).insertIfAbsent(cdi.getId(), gap, VALUE);
        inserts.verify(benchmarkValueRepository).insertIfAbsent(cdi.getId(), TODAY.minusDays(1), VALUE);
        inserts.verifyNoMoreInteractions();
    }

    @Test
    void scheduledSyncBackfillsMonthsWithoutTreatingIpcaAsDaily() {
        Benchmark ipca = benchmark("IPCA", BenchmarkType.INFLATION, BenchmarkProvider.BCB);
        LocalDate latest = LocalDate.of(2026, 2, 1);
        when(benchmarkRepository.findAll(any(Sort.class))).thenReturn(List.of(ipca));
        when(benchmarkValueRepository.findLatestByBenchmark(ipca)).thenReturn(valueAt(ipca, latest));
        LocalDate firstMonth = LocalDate.of(2025, 3, 1);
        List<LocalDate> publishedMonths = firstMonth.datesUntil(latest.plusDays(1), java.time.Period.ofMonths(1))
                .filter(date -> !date.equals(LocalDate.of(2026, 1, 1)))
                .toList();
        when(benchmarkValueRepository.findReferenceDatesByBenchmarkInRange(ipca, firstMonth, latest.plusDays(27)))
                .thenReturn(publishedMonths);
        when(bcbClient.getSeriesData(433, "01/01/2026", "31/01/2026"))
                .thenReturn(List.of(data("01/01/2026", "0.055")));

        benchmarkSyncService.syncAllBenchmarks();

        verify(benchmarkValueRepository).insertIfAbsent(ipca.getId(), LocalDate.of(2026, 1, 1), VALUE);
        verify(bcbClient).getSeriesData(433, "02/02/2026", "20/03/2026");
        verify(bcbClient).getSeriesData(433, "01/01/2026", "31/01/2026");
        verifyNoMoreInteractions(bcbClient);
    }

    @Test
    void scheduledSyncUsesBrapiHistoryWhenMoreThanOneBusinessDayIsMissing() {
        Benchmark ibov = benchmark("^BVSP", BenchmarkType.INDEX, BenchmarkProvider.BRAPI);
        LocalDate latest = TODAY.minusDays(2);
        when(benchmarkRepository.findAll(any(Sort.class))).thenReturn(List.of(ibov));
        when(benchmarkValueRepository.findLatestByBenchmark(ibov)).thenReturn(valueAt(ibov, latest));
        when(brapiClient.getHistoricalQuote("^BVSP", "1y", "test-token")).thenReturn(
                historicalQuotes(TODAY.plusDays(1), TODAY, TODAY.minusDays(1), latest));

        benchmarkSyncService.syncAllBenchmarks();

        var inserts = inOrder(benchmarkValueRepository);
        inserts.verify(benchmarkValueRepository).insertIfAbsent(ibov.getId(), TODAY.minusDays(1), VALUE);
        inserts.verify(benchmarkValueRepository).insertIfAbsent(ibov.getId(), TODAY, VALUE);
        inserts.verifyNoMoreInteractions();
        verifyNoInteractions(quoteService);
    }

    @Test
    void scheduledSyncSkipsBrapiSpotQuoteWithoutPrice() {
        Benchmark ibov = benchmark("^BVSP", BenchmarkType.INDEX, BenchmarkProvider.BRAPI);
        when(benchmarkRepository.findAll(any(Sort.class))).thenReturn(List.of(ibov));
        when(benchmarkValueRepository.findLatestByBenchmark(ibov)).thenReturn(valueAt(ibov, TODAY.minusDays(1)));
        when(quoteService.getCurrentQuote("^BVSP"))
                .thenReturn(Optional.of(new QuoteDTO("^BVSP", null, null, null, null, null, null)));

        benchmarkSyncService.syncAllBenchmarks();

        verify(quoteService).getCurrentQuote("^BVSP");
        verify(benchmarkValueRepository).findLatestByBenchmark(ibov);
        verifyNoMoreInteractions(benchmarkValueRepository);
        verifyNoInteractions(brapiClient);
    }

    @Test
    void syncRangeAlwaysUsesBrapiHistoryAndFiltersToRequestedDates() {
        Benchmark ibov = benchmark("^BVSP", BenchmarkType.INDEX, BenchmarkProvider.BRAPI);
        LocalDate date = LocalDate.of(2024, 1, 5);
        when(benchmarkRepository.findAll(any(Sort.class))).thenReturn(List.of(ibov));
        when(brapiClient.getHistoricalQuote("^BVSP", "max", "test-token"))
                .thenReturn(historicalQuotes(date.minusDays(1), date, date.plusDays(1)));
        when(benchmarkValueRepository.insertIfAbsent(ibov.getId(), date, VALUE)).thenReturn(1);

        assertThat(benchmarkSyncService.syncRange(date, date))
                .isEqualTo(new BenchmarkSyncResponseDTO(date, date, 1, 1, List.of()));
        verify(benchmarkValueRepository).insertIfAbsent(ibov.getId(), date, VALUE);
        verifyNoMoreInteractions(benchmarkValueRepository);
        verifyNoInteractions(quoteService);
    }

    @Test
    void benchmarkStatusesReportCoverageGapsAndFreshnessUsingTheMarketCalendar() {
        Benchmark cdi = benchmark("CDI", BenchmarkType.INTEREST_RATE, BenchmarkProvider.BCB);
        Benchmark ibov = benchmark("^BVSP", BenchmarkType.INDEX, BenchmarkProvider.BRAPI);
        Benchmark ipca = benchmark("IPCA", BenchmarkType.INFLATION, BenchmarkProvider.BCB);
        LocalDate sunday = TODAY.plusDays(2);
        LocalDate holiday = TODAY.minusDays(1);
        LocalDate windowStart = sunday.minusDays(29);
        LocalDate firstCdi = TODAY.minusMonths(4);
        LocalDate latestIpca = LocalDate.of(2026, 2, 1);

        doReturn(sunday).when(marketCalendar).today();
        doAnswer(invocation -> holiday.equals(invocation.getArgument(0)))
                .when(marketCalendar).isMarketHoliday(any(LocalDate.class));
        when(benchmarkRepository.findAll(any(Sort.class))).thenReturn(List.of(cdi, ibov, ipca));
        when(benchmarkValueRepository.findEarliestByBenchmark(cdi)).thenReturn(valueAt(cdi, firstCdi));
        when(benchmarkValueRepository.findLatestByBenchmark(cdi)).thenReturn(valueAt(cdi, TODAY));
        when(benchmarkValueRepository.countByBenchmark(cdi)).thenReturn(55L);
        when(benchmarkValueRepository.findReferenceDatesByBenchmarkInRange(cdi, windowStart, sunday))
                .thenReturn(List.of(TODAY.minusDays(2), TODAY));
        when(benchmarkValueRepository.findEarliestByBenchmark(ibov)).thenReturn(Optional.empty());
        when(benchmarkValueRepository.findLatestByBenchmark(ibov)).thenReturn(Optional.empty());
        when(benchmarkValueRepository.countByBenchmark(ibov)).thenReturn(0L);
        when(benchmarkValueRepository.findReferenceDatesByBenchmarkInRange(ibov, windowStart, sunday))
                .thenReturn(List.of());
        when(benchmarkValueRepository.findEarliestByBenchmark(ipca)).thenReturn(valueAt(ipca, latestIpca));
        when(benchmarkValueRepository.findLatestByBenchmark(ipca)).thenReturn(valueAt(ipca, latestIpca));
        when(benchmarkValueRepository.countByBenchmark(ipca)).thenReturn(1L);

        assertThat(benchmarkSyncService.getBenchmarkStatuses()).containsExactly(
                new BenchmarkStatusResponseDTO("CDI", "CDI", "INTEREST_RATE", "BCB",
                        firstCdi, TODAY, 55, 17, true),
                new BenchmarkStatusResponseDTO("^BVSP", "^BVSP", "INDEX", "BRAPI",
                        null, null, 0, 19, false),
                new BenchmarkStatusResponseDTO("IPCA", "IPCA", "INFLATION", "BCB",
                        latestIpca, latestIpca, 1, 0, true));
    }

    private Benchmark benchmark(String ticker, BenchmarkType type, BenchmarkProvider provider) {
        Benchmark benchmark = new Benchmark();
        benchmark.setId(UUID.randomUUID());
        benchmark.setTicker(ticker);
        benchmark.setName(ticker);
        benchmark.setType(type);
        benchmark.setProvider(provider);
        return benchmark;
    }

    private Optional<BenchmarkValue> valueAt(Benchmark benchmark, LocalDate date) {
        return Optional.of(new BenchmarkValue(1L, benchmark, date, VALUE));
    }

    private BcbClient.BcbDataDTO data(String date, String value) {
        return new BcbClient.BcbDataDTO(date, value);
    }

    private BrapiResponseDTO historicalQuotes(LocalDate... dates) {
        List<BrapiHistoricalDataDTO> history = java.util.Arrays.stream(dates)
                .map(date -> new BrapiHistoricalDataDTO(date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC), VALUE))
                .toList();
        return new BrapiResponseDTO(List.of(new BrapiResultDTO(
                "^BVSP", null, null, null, null, null, null, null, history)));
    }
}
