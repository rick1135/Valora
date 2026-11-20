package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.response.PortfolioHistoryResponseDTO;
import com.rick1135.Valora.dto.response.PortfolioHistoryResponseDTO.BenchmarkPointDTO;
import com.rick1135.Valora.dto.response.PortfolioHistoryResponseDTO.HistoryPointDTO;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkValue;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.PortfolioDailySnapshot;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.repository.BenchmarkRepository;
import com.rick1135.Valora.repository.BenchmarkValueRepository;
import com.rick1135.Valora.repository.PortfolioDailySnapshotRepository;
import com.rick1135.Valora.repository.PortfolioDailySnapshotRepository.SnapshotTrade;
import com.rick1135.Valora.repository.PortfolioRepository;
import com.rick1135.Valora.service.PortfolioTwrCalculator.DailyObservation;
import com.rick1135.Valora.service.PortfolioTwrCalculator.DailyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class PortfolioSnapshotService {
    private static final Logger logger = LoggerFactory.getLogger(PortfolioSnapshotService.class);
    private static final List<String> BENCHMARK_TICKERS = List.of("CDI", "SELIC", "^BVSP", "IPCA");
    private static final int CAPTURE_ATTEMPTS = 3;
    private static final BigDecimal ZERO_PERCENTAGE = BigDecimal.ZERO.setScale(FinancialConstants.PERCENTAGE_SCALE);

    // Match the position timeline's ordering for transactions with the same timestamp.
    private static final Comparator<Trade> TRANSACTION_ORDER = Comparator
            .comparing(Trade::transactionDate)
            .thenComparing(Trade::type)
            .thenComparing(Trade::quantity)
            .thenComparing(Trade::unitPrice)
            .thenComparing(Trade::id);

    private final PortfolioRepository portfolioRepository;
    private final PortfolioDailySnapshotRepository snapshotRepository;
    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkValueRepository benchmarkValueRepository;
    private final MarketCalendar marketCalendar;
    private final BrapiClient brapiClient;
    private final PortfolioTwrCalculator twrCalculator;
    private final TransactionTemplate captureTransaction;
    private final String brapiToken;

    public PortfolioSnapshotService(
            PortfolioRepository portfolioRepository,
            PortfolioDailySnapshotRepository snapshotRepository,
            BenchmarkRepository benchmarkRepository,
            BenchmarkValueRepository benchmarkValueRepository,
            MarketCalendar marketCalendar,
            BrapiClient brapiClient,
            PortfolioTwrCalculator twrCalculator,
            PlatformTransactionManager transactionManager,
            @Value("${brapi.token:}") String brapiToken
    ) {
        this.portfolioRepository = portfolioRepository;
        this.snapshotRepository = snapshotRepository;
        this.benchmarkRepository = benchmarkRepository;
        this.benchmarkValueRepository = benchmarkValueRepository;
        this.marketCalendar = marketCalendar;
        this.brapiClient = brapiClient;
        this.twrCalculator = twrCalculator;
        this.captureTransaction = new TransactionTemplate(transactionManager);
        this.brapiToken = brapiToken;
    }

    public PortfolioHistoryResponseDTO getPortfolioHistory(User user, UUID portfolioId, String range) {
        HistoryRange historyRange = HistoryRange.parse(range);
        Portfolio portfolio = portfolioRepository.findByIdAndUser(portfolioId, user)
                .orElseThrow(PortfolioSnapshotService::portfolioNotFound);
        LocalDate today = marketCalendar.today();
        LocalDate end = marketCalendar.lastBusinessDayOnOrBefore(today);
        LocalDate start = historyRange.startDate(today);
        rebuildMissingSnapshots(portfolio, end, false);
        List<PortfolioDailySnapshot> snapshots;
        if (start == null) {
            snapshots = snapshotRepository.findByPortfolioOrderBySnapshotDateAsc(portfolio);
        } else if (start.isAfter(end)) {
            snapshots = List.of();
        } else {
            snapshots = snapshotRepository.findByPortfolioAndSnapshotDateBetweenOrderBySnapshotDateAsc(portfolio, start, end);
        }
        if (snapshots.isEmpty()) {
            return new PortfolioHistoryResponseDTO(historyRange.label, start, end, ZERO_PERCENTAGE, true, List.of(), Map.of());
        }

        LocalDate firstPointDate = snapshots.getFirst().getSnapshotDate();
        var previous = snapshotRepository.findFirstByPortfolioAndSnapshotDateLessThanOrderBySnapshotDateDesc(
                portfolio, firstPointDate);
        BigDecimal startQuota = previous.map(PortfolioDailySnapshot::getTwrQuotaValue)
                .filter(quota -> quota.signum() > 0).orElse(BigDecimal.ONE);
        LocalDate baseDate = previous.map(PortfolioDailySnapshot::getSnapshotDate)
                .orElseGet(() -> marketCalendar.previousBusinessDay(firstPointDate));
        List<HistoryPointDTO> points = snapshots.stream()
                .map(snapshot -> new HistoryPointDTO(
                        snapshot.getSnapshotDate(), snapshot.getTotalPatrimony(), snapshot.getTotalInvested(),
                        twrCalculator.calculatePeriodTwrPercentage(startQuota, snapshot.getTwrQuotaValue()),
                        snapshot.isPricingComplete()))
                .toList();
        var benchmarks = loadBenchmarks(baseDate, firstPointDate, end);
        return new PortfolioHistoryResponseDTO(
                historyRange.label, firstPointDate, end, points.getLast().twrPercentage(),
                points.stream().allMatch(HistoryPointDTO::pricingComplete), points,
                benchmarks.points(), benchmarks.complete()
        );
    }

    public record PortfolioTwrSummary(BigDecimal twrPercentage, boolean reliable) {}

    @Transactional(readOnly = true)
    public Optional<PortfolioTwrSummary> getPortfolioTwrSummary(Portfolio portfolio) {
        return snapshotRepository.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio)
                .map(snapshot -> new PortfolioTwrSummary(
                        twrCalculator.calculatePeriodTwrPercentage(BigDecimal.ONE, snapshot.getTwrQuotaValue()),
                        !snapshotRepository.existsByPortfolioAndPricingCompleteFalse(portfolio)));
    }

    @Transactional(readOnly = true)
    public Optional<BigDecimal> getPortfolioTwrPercentage(Portfolio portfolio) {
        return getPortfolioTwrSummary(portfolio).map(PortfolioTwrSummary::twrPercentage);
    }

    @Transactional
    public void invalidateSnapshotsFrom(Portfolio portfolio, LocalDate fromDate) {
        if (portfolio != null && fromDate != null && snapshotRepository.lockPortfolio(portfolio.getId()).isPresent()) {
            snapshotRepository.deleteByPortfolioAndSnapshotDateGreaterThanEqual(portfolio, fromDate);
        }
    }

    @Scheduled(cron = "0 30 22 * * MON-FRI", zone = "America/Sao_Paulo")
    public void captureDailySnapshotsForAllPortfolios() {
        LocalDate today = marketCalendar.today();
        LocalDate end = marketCalendar.lastBusinessDayOnOrBefore(today);
        for (Portfolio portfolio : portfolioRepository.findAll()) {
            try {
                // Intraday history may have captured today's prices before the final close.
                rebuildMissingSnapshots(portfolio, end, end.equals(today));
            } catch (RuntimeException exception) {
                logger.error("Falha ao capturar snapshots da carteira {}", portfolio.getId(), exception);
            }
        }
    }

    private void rebuildMissingSnapshots(Portfolio portfolio, LocalDate end, boolean refreshEnd) {
        for (int attempt = 0; attempt < CAPTURE_ATTEMPTS; attempt++) {
            SnapshotBoundary boundary = findBoundary(portfolio, end, refreshEnd);
            if (boundary.replaceFrom() == null && boundary.previous() != null
                    && !boundary.previous().date().isBefore(end)) {
                return;
            }
            CaptureInputs inputs = loadCaptureInputs(portfolio, end);
            if (inputs.trades().isEmpty()) {
                return;
            }
            List<PortfolioDailySnapshot> missing = calculateSnapshots(portfolio, end, boundary, inputs);
            if (missing.isEmpty()) {
                return;
            }
            boolean saved = Boolean.TRUE.equals(captureTransaction.execute(status -> {
                if (snapshotRepository.lockPortfolio(portfolio.getId()).isEmpty()) {
                    throw portfolioNotFound();
                }
                // A mutation or another capture may have changed the suffix during the HTTP fetch.
                if (!boundary.equals(findBoundary(portfolio, end, refreshEnd))
                        || !inputs.equals(loadCaptureInputs(portfolio, end))) {
                    return false;
                }
                if (boundary.replaceFrom() != null) {
                    snapshotRepository.deleteByPortfolioAndSnapshotDateGreaterThanEqual(portfolio, boundary.replaceFrom());
                }
                snapshotRepository.saveAll(missing);
                return true;
            }));
            if (saved) {
                return;
            }
        }
        throw new ConcurrentModificationException("A carteira mudou durante a captura dos snapshots.");
    }

    private SnapshotBoundary findBoundary(Portfolio portfolio, LocalDate end, boolean refreshEnd) {
        LocalDate replaceFrom = snapshotRepository
                .findFirstByPortfolioAndPricingCompleteFalseOrderBySnapshotDateAsc(portfolio)
                .map(PortfolioDailySnapshot::getSnapshotDate)
                .filter(date -> !date.isAfter(end))
                .orElse(null);
        Optional<PortfolioDailySnapshot> latest = snapshotRepository.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio);
        if (replaceFrom == null && refreshEnd && latest.isPresent() && !latest.get().getSnapshotDate().isBefore(end)) {
            replaceFrom = end;
        }
        var previous = replaceFrom == null ? latest : snapshotRepository
                .findFirstByPortfolioAndSnapshotDateLessThanOrderBySnapshotDateDesc(portfolio, replaceFrom);
        return new SnapshotBoundary(replaceFrom, previous.map(SnapshotAnchor::from).orElse(null),
                latest.map(SnapshotAnchor::from).orElse(null));
    }

    private CaptureInputs loadCaptureInputs(Portfolio portfolio, LocalDate end) {
        Instant until = marketCalendar.endOfMarketDay(end).plusNanos(1);
        List<Trade> trades = snapshotRepository.findSnapshotTrades(portfolio, until).stream()
                .map(Trade::from).sorted(TRANSACTION_ORDER).toList();
        NavigableMap<LocalDate, BigDecimal> paidByDate = new TreeMap<>();
        if (!trades.isEmpty()) {
            LocalDate firstTrade = marketCalendar.marketDate(trades.getFirst().transactionDate());
            for (var total : snapshotRepository.findSnapshotPaidProvents(portfolio, firstTrade, end)) {
                paidByDate.put(total.getPaymentDate(), total.getNetAmount());
            }
        }
        return new CaptureInputs(trades, paidByDate);
    }

    private List<PortfolioDailySnapshot> calculateSnapshots(
            Portfolio portfolio, LocalDate end, SnapshotBoundary boundary, CaptureInputs inputs
    ) {
        List<Trade> trades = inputs.trades();
        SnapshotAnchor previous = boundary.previous();
        LocalDate lastCapturedDate = previous == null ? null : previous.date();
        LocalDate start = boundary.replaceFrom();
        if (start == null) {
            start = lastCapturedDate == null ? marketCalendar.marketDate(trades.getFirst().transactionDate())
                    : lastCapturedDate.plusDays(1);
        }
        while (!start.isAfter(end) && !marketCalendar.isBusinessDay(start)) {
            start = start.plusDays(1);
        }

        NavigableMap<LocalDate, BigDecimal> provents = new TreeMap<>(inputs.paidProvents());
        if (lastCapturedDate != null) {
            provents.headMap(lastCapturedDate, true).clear();
        }

        Map<SnapshotAsset, Holding> holdings = new HashMap<>();
        Map<String, NavigableMap<LocalDate, BigDecimal>> historicalPrices = new HashMap<>();
        List<DailyObservation> observations = new ArrayList<>();
        int transactionIndex = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (!marketCalendar.isBusinessDay(date)) {
                continue;
            }

            BigDecimal inflows = BigDecimal.ZERO;
            BigDecimal outflows = BigDecimal.ZERO;
            while (transactionIndex < trades.size()) {
                Trade trade = trades.get(transactionIndex);
                LocalDate transactionDate = marketCalendar.marketDate(trade.transactionDate());
                if (transactionDate.isAfter(date)) {
                    break;
                }
                Holding holding = holdings.computeIfAbsent(trade.asset(), ignored -> new Holding());
                BigDecimal amount = holding.apply(trade);
                if (lastCapturedDate == null || transactionDate.isAfter(lastCapturedDate)) {
                    if (trade.type() == TransactionType.BUY) {
                        inflows = inflows.add(amount);
                    } else {
                        outflows = outflows.add(amount);
                    }
                }
                transactionIndex++;
            }

            BigDecimal proventsReceived = provents.headMap(date, true).values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            provents.headMap(date, true).clear();
            observations.add(observe(date, inflows, outflows, proventsReceived, holdings, historicalPrices, start));
            lastCapturedDate = date;
        }

        BigDecimal previousPatrimony = previous == null ? BigDecimal.ZERO : previous.patrimony();
        BigDecimal previousQuota = previous == null ? BigDecimal.ONE : previous.quota();
        return twrCalculator.calculate(observations, previousPatrimony, previousQuota)
                .stream().map(result -> toSnapshot(portfolio, result)).toList();
    }

    private record SnapshotBoundary(LocalDate replaceFrom, SnapshotAnchor previous, SnapshotAnchor latest) {}

    private record SnapshotAnchor(Long id, LocalDate date, BigDecimal patrimony, BigDecimal quota) {
        private static SnapshotAnchor from(PortfolioDailySnapshot snapshot) {
            return new SnapshotAnchor(snapshot.getId(), snapshot.getSnapshotDate(),
                    snapshot.getTotalPatrimony(), snapshot.getTwrQuotaValue());
        }
    }

    private record CaptureInputs(List<Trade> trades, NavigableMap<LocalDate, BigDecimal> paidProvents) {}

    private record SnapshotAsset(UUID id, String ticker, AssetCategory category) {}

    private record Trade(UUID id, Instant transactionDate, TransactionType type, BigDecimal quantity, BigDecimal unitPrice,
                         SnapshotAsset asset) {
        private static Trade from(SnapshotTrade trade) {
            return new Trade(trade.getId(), trade.getTransactionDate(), trade.getType(), trade.getQuantity(),
                    trade.getUnitPrice(), new SnapshotAsset(trade.getAssetId(), trade.getTicker(), trade.getCategory()));
        }
    }

    private DailyObservation observe(
            LocalDate date,
            BigDecimal inflows,
            BigDecimal outflows,
            BigDecimal provents,
            Map<SnapshotAsset, Holding> holdings,
            Map<String, NavigableMap<LocalDate, BigDecimal>> historicalPrices,
            LocalDate start
    ) {
        BigDecimal totalPatrimony = BigDecimal.ZERO;
        BigDecimal totalInvested = BigDecimal.ZERO;
        boolean pricingComplete = true;
        for (var entry : holdings.entrySet()) {
            SnapshotAsset asset = entry.getKey();
            Holding holding = entry.getValue();
            if (holding.quantity.signum() <= 0) {
                continue;
            }
            BigDecimal cost = holding.quantity.multiply(holding.averagePrice);
            totalInvested = totalInvested.add(cost);
            Map.Entry<LocalDate, BigDecimal> close = asset.category() == AssetCategory.RENDA_FIXA
                    ? null
                    : historicalPrices.computeIfAbsent(asset.ticker(), ticker -> loadHistoricalPrices(ticker, start))
                            .floorEntry(date);
            if (close == null) {
                pricingComplete = false;
                totalPatrimony = totalPatrimony.add(cost);
            } else {
                pricingComplete &= close.getKey().equals(date);
                totalPatrimony = totalPatrimony.add(holding.quantity.multiply(close.getValue()));
            }
        }
        return new DailyObservation(date, money(totalPatrimony), money(totalInvested), inflows, outflows,
                money(provents), pricingComplete);
    }

    private NavigableMap<LocalDate, BigDecimal> loadHistoricalPrices(String ticker, LocalDate start) {
        NavigableMap<LocalDate, BigDecimal> prices = new TreeMap<>();
        if (brapiToken == null || brapiToken.isBlank()) {
            return prices;
        }

        try {
            String range = start.isBefore(marketCalendar.today().minusYears(1).plusDays(1)) ? "max" : "1y";
            BrapiResponseDTO response = brapiClient.getHistoricalQuote(ticker, range, brapiToken);
            if (response != null && response.results() != null && !response.results().isEmpty()) {
                var result = response.results().getFirst();
                if (result != null && result.historicalDataPrice() != null) {
                    for (var price : result.historicalDataPrice()) {
                        if (price != null && price.date() != null && price.close() != null && price.close().signum() > 0) {
                            LocalDate date = marketCalendar.marketDate(Instant.ofEpochSecond(price.date()));
                            prices.put(date, price.close());
                        }
                    }
                }
            }
        } catch (RestClientException exception) {
            logger.warn("Falha ao consultar fechamentos historicos de {}", ticker, exception);
        }
        return prices;
    }

    private BenchmarkHistory loadBenchmarks(LocalDate baseDate, LocalDate firstPointDate, LocalDate end) {
        Map<String, List<BenchmarkPointDTO>> points = new LinkedHashMap<>();
        Map<String, Boolean> complete = new LinkedHashMap<>();
        for (String ticker : BENCHMARK_TICKERS) {
            BenchmarkSeries series = benchmarkRepository.findByTicker(ticker)
                    .map(benchmark -> switch (benchmark.getType()) {
                        case INDEX -> indexSeries(benchmark, baseDate, firstPointDate, end);
                        case INTEREST_RATE -> interestRateSeries(benchmark, firstPointDate, end);
                        case INFLATION -> inflationSeries(benchmark, firstPointDate, end);
                    })
                    .orElseGet(() -> new BenchmarkSeries(List.of(), false));
            points.put(ticker, series.points());
            complete.put(ticker, series.complete());
        }
        return new BenchmarkHistory(points, complete);
    }

    private BenchmarkSeries indexSeries(Benchmark benchmark, LocalDate baseDate, LocalDate start, LocalDate end) {
        var base = benchmarkValueRepository.findLatestBeforeOrAt(benchmark, baseDate)
                .filter(value -> value.getValue() != null && value.getValue().signum() > 0)
                .filter(value -> marketCalendar.businessDaysBetween(value.getReferenceDate().plusDays(1), baseDate) <= 2);
        if (base.isEmpty()) {
            return new BenchmarkSeries(List.of(), false);
        }

        NavigableMap<LocalDate, BigDecimal> closes = new TreeMap<>();
        for (BenchmarkValue value : benchmarkValueRepository.findByBenchmarkInRange(benchmark, start, end)) {
            if (value.getValue() != null && value.getValue().signum() > 0
                    && marketCalendar.isBusinessDay(value.getReferenceDate())) {
                closes.put(value.getReferenceDate(), value.getValue());
            }
        }
        List<BenchmarkPointDTO> points = closes.entrySet().stream()
                .map(close -> new BenchmarkPointDTO(close.getKey(),
                        twrCalculator.calculatePeriodTwrPercentage(base.get().getValue(), close.getValue())))
                .toList();
        return new BenchmarkSeries(points, closes.size() == marketCalendar.businessDaysBetween(start, end));
    }

    private BenchmarkSeries interestRateSeries(Benchmark benchmark, LocalDate start, LocalDate end) {
        NavigableMap<LocalDate, BigDecimal> rates = new TreeMap<>();
        for (BenchmarkValue value : benchmarkValueRepository.findByBenchmarkInRange(benchmark, start, end)) {
            if (value.getValue() != null && marketCalendar.isBusinessDay(value.getReferenceDate())) {
                rates.put(value.getReferenceDate(), value.getValue());
            }
        }
        // Only the final business day's rate may still be awaiting publication.
        LocalDate lastBusinessDay = marketCalendar.lastBusinessDayOnOrBefore(end);
        long expectedBeforeLast = marketCalendar.businessDaysBetween(start, lastBusinessDay.minusDays(1));
        boolean complete = !rates.isEmpty() && rates.headMap(lastBusinessDay, false).size() == expectedBeforeLast;
        return new BenchmarkSeries(compoundRates(rates), complete);
    }

    private BenchmarkSeries inflationSeries(Benchmark benchmark, LocalDate start, LocalDate end) {
        LocalDate firstReferenceDate = start.withDayOfMonth(1);
        Map<YearMonth, BigDecimal> ratesByMonth = new HashMap<>();
        for (BenchmarkValue value : benchmarkValueRepository.findByBenchmarkInRange(benchmark, firstReferenceDate, end)) {
            if (value.getValue() != null) {
                ratesByMonth.putIfAbsent(YearMonth.from(value.getReferenceDate()), value.getValue());
            }
        }

        boolean complete = start.equals(firstReferenceDate);
        YearMonth month = YearMonth.from(complete ? start : start.plusMonths(1));
        NavigableMap<LocalDate, BigDecimal> closedMonthRates = new TreeMap<>();
        while (!month.atEndOfMonth().isAfter(end)) {
            BigDecimal rate = ratesByMonth.get(month);
            if (rate == null) {
                complete = false;
            } else {
                closedMonthRates.put(month.atEndOfMonth(), rate);
            }
            month = month.plusMonths(1);
        }
        return new BenchmarkSeries(compoundRates(closedMonthRates), complete);
    }

    private List<BenchmarkPointDTO> compoundRates(NavigableMap<LocalDate, BigDecimal> rates) {
        List<BenchmarkPointDTO> points = new ArrayList<>();
        BigDecimal factor = BigDecimal.ONE;
        for (var rate : rates.entrySet()) {
            factor = factor.multiply(BigDecimal.ONE.add(rate.getValue().movePointLeft(2)))
                    .setScale(FinancialConstants.BENCHMARK_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
            points.add(new BenchmarkPointDTO(rate.getKey(),
                    twrCalculator.calculatePeriodTwrPercentage(BigDecimal.ONE, factor)));
        }
        return List.copyOf(points);
    }

    private record BenchmarkHistory(Map<String, List<BenchmarkPointDTO>> points, Map<String, Boolean> complete) {}

    private record BenchmarkSeries(List<BenchmarkPointDTO> points, boolean complete) {}

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
    }

    private static PortfolioDailySnapshot toSnapshot(Portfolio portfolio, DailyResult result) {
        DailyObservation observation = result.observation();
        return new PortfolioDailySnapshot(null, portfolio, observation.date(), observation.totalPatrimony(),
                observation.totalInvested(), money(observation.inflows().subtract(observation.outflows())),
                observation.proventsReceived(),
                result.dailyReturnFactor(), result.quota(), observation.pricingComplete());
    }

    private static PortfolioNotFoundException portfolioNotFound() {
        return new PortfolioNotFoundException("Carteira nao encontrada.");
    }

    private static class Holding {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal averagePrice = BigDecimal.ZERO;

        private BigDecimal apply(Trade trade) {
            BigDecimal tradedQuantity = trade.quantity();
            BigDecimal amount = tradedQuantity.multiply(trade.unitPrice());
            if (trade.type() == TransactionType.BUY) {
                BigDecimal cost = quantity.multiply(averagePrice).add(amount);
                quantity = quantity.add(tradedQuantity);
                averagePrice = cost.divide(quantity,
                        FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
                return amount;
            }

            quantity = quantity.subtract(tradedQuantity);
            if (quantity.signum() == 0) {
                averagePrice = BigDecimal.ZERO;
            }
            return amount;
        }
    }

    private enum HistoryRange {
        ONE_MONTH("1M"), THREE_MONTHS("3M"), SIX_MONTHS("6M"), ONE_YEAR("1Y"), YTD("YTD"), ALL("ALL");

        private final String label;

        HistoryRange(String label) {
            this.label = label;
        }

        private static HistoryRange parse(String range) {
            if (range == null || range.isBlank()) {
                return ALL;
            }
            String normalizedRange = range.trim().toUpperCase(Locale.ROOT);
            for (HistoryRange value : values()) {
                if (value.label.equals(normalizedRange)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Intervalo invalido. Use 1M, 3M, 6M, 1Y, YTD ou ALL.");
        }

        private LocalDate startDate(LocalDate today) {
            return switch (this) {
                case ONE_MONTH -> today.minusMonths(1);
                case THREE_MONTHS -> today.minusMonths(3);
                case SIX_MONTHS -> today.minusMonths(6);
                case ONE_YEAR -> today.minusYears(1);
                case YTD -> today.withDayOfYear(1);
                case ALL -> null;
            };
        }
    }
}
