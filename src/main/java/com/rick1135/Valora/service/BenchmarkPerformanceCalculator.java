package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkType;
import com.rick1135.Valora.entity.BenchmarkValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BenchmarkPerformanceCalculator {

    private static final int MAX_EDGE_GAP_BUSINESS_DAYS = 2;
    private static final int MAX_INTERNAL_GAP_BUSINESS_DAYS = 2;
    private static final double MIN_BUSINESS_DAY_COVERAGE_RATIO = 0.80;

    private final MarketCalendar marketCalendar;

    public BenchmarkPerformanceCalculator() {
        this(new MarketCalendar(Clock.systemDefaultZone()));
    }

    @Autowired
    public BenchmarkPerformanceCalculator(MarketCalendar marketCalendar) {
        this.marketCalendar = marketCalendar;
    }

    public enum BenchmarkCoverageStatus {
        AVAILABLE,
        MISSING_DATA,
        INSUFFICIENT_DATA,
        INVALID_BASE_VALUE
    }

    public record BenchmarkYieldResult(
            BigDecimal percentage,
            boolean available,
            BenchmarkCoverageStatus status
    ) {
        public static BenchmarkYieldResult available(BigDecimal percentage) {
            return new BenchmarkYieldResult(percentage, true, BenchmarkCoverageStatus.AVAILABLE);
        }

        public static BenchmarkYieldResult unavailable(BenchmarkCoverageStatus status) {
            return new BenchmarkYieldResult(null, false, status);
        }
    }

    /**
     * Calculates the benchmark yield percentage for the given period, returning {@code null}
     * when benchmark data is absent or insufficient so missing coverage is never disguised as 0%.
     */
    public BigDecimal calculateYield(Benchmark benchmark, List<BenchmarkValue> values, LocalDate startDate, LocalDate endDate) {
        return calculateYieldResult(benchmark, values, startDate, endDate).percentage();
    }

    /**
     * Calculates the benchmark yield along with explicit coverage metadata.
     */
    public BenchmarkYieldResult calculateYieldResult(
            Benchmark benchmark,
            List<BenchmarkValue> values,
            LocalDate startDate,
            LocalDate endDate
    ) {
        if (benchmark == null || benchmark.getType() == null) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.MISSING_DATA);
        }

        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
        }

        List<BenchmarkValue> normalizedValues = normalizeValuesInRange(values, startDate, endDate);

        if (normalizedValues.isEmpty()) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.MISSING_DATA);
        }

        if (benchmark.getType() == BenchmarkType.INTEREST_RATE) {
            return calculateInterestRateYield(normalizedValues, startDate, endDate);
        } else if (benchmark.getType() == BenchmarkType.INDEX) {
            return calculateIndexYield(normalizedValues, startDate, endDate);
        } else if (benchmark.getType() == BenchmarkType.INFLATION) {
            return calculateInflationYield(normalizedValues, startDate, endDate);
        }

        return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.MISSING_DATA);
    }

    private List<BenchmarkValue> normalizeValuesInRange(List<BenchmarkValue> values, LocalDate startDate, LocalDate endDate) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(bv -> bv != null && bv.getReferenceDate() != null)
                .filter(bv -> !bv.getReferenceDate().isBefore(startDate) && !bv.getReferenceDate().isAfter(endDate))
                .sorted(Comparator.comparing(BenchmarkValue::getReferenceDate))
                .toList();
    }

    private BenchmarkYieldResult calculateInterestRateYield(
            List<BenchmarkValue> values,
            LocalDate startDate,
            LocalDate endDate
    ) {
        List<BenchmarkValue> validValues = values.stream()
                .filter(bv -> bv.getValue() != null)
                .toList();

        if (validValues.isEmpty()) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.MISSING_DATA);
        }

        long expectedBusinessDays = marketCalendar.businessDaysBetween(startDate, endDate);
        if (expectedBusinessDays == 0) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
        }

        LocalDate firstDate = validValues.get(0).getReferenceDate();
        LocalDate lastDate = validValues.get(validValues.size() - 1).getReferenceDate();

        if (marketCalendar.businessDaysBetween(startDate, firstDate.minusDays(1)) > MAX_EDGE_GAP_BUSINESS_DAYS) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
        }

        if (marketCalendar.businessDaysBetween(lastDate.plusDays(1), endDate) > MAX_EDGE_GAP_BUSINESS_DAYS) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
        }

        for (int i = 1; i < validValues.size(); i++) {
            LocalDate prevDate = validValues.get(i - 1).getReferenceDate();
            LocalDate currDate = validValues.get(i).getReferenceDate();
            if (currDate.isAfter(prevDate.plusDays(1))
                    && marketCalendar.businessDaysBetween(prevDate.plusDays(1), currDate.minusDays(1)) > MAX_INTERNAL_GAP_BUSINESS_DAYS) {
                return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
            }
        }

        Set<LocalDate> coveredBusinessDays = validValues.stream()
                .map(BenchmarkValue::getReferenceDate)
                .filter(marketCalendar::isBusinessDay)
                .collect(Collectors.toSet());

        if (expectedBusinessDays <= 5) {
            if (coveredBusinessDays.isEmpty() || coveredBusinessDays.size() < expectedBusinessDays - 1) {
                return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
            }
        } else if ((double) coveredBusinessDays.size() / expectedBusinessDays < MIN_BUSINESS_DAY_COVERAGE_RATIO) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
        }

        return BenchmarkYieldResult.available(calculateAccumulatedRateYield(validValues));
    }

    private BigDecimal calculateAccumulatedRateYield(List<BenchmarkValue> values) {
        // Accumulate rates using ANBIMA precision (16+ decimal places)
        BigDecimal factor = BigDecimal.ONE.setScale(FinancialConstants.BENCHMARK_PRECISION_SCALE, RoundingMode.HALF_UP);
        BigDecimal hundred = new BigDecimal("100");

        for (BenchmarkValue bv : values) {
            BigDecimal periodRate = bv.getValue().divide(hundred, FinancialConstants.BENCHMARK_PRECISION_SCALE, RoundingMode.HALF_UP);
            factor = factor.multiply(BigDecimal.ONE.add(periodRate));
        }

        return factor.subtract(BigDecimal.ONE)
                .multiply(hundred)
                .setScale(FinancialConstants.PERCENTAGE_SCALE, RoundingMode.HALF_UP);
    }

    private BenchmarkYieldResult calculateIndexYield(List<BenchmarkValue> values, LocalDate startDate, LocalDate endDate) {
        if (values == null || values.isEmpty()) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.MISSING_DATA);
        }
        if (values.size() < 2) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
        }

        BenchmarkValue first = values.get(0);
        BenchmarkValue last = values.get(values.size() - 1);
        BigDecimal startPrice = first.getValue();
        BigDecimal endPrice = last.getValue();

        if (startPrice == null || endPrice == null || startPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INVALID_BASE_VALUE);
        }

        LocalDate firstDate = first.getReferenceDate();
        LocalDate lastDate = last.getReferenceDate();
        if (marketCalendar.businessDaysBetween(startDate, firstDate.minusDays(1)) > MAX_EDGE_GAP_BUSINESS_DAYS
                || marketCalendar.businessDaysBetween(lastDate.plusDays(1), endDate) > MAX_EDGE_GAP_BUSINESS_DAYS) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
        }

        BigDecimal yield = endPrice.divide(startPrice, FinancialConstants.BENCHMARK_PRECISION_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .multiply(new BigDecimal("100"))
                .setScale(FinancialConstants.PERCENTAGE_SCALE, RoundingMode.HALF_UP);

        return BenchmarkYieldResult.available(yield);
    }

    private BenchmarkYieldResult calculateInflationYield(
            List<BenchmarkValue> values,
            LocalDate startDate,
            LocalDate endDate
    ) {
        List<BenchmarkValue> validValues = values.stream()
                .filter(bv -> bv.getValue() != null)
                .toList();

        if (validValues.isEmpty()) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.MISSING_DATA);
        }

        // A monthly rate measures a whole reference month only. Never label a
        // partial month, or a still-unpublished month, as covered.
        if (startDate.getDayOfMonth() != 1 || !endDate.equals(YearMonth.from(endDate).atEndOfMonth())) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
        }

        // IPCA is monthly (referenceDate = 1st of month). Requiring every
        // reference month also accounts for publication delay without guessing a date.
        YearMonth expectedFirstMonth = YearMonth.from(startDate);
        YearMonth expectedLastMonth = YearMonth.from(endDate);

        if (expectedFirstMonth.isAfter(expectedLastMonth)) {
            return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
        }

        Map<YearMonth, BenchmarkValue> byMonth = new HashMap<>();
        for (BenchmarkValue bv : validValues) {
            byMonth.putIfAbsent(YearMonth.from(bv.getReferenceDate()), bv);
        }

        List<BenchmarkValue> monthlyValues = new ArrayList<>();
        YearMonth cursor = expectedFirstMonth;
        while (!cursor.isAfter(expectedLastMonth)) {
            BenchmarkValue monthValue = byMonth.get(cursor);
            if (monthValue == null) {
                return BenchmarkYieldResult.unavailable(BenchmarkCoverageStatus.INSUFFICIENT_DATA);
            }
            monthlyValues.add(monthValue);
            cursor = cursor.plusMonths(1);
        }

        return BenchmarkYieldResult.available(calculateAccumulatedRateYield(monthlyValues));
    }
}
