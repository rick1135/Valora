package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BcbClient;
import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkProvider;
import com.rick1135.Valora.entity.BenchmarkValue;
import com.rick1135.Valora.repository.BenchmarkRepository;
import com.rick1135.Valora.repository.BenchmarkValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenchmarkSyncService {
    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkValueRepository benchmarkValueRepository;
    private final BcbClient bcbClient;
    private final QuoteService quoteService;
    private final MarketCalendar marketCalendar;
    private final BrapiClient brapiClient;

    private static final DateTimeFormatter BCB_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Value("${brapi.token:}")
    private String brapiToken;

    @Scheduled(cron = "0 0 2 * * *") // Every day at 2 AM
    @Transactional
    public void syncAllBenchmarks() {
        log.info("Iniciando sincronizacao de benchmarks...");
        syncBcbBenchmarks();
        syncBrapiBenchmarks();
        log.info("Sincronizacao de benchmarks concluida.");
    }

    private void syncBcbBenchmarks() {
        List<Benchmark> bcbBenchmarks = benchmarkRepository.findByProvider(BenchmarkProvider.BCB);
        for (Benchmark benchmark : bcbBenchmarks) {
            try {
                int seriesCode = getSeriesCode(benchmark.getTicker());
                LocalDate lastStoredDate = benchmarkValueRepository.findLatestByBenchmark(benchmark)
                        .map(BenchmarkValue::getReferenceDate)
                        .orElse(LocalDate.now().minusYears(1));

                checkSyncDelay(benchmark, lastStoredDate);

                LocalDate startDate = lastStoredDate.plusDays(1);
                LocalDate endDate = LocalDate.now();

                if (startDate.isAfter(endDate)) continue;

                List<BcbClient.BcbDataDTO> data = bcbClient.getSeriesData(
                        seriesCode,
                        startDate.format(BCB_DATE_FORMATTER),
                        endDate.format(BCB_DATE_FORMATTER)
                );

                if (data != null && !data.isEmpty()) {
                    for (BcbClient.BcbDataDTO dto : data) {
                        LocalDate date = LocalDate.parse(dto.getData(), BCB_DATE_FORMATTER);
                        BigDecimal value = new BigDecimal(dto.getValor());
                        saveValue(benchmark, date, value);
                    }
                } else {
                    // If no data, but it's a weekend/holiday, we can assume it's normal but we don't advance the stored date.
                    // However, we don't want to log error for weekends.
                    if (marketCalendar.isMarketHoliday(endDate) || !marketCalendar.isBusinessDay(endDate)) {
                        log.debug("Nenhum dado retornado para {} em dia nao util.", benchmark.getTicker());
                    }
                }
            } catch (Exception e) {
                log.error("Erro ao sincronizar benchmark BCB {}: {}", benchmark.getTicker(), e.getMessage());
            }
        }
    }

    private void syncBrapiBenchmarks() {
        List<Benchmark> brapiBenchmarks = benchmarkRepository.findByProvider(BenchmarkProvider.BRAPI);
        for (Benchmark benchmark : brapiBenchmarks) {
            try {
                LocalDate lastStoredDate = benchmarkValueRepository.findLatestByBenchmark(benchmark)
                        .map(BenchmarkValue::getReferenceDate)
                        .orElse(null);

                if (lastStoredDate == null) {
                    syncBrapiHistorical(benchmark);
                } else {
                    checkSyncDelay(benchmark, lastStoredDate);
                    // Only sync if last stored date is before today
                    if (lastStoredDate.isBefore(LocalDate.now())) {
                        Optional<QuoteDTO> quote = quoteService.getCurrentQuote(benchmark.getTicker());
                        quote.ifPresent(q -> saveValue(benchmark, LocalDate.now(), q.price()));
                    }
                }
            } catch (Exception e) {
                log.error("Erro ao sincronizar benchmark BRAPI {}: {}", benchmark.getTicker(), e.getMessage());
            }
        }
    }

    private void checkSyncDelay(Benchmark benchmark, LocalDate lastStoredDate) {
        if (lastStoredDate != null && lastStoredDate.isBefore(LocalDate.now().minusDays(5))) {
            log.warn("ALERTA: O benchmark {} esta sem atualizacoes desde {}. Verifique a estabilidade da API {}!",
                    benchmark.getTicker(), lastStoredDate, benchmark.getProvider());
        }
    }

    private void syncBrapiHistorical(Benchmark benchmark) {
        if (brapiToken == null || brapiToken.isBlank()) return;

        BrapiResponseDTO response = brapiClient.getHistoricalQuote(benchmark.getTicker(), "1y", brapiToken);
        if (response != null && !response.results().isEmpty()) {
            BrapiResultDTO result = response.results().get(0);
            if (result.historicalDataPrice() != null) {
                for (com.rick1135.Valora.dto.brapi.BrapiHistoricalDataDTO historical : result.historicalDataPrice()) {
                    LocalDate date = java.time.Instant.ofEpochSecond(historical.date())
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate();
                    saveValue(benchmark, date, historical.close());
                }
            }
        }
    }

    private void saveValue(Benchmark benchmark, LocalDate date, BigDecimal value) {
        if (benchmarkValueRepository.findByBenchmarkAndReferenceDate(benchmark, date).isEmpty()) {
            BenchmarkValue bv = new BenchmarkValue();
            bv.setBenchmark(benchmark);
            bv.setReferenceDate(date);
            bv.setValue(value);
            benchmarkValueRepository.save(bv);
        }
    }

    private LocalDate getStartDateForSync(Benchmark benchmark) {
        return benchmarkValueRepository.findLatestByBenchmark(benchmark)
                .map(bv -> bv.getReferenceDate().plusDays(1))
                .orElse(LocalDate.now().minusYears(1)); // Default to 1 year back if empty
    }

    private int getSeriesCode(String ticker) {
        return switch (ticker) {
            case "CDI" -> 12;
            case "IPCA" -> 433;
            default -> throw new IllegalArgumentException("Ticker BCB desconhecido: " + ticker);
        };
    }
}
