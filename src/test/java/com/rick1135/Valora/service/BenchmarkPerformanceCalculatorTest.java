package com.rick1135.Valora.service;

import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkType;
import com.rick1135.Valora.entity.BenchmarkValue;
import com.rick1135.Valora.repository.BenchmarkValueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BenchmarkPerformanceCalculatorTest {

    @Mock
    private BenchmarkValueRepository benchmarkValueRepository;

    @InjectMocks
    private BenchmarkPerformanceCalculator calculator;

    private Benchmark cdiBenchmark;
    private Benchmark ibovBenchmark;
    private Benchmark ipcaBenchmark;

    @BeforeEach
    void setUp() {
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
        // CDI of 0.05% for 2 days
        BenchmarkValue v1 = new BenchmarkValue(1L, cdiBenchmark, LocalDate.now().minusDays(2), new BigDecimal("0.05"));
        BenchmarkValue v2 = new BenchmarkValue(2L, cdiBenchmark, LocalDate.now().minusDays(1), new BigDecimal("0.05"));

        BigDecimal yield = calculator.calculateYield(cdiBenchmark, List.of(v1, v2), LocalDate.now().minusDays(2), LocalDate.now());

        // Factor = (1.0005) * (1.0005) = 1.00100025
        // Yield = 0.100025% -> rounded to 4 decimals = 0.1000
        assertEquals(new BigDecimal("0.1000"), yield);
    }

    @Test
    void shouldCalculateLongTermCdiPrecision() {
        List<BenchmarkValue> values = new ArrayList<>();
        BigDecimal dailyRate = new BigDecimal("0.0475"); // Approx 12% p.a.
        for (int i = 0; i < 252; i++) {
            values.add(new BenchmarkValue((long)i, cdiBenchmark, LocalDate.now().minusDays(252-i), dailyRate));
        }

        BigDecimal yield = calculator.calculateYield(cdiBenchmark, values, LocalDate.now().minusDays(252), LocalDate.now());
        
        // (1 + 0.000475)^252 - 1
        // 1.000475^252 = 1.127127...
        // Yield approx 12.7127%
        assertEquals(new BigDecimal("12.7127"), yield);
    }

    @Test
    void shouldFallbackForIpcaWhenNoDataInRange() {
        LocalDate start = LocalDate.now().minusDays(5);
        LocalDate end = LocalDate.now();
        
        when(benchmarkValueRepository.findLatestBeforeOrAt(eq(ipcaBenchmark), any()))
                .thenReturn(Optional.of(new BenchmarkValue(1L, ipcaBenchmark, LocalDate.now().minusMonths(1), new BigDecimal("0.56"))));

        BigDecimal yield = calculator.calculateYield(ipcaBenchmark, List.of(), start, end);

        assertEquals(new BigDecimal("0.5600"), yield);
    }

    @Test
    void shouldReturnZeroForEmptyPortfolioGracefully() {
        BigDecimal yield = calculator.calculateYield(ibovBenchmark, List.of(), LocalDate.now(), LocalDate.now());
        assertEquals(BigDecimal.ZERO, yield);
    }
}
