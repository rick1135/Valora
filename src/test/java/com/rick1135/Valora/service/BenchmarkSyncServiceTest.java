package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BcbClient;
import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.response.QuoteDTO;
import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkProvider;
import com.rick1135.Valora.entity.BenchmarkType;
import com.rick1135.Valora.entity.BenchmarkValue;
import com.rick1135.Valora.repository.BenchmarkRepository;
import com.rick1135.Valora.repository.BenchmarkValueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BenchmarkSyncServiceTest {

    @Mock
    private BenchmarkRepository benchmarkRepository;
    @Mock
    private BenchmarkValueRepository benchmarkValueRepository;
    @Mock
    private BcbClient bcbClient;
    @Mock
    private QuoteService quoteService;
    @Mock
    private MarketCalendar marketCalendar;
    @Mock
    private BrapiClient brapiClient;

    @InjectMocks
    private BenchmarkSyncService benchmarkSyncService;

    @Test
    void syncBcbBenchmarksShouldBeIdempotentAndIgnoreBlankEntries() {
        Benchmark cdi = new Benchmark();
        cdi.setId(java.util.UUID.randomUUID());
        cdi.setTicker("CDI");
        cdi.setName("CDI");
        cdi.setProvider(BenchmarkProvider.BCB);
        cdi.setType(BenchmarkType.INTEREST_RATE);

        LocalDate today = LocalDate.of(2026, 3, 20);
        LocalDate lastStored = LocalDate.of(2026, 3, 18);

        when(marketCalendar.today()).thenReturn(today);
        when(benchmarkRepository.findByProvider(BenchmarkProvider.BCB)).thenReturn(List.of(cdi));
        when(benchmarkRepository.findByProvider(BenchmarkProvider.BRAPI)).thenReturn(List.of());
        when(benchmarkValueRepository.findLatestByBenchmark(cdi))
                .thenReturn(Optional.of(new BenchmarkValue(1L, cdi, lastStored, new BigDecimal("0.05"))));

        when(bcbClient.getSeriesData(12, "19/03/2026", "20/03/2026"))
                .thenReturn(List.of(
                        new BcbClient.BcbDataDTO("19/03/2026", "0.055"),
                        new BcbClient.BcbDataDTO("20/03/2026", " ")
                ));

        when(benchmarkValueRepository.findByBenchmarkAndReferenceDate(cdi, LocalDate.of(2026, 3, 19)))
                .thenReturn(Optional.empty());

        benchmarkSyncService.syncAllBenchmarks();

        verify(benchmarkValueRepository).save(any(BenchmarkValue.class));
        verify(benchmarkValueRepository, never()).findByBenchmarkAndReferenceDate(cdi, LocalDate.of(2026, 3, 20));
    }

    @Test
    void syncBrapiBenchmarksShouldSkipWhenQuotePriceIsNull() {
        Benchmark ibov = new Benchmark();
        ibov.setId(java.util.UUID.randomUUID());
        ibov.setTicker("^BVSP");
        ibov.setName("Ibovespa");
        ibov.setProvider(BenchmarkProvider.BRAPI);
        ibov.setType(BenchmarkType.INDEX);

        LocalDate today = LocalDate.of(2026, 3, 20);
        LocalDate yesterday = LocalDate.of(2026, 3, 19);

        when(marketCalendar.today()).thenReturn(today);
        when(benchmarkRepository.findByProvider(BenchmarkProvider.BCB)).thenReturn(List.of());
        when(benchmarkRepository.findByProvider(BenchmarkProvider.BRAPI)).thenReturn(List.of(ibov));
        when(benchmarkValueRepository.findLatestByBenchmark(ibov))
                .thenReturn(Optional.of(new BenchmarkValue(1L, ibov, yesterday, new BigDecimal("130000"))));
        when(quoteService.getCurrentQuote("^BVSP"))
                .thenReturn(Optional.of(new QuoteDTO("^BVSP", null, null, null, null, null, null)));

        benchmarkSyncService.syncAllBenchmarks();

        verify(benchmarkValueRepository, never()).save(any(BenchmarkValue.class));
    }
}
