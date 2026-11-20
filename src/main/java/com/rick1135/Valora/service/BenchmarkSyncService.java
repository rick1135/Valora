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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenchmarkSyncService {
    private static final DateTimeFormatter BCB_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int BCB_CHUNK_DAYS = 365;

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkValueRepository benchmarkValueRepository;
    private final BcbClient bcbClient;
    private final QuoteService quoteService;
    private final MarketCalendar marketCalendar;
    private final BrapiClient brapiClient;

    @Value("${brapi.token:}")
    private String brapiToken;

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void syncAllBenchmarks() {
        LocalDate today = marketCalendar.today();
        List<String> errors = new ArrayList<>();
        for (Benchmark benchmark : benchmarkRepository.findAll(Sort.by("ticker"))) {
            LocalDate latest = findLatestDate(benchmark);
            if (benchmark.getProvider() == BenchmarkProvider.BCB) {
                syncBcbAutomatically(benchmark, latest, today, errors);
            } else {
                syncBrapiAutomatically(benchmark, latest, today, errors);
            }
        }
    }

    @Transactional
    public BenchmarkSyncResponseDTO syncRange(LocalDate startDate, LocalDate endDate) {
        LocalDate today = marketCalendar.today();
        LocalDate end = endDate == null ? today : endDate;
        LocalDate start = startDate == null ? end.minusYears(1) : startDate;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate deve ser anterior ou igual a endDate.");
        }

        List<String> errors = new ArrayList<>();
        List<Benchmark> benchmarks = benchmarkRepository.findAll(Sort.by("ticker"));
        int inserted = 0;
        for (Benchmark benchmark : benchmarks) {
            inserted += switch (benchmark.getProvider()) {
                case BCB -> syncBcbRange(benchmark, start, end, errors);
                case BRAPI -> syncBrapiHistorical(benchmark, start, end, today, errors);
            };
        }
        return new BenchmarkSyncResponseDTO(start, end, benchmarks.size(), inserted, List.copyOf(errors));
    }

    @Transactional(readOnly = true)
    public List<BenchmarkStatusResponseDTO> getBenchmarkStatuses() {
        LocalDate today = marketCalendar.today();
        LocalDate start = today.minusDays(29);
        LocalDate latestBusinessDay = lastBusinessDayOnOrBefore(today);
        LocalDate latestInflationMonth = YearMonth.from(today).minusMonths(1).atDay(1);
        return benchmarkRepository.findAll(Sort.by("ticker")).stream()
                .map(benchmark -> {
                    LocalDate earliest = findEarliestDate(benchmark);
                    LocalDate latest = findLatestDate(benchmark);
                    boolean daily = benchmark.getType() != BenchmarkType.INFLATION;
                    LocalDate expected = daily ? latestBusinessDay : latestInflationMonth;
                    long missingDays = daily ? missingBusinessDays(benchmark, start, today).size() : 0;
                    return new BenchmarkStatusResponseDTO(
                            benchmark.getTicker(), benchmark.getName(), benchmark.getType().name(),
                            benchmark.getProvider().name(), earliest, latest,
                            benchmarkValueRepository.countByBenchmark(benchmark), missingDays,
                            latest != null && !latest.isBefore(expected));
                })
                .toList();
    }

    private void syncBcbAutomatically(Benchmark benchmark, LocalDate latest, LocalDate today, List<String> errors) {
        LocalDate start = today.minusYears(1);
        if (latest != null) {
            backfillBcb(benchmark, latest, today, errors);
            if (!latest.isBefore(today)) {
                return;
            }
            start = latest.plusDays(1);
        }
        syncBcbRange(benchmark, start, today, errors);
    }

    private void backfillBcb(Benchmark benchmark, LocalDate latest, LocalDate today, List<String> errors) {
        List<LocalDate> missing;
        LocalDate end;
        if (benchmark.getType() == BenchmarkType.INFLATION) {
            YearMonth firstMonth = YearMonth.from(today).minusMonths(12);
            YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
            if (YearMonth.from(latest).isBefore(lastMonth)) {
                lastMonth = YearMonth.from(latest);
            }
            missing = missingMonths(benchmark, firstMonth, lastMonth);
            if (missing.isEmpty()) {
                return;
            }
            end = YearMonth.from(missing.getLast()).atEndOfMonth();
        } else {
            LocalDate earliest = findEarliestDate(benchmark);
            LocalDate start = today.minusDays(89);
            if (earliest == null) {
                return;
            }
            if (earliest.isAfter(start)) {
                start = earliest;
            }
            end = latest.isBefore(today) ? latest : today;
            missing = missingBusinessDays(benchmark, start, end);
            if (missing.isEmpty()) {
                return;
            }
            end = missing.getLast();
        }
        syncBcbRange(benchmark, missing.getFirst(), end, errors);
    }

    private List<LocalDate> missingMonths(Benchmark benchmark, YearMonth start, YearMonth end) {
        if (start.isAfter(end)) {
            return List.of();
        }
        Set<YearMonth> present = benchmarkValueRepository
                .findReferenceDatesByBenchmarkInRange(benchmark, start.atDay(1), end.atEndOfMonth())
                .stream().map(YearMonth::from).collect(Collectors.toSet());
        return Stream.iterate(start, month -> !month.isAfter(end), month -> month.plusMonths(1))
                .filter(month -> !present.contains(month))
                .map(month -> month.atDay(1))
                .toList();
    }

    private List<LocalDate> missingBusinessDays(Benchmark benchmark, LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            return List.of();
        }
        Set<LocalDate> present = new HashSet<>(
                benchmarkValueRepository.findReferenceDatesByBenchmarkInRange(benchmark, start, end));
        return start.datesUntil(end.plusDays(1))
                .filter(this::isTradingDay)
                .filter(date -> !present.contains(date))
                .toList();
    }

    private int syncBcbRange(Benchmark benchmark, LocalDate start, LocalDate end, List<String> errors) {
        int seriesCode;
        try {
            seriesCode = getSeriesCode(benchmark.getTicker());
        } catch (IllegalArgumentException exception) {
            recordError(errors, exception.getMessage());
            return 0;
        }

        int inserted = 0;
        LocalDate chunkStart = start;
        while (true) {
            LocalDate chunkEnd = ChronoUnit.DAYS.between(chunkStart, end) >= BCB_CHUNK_DAYS
                    ? chunkStart.plusDays(BCB_CHUNK_DAYS - 1) : end;
            inserted += syncBcbChunk(benchmark, seriesCode, chunkStart, chunkEnd, errors);
            if (chunkEnd.equals(end)) {
                return inserted;
            }
            chunkStart = chunkEnd.plusDays(1);
        }
    }

    private int syncBcbChunk(Benchmark benchmark, int seriesCode, LocalDate start, LocalDate end, List<String> errors) {
        List<BcbClient.BcbDataDTO> data;
        try {
            data = fetchBcbData(seriesCode, start, end);
        } catch (RestClientException exception) {
            recordError(errors, "Falha ao consultar BCB para %s de %s a %s."
                    .formatted(benchmark.getTicker(), start, end));
            return 0;
        }

        if (data == null || data.isEmpty()) {
            if (isTradingDay(end)) {
                log.warn("Nenhum dado retornado pelo BCB para {} de {} a {}.", benchmark.getTicker(), start, end);
            }
            return 0;
        }

        Map<LocalDate, BigDecimal> values = new TreeMap<>();
        for (BcbClient.BcbDataDTO dto : data) {
            if (dto == null || dto.getData() == null || dto.getData().isBlank()
                    || dto.getValor() == null || dto.getValor().isBlank()) {
                continue;
            }
            try {
                LocalDate date = LocalDate.parse(dto.getData().trim(), BCB_DATE_FORMATTER);
                if (!date.isBefore(start) && !date.isAfter(end)) {
                    values.putIfAbsent(date, new BigDecimal(dto.getValor().trim()));
                }
            } catch (DateTimeParseException | NumberFormatException exception) {
                recordError(errors, "Dado invalido retornado pelo BCB para %s de %s a %s."
                        .formatted(benchmark.getTicker(), start, end));
            }
        }
        return insertValues(benchmark, values);
    }

    private List<BcbClient.BcbDataDTO> fetchBcbData(int seriesCode, LocalDate start, LocalDate end) {
        String formattedStart = start.format(BCB_DATE_FORMATTER);
        String formattedEnd = end.format(BCB_DATE_FORMATTER);
        try {
            return bcbClient.getSeriesData(seriesCode, formattedStart, formattedEnd);
        } catch (RestClientException exception) {
            if (!isTransientBcbFailure(exception)) {
                throw exception;
            }
            return bcbClient.getSeriesData(seriesCode, formattedStart, formattedEnd);
        }
    }

    private boolean isTransientBcbFailure(RestClientException exception) {
        if (exception instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return response.getStatusCode().is5xxServerError() || status == 408 || status == 429;
        }
        return exception instanceof ResourceAccessException;
    }

    private void syncBrapiAutomatically(Benchmark benchmark, LocalDate latest, LocalDate today, List<String> errors) {
        LocalDate end = lastBusinessDayOnOrBefore(today);
        if (latest == null) {
            syncBrapiHistorical(benchmark, today.minusYears(1), end, today, errors);
        } else if (latest.isBefore(end)) {
            LocalDate start = latest.plusDays(1);
            boolean hasEarlierMissingDay = start.datesUntil(end).anyMatch(this::isTradingDay);
            if (hasEarlierMissingDay || !end.equals(today)) {
                syncBrapiHistorical(benchmark, start, end, today, errors);
            } else {
                quoteService.getCurrentQuote(benchmark.getTicker())
                        .map(QuoteDTO::price)
                        .ifPresent(price -> benchmarkValueRepository.insertIfAbsent(benchmark.getId(), today, price));
            }
        }
    }

    private int syncBrapiHistorical(
            Benchmark benchmark, LocalDate start, LocalDate end, LocalDate today, List<String> errors
    ) {
        if (brapiToken == null || brapiToken.isBlank()) {
            recordError(errors, "Token da BRAPI nao configurado para " + benchmark.getTicker() + ".");
            return 0;
        }

        String range = start.isBefore(today.minusYears(1)) ? "max" : "1y";
        BrapiResponseDTO response;
        try {
            response = brapiClient.getHistoricalQuote(benchmark.getTicker(), range, brapiToken);
        } catch (RestClientException exception) {
            recordError(errors, "Falha ao consultar o historico da BRAPI para %s de %s a %s."
                    .formatted(benchmark.getTicker(), start, end));
            return 0;
        }
        if (response == null || response.results() == null || response.results().isEmpty()) {
            return 0;
        }
        BrapiResultDTO result = response.results().getFirst();
        if (result == null || result.historicalDataPrice() == null) {
            return 0;
        }

        Map<LocalDate, BigDecimal> values = new TreeMap<>();
        for (BrapiHistoricalDataDTO historical : result.historicalDataPrice()) {
            if (historical == null || historical.date() == null || historical.close() == null) {
                continue;
            }
            LocalDate date = marketCalendar.marketDate(Instant.ofEpochSecond(historical.date()));
            if (!date.isBefore(start) && !date.isAfter(end)) {
                values.putIfAbsent(date, historical.close());
            }
        }
        return insertValues(benchmark, values);
    }

    private int insertValues(Benchmark benchmark, Map<LocalDate, BigDecimal> values) {
        int inserted = 0;
        for (var entry : values.entrySet()) {
            inserted += benchmarkValueRepository.insertIfAbsent(benchmark.getId(), entry.getKey(), entry.getValue());
        }
        return inserted;
    }

    private LocalDate findEarliestDate(Benchmark benchmark) {
        return benchmarkValueRepository.findEarliestByBenchmark(benchmark)
                .map(BenchmarkValue::getReferenceDate).orElse(null);
    }

    private LocalDate findLatestDate(Benchmark benchmark) {
        return benchmarkValueRepository.findLatestByBenchmark(benchmark)
                .map(BenchmarkValue::getReferenceDate).orElse(null);
    }

    private LocalDate lastBusinessDayOnOrBefore(LocalDate date) {
        while (!isTradingDay(date)) {
            date = date.minusDays(1);
        }
        return date;
    }

    private boolean isTradingDay(LocalDate date) {
        return marketCalendar.isBusinessDay(date) && !marketCalendar.isMarketHoliday(date);
    }

    private void recordError(List<String> errors, String message) {
        errors.add(message);
        log.warn("{}", message);
    }

    private int getSeriesCode(String ticker) {
        return switch (ticker) {
            case "CDI" -> 12;
            case "SELIC" -> 11;
            case "IPCA" -> 433;
            default -> throw new IllegalArgumentException("Ticker BCB desconhecido: " + ticker);
        };
    }
}
