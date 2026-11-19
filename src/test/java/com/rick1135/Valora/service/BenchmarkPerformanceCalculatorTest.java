package com.rick1135.Valora.service;

import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkType;
import com.rick1135.Valora.entity.BenchmarkValue;
import com.rick1135.Valora.service.BenchmarkPerformanceCalculator.BenchmarkCoverageStatus;
import com.rick1135.Valora.service.BenchmarkPerformanceCalculator.BenchmarkYieldResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkPerformanceCalculatorTest {

    private BenchmarkPerformanceCalculator calculator;

    private Benchmark cdiBenchmark;
    private Benchmark ibovBenchmark;
    private Benchmark ipcaBenchmark;

    @BeforeEach
    void setUp() {
        calculator = new BenchmarkPerformanceCalculator();

        cdiBenchmark = new Benchmark();
        cdiBenchmark.setName("CDI");
        cdiBenchmark.setType(BenchmarkType.INTEREST_RATE);

        ibovBenchmark = new Benchmark();
        ibovBenchmark.setName("Ibovespa");
        ibovBenchmark.setType(BenchmarkType.INDEX);

        ipcaBenchmark = new Benchmark();
        ipcaBenchmark.setName("IPCA");
        ipcaBenchmark.setType(BenchmarkType.INFLATION);
    }

    @Test
    void shouldCalculateCdiYieldWithHighPrecision() {
        // CDI of 0.05% for 2 business days within a 3-business-day window (allowing D-0 not yet published)
        LocalDate start = LocalDate.of(2026, 11, 17); // Tuesday
        LocalDate end = LocalDate.of(2026, 11, 19);   // Thursday
        BenchmarkValue v1 = new BenchmarkValue(1L, cdiBenchmark, start, new BigDecimal("0.05"));
        BenchmarkValue v2 = new BenchmarkValue(2L, cdiBenchmark, start.plusDays(1), new BigDecimal("0.05"));

        BigDecimal yield = calculator.calculateYield(cdiBenchmark, List.of(v1, v2), start, end);

        // Factor = (1.0005) * (1.0005) = 1.00100025
        // Yield = 0.100025% -> rounded to 4 decimals = 0.1000
        assertEquals(new BigDecimal("0.1000"), yield);
    }

    @Test
    void shouldCalculateLongTermCdiPrecision() {
        List<BenchmarkValue> values = new ArrayList<>();
        BigDecimal dailyRate = new BigDecimal("0.0475"); // Approx 12% p.a.
        for (int i = 0; i < 252; i++) {
            values.add(new BenchmarkValue((long) i, cdiBenchmark, LocalDate.now().minusDays(252 - i), dailyRate));
        }

        BigDecimal yield = calculator.calculateYield(cdiBenchmark, values, LocalDate.now().minusDays(252), LocalDate.now());

        // (1 + 0.000475)^252 - 1
        // 1.000475^252 = 1.127127...
        // Yield approx 12.7127%
        assertEquals(new BigDecimal("12.7127"), yield);
    }

    @Test
    void shouldReturnMissingDataForShortIpcaIntervalWithoutFallbackToPriorMonth() {
        LocalDate start = LocalDate.of(2026, 11, 10);
        LocalDate end = LocalDate.of(2026, 11, 15);
        List<BenchmarkValue> priorMonthValues = List.of(
                new BenchmarkValue(1L, ipcaBenchmark, LocalDate.of(2026, 10, 1), new BigDecimal("0.56"))
        );

        BenchmarkYieldResult result = calculator.calculateYieldResult(ipcaBenchmark, priorMonthValues, start, end);

        assertFalse(result.available());
        assertEquals(BenchmarkCoverageStatus.MISSING_DATA, result.status());
        assertNull(result.percentage());
    }

    @Test
    void shouldReturnNullInsteadOfMisleadingZeroWhenCoverageIsAbsent() {
        LocalDate start = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now();

        BenchmarkYieldResult cdiResult = calculator.calculateYieldResult(cdiBenchmark, List.of(), start, end);
        BenchmarkYieldResult ibovEmptyResult = calculator.calculateYieldResult(ibovBenchmark, List.of(), start, end);
        BenchmarkYieldResult ipcaResult = calculator.calculateYieldResult(ipcaBenchmark, List.of(), start, end);

        assertFalse(cdiResult.available());
        assertEquals(BenchmarkCoverageStatus.MISSING_DATA, cdiResult.status());
        assertNull(cdiResult.percentage());

        assertFalse(ibovEmptyResult.available());
        assertEquals(BenchmarkCoverageStatus.MISSING_DATA, ibovEmptyResult.status());
        assertNull(ibovEmptyResult.percentage());

        assertFalse(ipcaResult.available());
        assertEquals(BenchmarkCoverageStatus.MISSING_DATA, ipcaResult.status());
        assertNull(ipcaResult.percentage());
    }

    @Test
    void shouldReturnNullWhenIndexHasInsufficientDataOrZeroBasePrice() {
        LocalDate start = LocalDate.now().minusDays(5);
        LocalDate end = LocalDate.now();

        BenchmarkValue single = new BenchmarkValue(1L, ibovBenchmark, start, new BigDecimal("130000"));
        BenchmarkYieldResult singlePointResult = calculator.calculateYieldResult(ibovBenchmark, List.of(single), start, end);

        assertFalse(singlePointResult.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, singlePointResult.status());
        assertNull(singlePointResult.percentage());

        BenchmarkValue zeroStart = new BenchmarkValue(1L, ibovBenchmark, start, BigDecimal.ZERO);
        BenchmarkValue endPoint = new BenchmarkValue(2L, ibovBenchmark, end, new BigDecimal("130000"));
        BenchmarkYieldResult zeroBaseResult = calculator.calculateYieldResult(ibovBenchmark, List.of(zeroStart, endPoint), start, end);

        assertFalse(zeroBaseResult.available());
        assertEquals(BenchmarkCoverageStatus.INVALID_BASE_VALUE, zeroBaseResult.status());
        assertNull(zeroBaseResult.percentage());
    }

    @Test
    void shouldCalculateIndexYieldWhenTwoOrMoreValidPointsExist() {
        LocalDate start = LocalDate.now().minusDays(5);
        LocalDate end = LocalDate.now();

        BenchmarkValue startPoint = new BenchmarkValue(1L, ibovBenchmark, start, new BigDecimal("100000"));
        BenchmarkValue endPoint = new BenchmarkValue(2L, ibovBenchmark, end, new BigDecimal("110000"));

        BenchmarkYieldResult result = calculator.calculateYieldResult(ibovBenchmark, List.of(startPoint, endPoint), start, end);

        assertTrue(result.available());
        assertEquals(BenchmarkCoverageStatus.AVAILABLE, result.status());
        assertEquals(new BigDecimal("10.0000"), result.percentage());
    }

    @Test
    void shouldReturnInsufficientDataWhenCdiMissingAtStart() {
        LocalDate start = LocalDate.of(2026, 11, 2); // Monday
        LocalDate end = LocalDate.of(2026, 11, 13);  // Friday (10 business days)

        // Missing first 3 business days (Nov 2, 3, 4)
        List<BenchmarkValue> values = buildDailyBusinessValues(cdiBenchmark, LocalDate.of(2026, 11, 5), end, new BigDecimal("0.05"));

        BenchmarkYieldResult result = calculator.calculateYieldResult(cdiBenchmark, values, start, end);

        assertFalse(result.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, result.status());
        assertNull(result.percentage());
    }

    @Test
    void shouldReturnInsufficientDataWhenCdiMissingInMiddle() {
        LocalDate start = LocalDate.of(2026, 11, 2); // Monday
        LocalDate end = LocalDate.of(2026, 11, 13);  // Friday

        // Missing 3 consecutive business days in the middle (Nov 5, 6, 9)
        List<BenchmarkValue> values = new ArrayList<>();
        values.addAll(buildDailyBusinessValues(cdiBenchmark, start, LocalDate.of(2026, 11, 4), new BigDecimal("0.05")));
        values.addAll(buildDailyBusinessValues(cdiBenchmark, LocalDate.of(2026, 11, 10), end, new BigDecimal("0.05")));

        BenchmarkYieldResult result = calculator.calculateYieldResult(cdiBenchmark, values, start, end);

        assertFalse(result.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, result.status());
        assertNull(result.percentage());
    }

    @Test
    void shouldReturnInsufficientDataWhenCdiMissingAtEnd() {
        LocalDate start = LocalDate.of(2026, 11, 2); // Monday
        LocalDate end = LocalDate.of(2026, 11, 13);  // Friday

        // Missing last 3 business days (Nov 11, 12, 13)
        List<BenchmarkValue> values = buildDailyBusinessValues(cdiBenchmark, start, LocalDate.of(2026, 11, 10), new BigDecimal("0.05"));

        BenchmarkYieldResult result = calculator.calculateYieldResult(cdiBenchmark, values, start, end);

        assertFalse(result.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, result.status());
        assertNull(result.percentage());
    }

    @Test
    void shouldAllowCdiHolidayAndCurrentDayLagWithinTolerance() {
        LocalDate start = LocalDate.of(2026, 11, 2); // Monday
        LocalDate end = LocalDate.of(2026, 11, 13);  // Friday (10 business days)

        // 1 holiday in middle (Nov 6) + 1 day lag at end (Nov 13) -> 8/10 = 80% covered
        List<BenchmarkValue> values = buildDailyBusinessValues(cdiBenchmark, start, LocalDate.of(2026, 11, 12), new BigDecimal("0.05"))
                .stream()
                .filter(bv -> !bv.getReferenceDate().equals(LocalDate.of(2026, 11, 6)))
                .toList();

        BenchmarkYieldResult result = calculator.calculateYieldResult(cdiBenchmark, values, start, end);

        assertTrue(result.available());
        assertEquals(BenchmarkCoverageStatus.AVAILABLE, result.status());
        assertTrue(result.percentage().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void shouldReturnInsufficientDataWhenIndexMissingAtStartOrEnd() {
        LocalDate start = LocalDate.of(2026, 10, 1); // Thursday
        LocalDate end = LocalDate.of(2026, 10, 30);  // Friday

        // Missing start boundary (first point 5 business days after start)
        List<BenchmarkValue> missingStart = List.of(
                new BenchmarkValue(1L, ibovBenchmark, LocalDate.of(2026, 10, 8), new BigDecimal("100000")),
                new BenchmarkValue(2L, ibovBenchmark, end, new BigDecimal("105000"))
        );
        BenchmarkYieldResult startResult = calculator.calculateYieldResult(ibovBenchmark, missingStart, start, end);
        assertFalse(startResult.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, startResult.status());
        assertNull(startResult.percentage());

        // Missing end boundary (last point 5 business days before end)
        List<BenchmarkValue> missingEnd = List.of(
                new BenchmarkValue(1L, ibovBenchmark, start, new BigDecimal("100000")),
                new BenchmarkValue(2L, ibovBenchmark, LocalDate.of(2026, 10, 23), new BigDecimal("105000"))
        );
        BenchmarkYieldResult endResult = calculator.calculateYieldResult(ibovBenchmark, missingEnd, start, end);
        assertFalse(endResult.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, endResult.status());
        assertNull(endResult.percentage());
    }

    @Test
    void shouldCalculateMultiMonthIpcaWhenEveryFullMonthIsAvailable() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 3, 31);

        List<BenchmarkValue> values = List.of(
                new BenchmarkValue(1L, ipcaBenchmark, LocalDate.of(2026, 1, 1), new BigDecimal("0.50")),
                new BenchmarkValue(2L, ipcaBenchmark, LocalDate.of(2026, 2, 1), new BigDecimal("0.40")),
                new BenchmarkValue(3L, ipcaBenchmark, LocalDate.of(2026, 3, 1), new BigDecimal("0.30"))
        );

        BenchmarkYieldResult result = calculator.calculateYieldResult(ipcaBenchmark, values, start, end);

        assertTrue(result.available());
        assertEquals(BenchmarkCoverageStatus.AVAILABLE, result.status());
        assertEquals(new BigDecimal("1.2047"), result.percentage());
    }

    @Test
    void shouldReturnUnavailableWhenLastFullIpcaMonthIsNotPublished() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 3, 31);

        List<BenchmarkValue> values = List.of(
                new BenchmarkValue(1L, ipcaBenchmark, LocalDate.of(2026, 1, 1), new BigDecimal("0.50")),
                new BenchmarkValue(2L, ipcaBenchmark, LocalDate.of(2026, 2, 1), new BigDecimal("0.40"))
        );

        BenchmarkYieldResult result = calculator.calculateYieldResult(ipcaBenchmark, values, start, end);

        assertFalse(result.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, result.status());
        assertNull(result.percentage());
    }

    @Test
    void shouldReturnInsufficientDataWhenIpcaMissingAtStartMiddleOrEnd() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 3, 31);

        // Missing start (Jan absent)
        List<BenchmarkValue> missingStart = List.of(
                new BenchmarkValue(2L, ipcaBenchmark, LocalDate.of(2026, 2, 1), new BigDecimal("0.40")),
                new BenchmarkValue(3L, ipcaBenchmark, LocalDate.of(2026, 3, 1), new BigDecimal("0.30"))
        );
        BenchmarkYieldResult startResult = calculator.calculateYieldResult(ipcaBenchmark, missingStart, start, end);
        assertFalse(startResult.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, startResult.status());
        assertNull(startResult.percentage());

        // Missing middle (Feb absent)
        List<BenchmarkValue> missingMiddle = List.of(
                new BenchmarkValue(1L, ipcaBenchmark, LocalDate.of(2026, 1, 1), new BigDecimal("0.50")),
                new BenchmarkValue(3L, ipcaBenchmark, LocalDate.of(2026, 3, 1), new BigDecimal("0.30"))
        );
        BenchmarkYieldResult middleResult = calculator.calculateYieldResult(ipcaBenchmark, missingMiddle, start, end);
        assertFalse(middleResult.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, middleResult.status());
        assertNull(middleResult.percentage());

        // Missing end (Mar absent)
        List<BenchmarkValue> missingEnd = List.of(
                new BenchmarkValue(1L, ipcaBenchmark, LocalDate.of(2026, 1, 1), new BigDecimal("0.50")),
                new BenchmarkValue(2L, ipcaBenchmark, LocalDate.of(2026, 2, 1), new BigDecimal("0.40"))
        );
        BenchmarkYieldResult endResult = calculator.calculateYieldResult(ipcaBenchmark, missingEnd, start, end);
        assertFalse(endResult.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, endResult.status());
        assertNull(endResult.percentage());
    }

    @Test
    void shouldNotUseSingleMonthlyIpcaFallbackForLongerOrArbitraryPeriod() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2026, 6, 20);

        BenchmarkYieldResult result = calculator.calculateYieldResult(ipcaBenchmark, List.of(), start, end);

        assertFalse(result.available());
        assertEquals(BenchmarkCoverageStatus.MISSING_DATA, result.status());
        assertNull(result.percentage());
    }

    @Test
    void shouldReturnInsufficientDataForPartialIpcaIntervals() {
        // Partial start month: Jan 15 to Apr 20 cannot be measured with monthly IPCA
        List<BenchmarkValue> febAndMar = List.of(
                new BenchmarkValue(2L, ipcaBenchmark, LocalDate.of(2026, 2, 1), new BigDecimal("0.40")),
                new BenchmarkValue(3L, ipcaBenchmark, LocalDate.of(2026, 3, 1), new BigDecimal("0.30"))
        );
        BenchmarkYieldResult partialStart = calculator.calculateYieldResult(
                ipcaBenchmark, febAndMar, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 20));
        assertFalse(partialStart.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, partialStart.status());
        assertNull(partialStart.percentage());

        // Intra-month interval: Jan 1 to Jan 20 has no published full-month coverage
        List<BenchmarkValue> janOnly = List.of(
                new BenchmarkValue(1L, ipcaBenchmark, LocalDate.of(2026, 1, 1), new BigDecimal("0.50"))
        );
        BenchmarkYieldResult intraMonth = calculator.calculateYieldResult(
                ipcaBenchmark, janOnly, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20));
        assertFalse(intraMonth.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, intraMonth.status());
        assertNull(intraMonth.percentage());

        // End date before publication cutoff with only start month present: Mar 1 to Apr 10
        List<BenchmarkValue> marOnly = List.of(
                new BenchmarkValue(1L, ipcaBenchmark, LocalDate.of(2026, 3, 1), new BigDecimal("0.30"))
        );
        BenchmarkYieldResult beforeCutoff = calculator.calculateYieldResult(
                ipcaBenchmark, marOnly, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 10));
        assertFalse(beforeCutoff.available());
        assertEquals(BenchmarkCoverageStatus.INSUFFICIENT_DATA, beforeCutoff.status());
        assertNull(beforeCutoff.percentage());
    }

    private List<BenchmarkValue> buildDailyBusinessValues(
            Benchmark benchmark,
            LocalDate from,
            LocalDate to,
            BigDecimal rate
    ) {
        List<BenchmarkValue> list = new ArrayList<>();
        long id = 1L;
        LocalDate current = from;
        while (!current.isAfter(to)) {
            if (current.getDayOfWeek().getValue() <= 5) {
                list.add(new BenchmarkValue(id++, benchmark, current, rate));
            }
            current = current.plusDays(1);
        }
        return list;
    }
}
