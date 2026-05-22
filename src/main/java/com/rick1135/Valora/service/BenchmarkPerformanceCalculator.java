package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkType;
import com.rick1135.Valora.entity.BenchmarkValue;
import com.rick1135.Valora.repository.BenchmarkValueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BenchmarkPerformanceCalculator {

    private final BenchmarkValueRepository benchmarkValueRepository;

    public BigDecimal calculateYield(Benchmark benchmark, List<BenchmarkValue> values, LocalDate startDate, LocalDate endDate) {
        if ((values == null || values.isEmpty()) && benchmark.getType() != BenchmarkType.INFLATION) {
            return BigDecimal.ZERO;
        }

        if (benchmark.getType() == BenchmarkType.INTEREST_RATE) {
            return calculateInterestRateYield(values);
        } else if (benchmark.getType() == BenchmarkType.INDEX) {
            return calculateIndexYield(values);
        } else if (benchmark.getType() == BenchmarkType.INFLATION) {
            return calculateInflationYield(benchmark, values, startDate, endDate);
        }

        return BigDecimal.ZERO;
    }

    private BigDecimal calculateInterestRateYield(List<BenchmarkValue> values) {
        // Accumulate daily rates using ANBIMA precision (16+ decimal places)
        BigDecimal factor = BigDecimal.ONE.setScale(FinancialConstants.BENCHMARK_PRECISION_SCALE, RoundingMode.HALF_UP);
        BigDecimal hundred = new BigDecimal("100");

        for (BenchmarkValue bv : values) {
            BigDecimal dailyRate = bv.getValue().divide(hundred, FinancialConstants.BENCHMARK_PRECISION_SCALE, RoundingMode.HALF_UP);
            factor = factor.multiply(BigDecimal.ONE.add(dailyRate));
        }

        return factor.subtract(BigDecimal.ONE)
                .multiply(hundred)
                .setScale(FinancialConstants.PERCENTAGE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateIndexYield(List<BenchmarkValue> values) {
        if (values.size() < 2) return BigDecimal.ZERO;
        
        BigDecimal startPrice = values.get(0).getValue();
        BigDecimal endPrice = values.get(values.size() - 1).getValue();
        
        if (startPrice.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        return endPrice.divide(startPrice, FinancialConstants.BENCHMARK_PRECISION_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .multiply(new BigDecimal("100"))
                .setScale(FinancialConstants.PERCENTAGE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateInflationYield(Benchmark benchmark, List<BenchmarkValue> values, LocalDate startDate, LocalDate endDate) {
        // Fallback Strategy (Lookback): If no values in range, look for the most recent monthly value
        if (values == null || values.isEmpty()) {
            return benchmarkValueRepository.findLatestBeforeOrAt(benchmark, endDate)
                    .map(BenchmarkValue::getValue)
                    .orElse(BigDecimal.ZERO)
                    .setScale(FinancialConstants.PERCENTAGE_SCALE, RoundingMode.HALF_UP);
        }

        // Standard accumulation for monthly inflation indices
        BigDecimal factor = BigDecimal.ONE.setScale(FinancialConstants.BENCHMARK_PRECISION_SCALE, RoundingMode.HALF_UP);
        BigDecimal hundred = new BigDecimal("100");

        for (BenchmarkValue bv : values) {
            BigDecimal monthlyRate = bv.getValue().divide(hundred, FinancialConstants.BENCHMARK_PRECISION_SCALE, RoundingMode.HALF_UP);
            factor = factor.multiply(BigDecimal.ONE.add(monthlyRate));
        }

        return factor.subtract(BigDecimal.ONE)
                .multiply(hundred)
                .setScale(FinancialConstants.PERCENTAGE_SCALE, RoundingMode.HALF_UP);
    }
}
