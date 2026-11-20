package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.response.PortfolioHistoryResponseDTO;
import com.rick1135.Valora.dto.response.PortfolioHistoryResponseDTO.BenchmarkPointDTO;
import com.rick1135.Valora.dto.response.PortfolioHistoryResponseDTO.HistoryPointDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.BenchmarkType;
import com.rick1135.Valora.entity.BenchmarkValue;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.PortfolioDailySnapshot;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.repository.BenchmarkRepository;
import com.rick1135.Valora.repository.BenchmarkValueRepository;
import com.rick1135.Valora.repository.PortfolioDailySnapshotRepository;
import com.rick1135.Valora.repository.PortfolioRepository;
import com.rick1135.Valora.repository.ProventProvisionRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import com.rick1135.Valora.service.PortfolioTwrCalculator.DailyObservation;
import com.rick1135.Valora.service.PortfolioTwrCalculator.DailyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final BigDecimal ZERO_PERCENTAGE = BigDecimal.ZERO.setScale(FinancialConstants.PERCENTAGE_SCALE);

    // Match the position timeline's ordering for transactions with the same timestamp.
    private static final Comparator<Transaction> TRANSACTION_ORDER = Comparator
            .comparing(Transaction::getTransactionDate)
            .thenComparing(Transaction::getType)
            .thenComparing(Transaction::getQuantity)
            .thenComparing(Transaction::getUnitPrice)
            .thenComparing(Transaction::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final PortfolioRepository portfolioRepository;
    private final PortfolioDailySnapshotRepository snapshotRepository;
    private final TransactionRepository transactionRepository;
    private final ProventProvisionRepository proventProvisionRepository;
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
            TransactionRepository transactionRepository,
            ProventProvisionRepository proventProvisionRepository,
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
        this.transactionRepository = transactionRepository;
        this.proventProvisionRepository = proventProvisionRepository;
        this.benchmarkRepository = benchmarkRepository;
        this.benchmarkValueRepository = benchmarkValueRepository;
        this.marketCalendar = marketCalendar;
        this.brapiClient = brapiClient;
        this.twrCalculator = twrCalculator;
        this.captureTransaction = new TransactionTemplate(transactionManager);
        this.brapiToken = brapiToken;
    }

    @Transactional
    public PortfolioHistoryResponseDTO getPortfolioHistory(User user, UUID portfolioId, String range) {
        HistoryRange historyRange = HistoryRange.parse(range);
        Portfolio portfolio = portfolioRepository.findByIdAndUser(portfolioId, user)
                .orElseThrow(PortfolioSnapshotService::portfolioNotFound);
        if (snapshotRepository.lockPortfolio(portfolioId).isEmpty()) {
            throw portfolioNotFound();
        }

        LocalDate today = marketCalendar.today();
        LocalDate end = marketCalendar.lastBusinessDayOnOrBefore(today);
        LocalDate start = historyRange.startDate(today);
        rebuildMissingSnapshots(portfolio, end);
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

        BigDecimal startQuota = snapshots.getFirst().getTwrQuotaValue();
        List<HistoryPointDTO> points = snapshots.stream()
                .map(snapshot -> new HistoryPointDTO(
                        snapshot.getSnapshotDate(), snapshot.getTotalPatrimony(), snapshot.getTotalInvested(),
                        twrCalculator.calculatePeriodTwrPercentage(startQuota, snapshot.getTwrQuotaValue()),
                        snapshot.isPricingComplete()))
                .toList();
        LocalDate firstPointDate = points.getFirst().date();
        return new PortfolioHistoryResponseDTO(
                historyRange.label, firstPointDate, end, points.getLast().twrPercentage(),
                points.stream().allMatch(HistoryPointDTO::pricingComplete), points, loadBenchmarks(firstPointDate, end)
        );
    }

    // The portfolio summary can call this from its read-only transaction.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<BigDecimal> getPortfolioTwrPercentage(Portfolio portfolio) {
        if (snapshotRepository.lockPortfolio(portfolio.getId()).isEmpty()) {
            return Optional.empty();
        }
        LocalDate end = marketCalendar.lastBusinessDayOnOrBefore(marketCalendar.today());
        return rebuildMissingSnapshots(portfolio, end)
                .map(snapshot -> twrCalculator.calculatePeriodTwrPercentage(BigDecimal.ONE, snapshot.getTwrQuotaValue()));
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
                captureTransaction.executeWithoutResult(status -> {
                    if (snapshotRepository.lockPortfolio(portfolio.getId()).isPresent()) {
                        // History may have priced today's holdings before the closing quotes were available.
                        if (end.equals(today)) {
                            snapshotRepository.deleteByPortfolioAndSnapshotDateGreaterThanEqual(portfolio, today);
                        }
                        rebuildMissingSnapshots(portfolio, end);
                    }
                });
            } catch (RuntimeException exception) {
                logger.error("Falha ao capturar snapshots da carteira {}", portfolio.getId(), exception);
            }
        }
    }

    private Optional<PortfolioDailySnapshot> rebuildMissingSnapshots(Portfolio portfolio, LocalDate end) {
        Optional<PortfolioDailySnapshot> latest = snapshotRepository.findFirstByPortfolioOrderBySnapshotDateDesc(portfolio);
        if (latest.isPresent() && !latest.get().getSnapshotDate().isBefore(end)) {
            return latest;
        }

        List<Transaction> transactions = transactionRepository.findByPortfolioOrderByTransactionDateAscIdAsc(portfolio)
                .stream().sorted(TRANSACTION_ORDER).toList();
        if (transactions.isEmpty()) {
            return latest;
        }

        LocalDate firstTransactionDate = marketCalendar.marketDate(transactions.getFirst().getTransactionDate());
        LocalDate start = latest.map(snapshot -> snapshot.getSnapshotDate().plusDays(1)).orElse(firstTransactionDate);
        while (!start.isAfter(end) && !marketCalendar.isBusinessDay(start)) {
            start = start.plusDays(1);
        }
        if (start.isAfter(end)) {
            return latest;
        }

        NavigableMap<LocalDate, BigDecimal> provents = loadPaidProvents(portfolio, firstTransactionDate, end);
        if (latest.isPresent()) {
            provents = provents.tailMap(latest.get().getSnapshotDate(), false);
        }

        Map<String, NavigableMap<LocalDate, BigDecimal>> historicalPrices = new HashMap<>();
        Map<Asset, Holding> holdings = new HashMap<>();
        List<DailyObservation> observations = new ArrayList<>();
        int transactionIndex = 0;
        LocalDate lastCapturedDate = latest.map(PortfolioDailySnapshot::getSnapshotDate).orElse(null);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (!marketCalendar.isBusinessDay(date)) {
                continue;
            }

            BigDecimal cashFlow = BigDecimal.ZERO;
            while (transactionIndex < transactions.size()) {
                Transaction transaction = transactions.get(transactionIndex);
                LocalDate transactionDate = marketCalendar.marketDate(transaction.getTransactionDate());
                if (transactionDate.isAfter(date)) {
                    break;
                }
                Holding holding = holdings.computeIfAbsent(transaction.getAsset(), ignored -> new Holding());
                BigDecimal amount = holding.apply(transaction);
                if (lastCapturedDate == null || transactionDate.isAfter(lastCapturedDate)) {
                    cashFlow = cashFlow.add(amount);
                }
                transactionIndex++;
            }

            BigDecimal proventsReceived = provents.headMap(date, true).values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            provents.headMap(date, true).clear();
            observations.add(observe(date, cashFlow, proventsReceived, holdings, historicalPrices, start));
            lastCapturedDate = date;
        }

        BigDecimal previousPatrimony = latest.map(PortfolioDailySnapshot::getTotalPatrimony).orElse(BigDecimal.ZERO);
        BigDecimal previousQuota = latest.map(PortfolioDailySnapshot::getTwrQuotaValue).orElse(BigDecimal.ONE);
        List<PortfolioDailySnapshot> missing = twrCalculator.calculate(observations, previousPatrimony, previousQuota)
                .stream().map(result -> toSnapshot(portfolio, result)).toList();
        snapshotRepository.saveAll(missing);
        return Optional.of(missing.getLast());
    }

    private NavigableMap<LocalDate, BigDecimal> loadPaidProvents(Portfolio portfolio, LocalDate start, LocalDate end) {
        NavigableMap<LocalDate, BigDecimal> paidByDate = new TreeMap<>();
        for (var provision : proventProvisionRepository.findByPortfolioOrderByProventPaymentDateDescProventComDateDesc(portfolio)) {
            LocalDate date = provision.getProvent().getPaymentDate();
            if (provision.getStatus() == ProventStatus.PAID && !date.isBefore(start) && !date.isAfter(end)) {
                paidByDate.merge(date, provision.getNetAmount(), BigDecimal::add);
            }
        }
        return paidByDate;
    }

    private DailyObservation observe(
            LocalDate date,
            BigDecimal cashFlow,
            BigDecimal provents,
            Map<Asset, Holding> holdings,
            Map<String, NavigableMap<LocalDate, BigDecimal>> historicalPrices,
            LocalDate start
    ) {
        BigDecimal totalPatrimony = BigDecimal.ZERO;
        BigDecimal totalInvested = BigDecimal.ZERO;
        boolean pricingComplete = true;
        for (var entry : holdings.entrySet()) {
            Asset asset = entry.getKey();
            Holding holding = entry.getValue();
            if (holding.quantity.signum() <= 0) {
                continue;
            }
            BigDecimal cost = holding.quantity.multiply(holding.averagePrice);
            totalInvested = totalInvested.add(cost);
            Map.Entry<LocalDate, BigDecimal> close = asset.getCategory() == AssetCategory.RENDA_FIXA
                    ? null
                    : historicalPrices.computeIfAbsent(asset.getTicker(), ticker -> loadHistoricalPrices(ticker, start))
                            .floorEntry(date);
            if (close == null) {
                pricingComplete = false;
                totalPatrimony = totalPatrimony.add(cost);
            } else {
                totalPatrimony = totalPatrimony.add(holding.quantity.multiply(close.getValue()));
            }
        }
        return new DailyObservation(date, money(totalPatrimony), money(totalInvested), money(cashFlow),
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

    private Map<String, List<BenchmarkPointDTO>> loadBenchmarks(LocalDate start, LocalDate end) {
        Map<String, List<BenchmarkPointDTO>> benchmarks = new LinkedHashMap<>();
        for (String ticker : BENCHMARK_TICKERS) {
            var benchmark = benchmarkRepository.findByTicker(ticker);
            List<BenchmarkPointDTO> points = benchmark.map(value -> benchmarkPoints(
                    value.getType(), start, end, benchmarkValueRepository.findByBenchmarkInRange(value, start, end)
            )).orElseGet(List::of);
            benchmarks.put(ticker, points);
        }
        return benchmarks;
    }

    private List<BenchmarkPointDTO> benchmarkPoints(
            BenchmarkType type, LocalDate start, LocalDate end, List<BenchmarkValue> values
    ) {
        List<BenchmarkPointDTO> points = new ArrayList<>();
        if (type == BenchmarkType.INDEX) {
            BigDecimal base = null;
            for (BenchmarkValue value : values) {
                if (value.getValue().signum() <= 0) {
                    continue;
                }
                if (base == null) {
                    base = value.getValue();
                }
                points.add(new BenchmarkPointDTO(value.getReferenceDate(),
                        twrCalculator.calculatePeriodTwrPercentage(base, value.getValue())));
            }
        } else if (!values.isEmpty()) {
            points.add(new BenchmarkPointDTO(start, ZERO_PERCENTAGE));
            BigDecimal factor = BigDecimal.ONE;
            for (BenchmarkValue value : values) {
                LocalDate date = type == BenchmarkType.INFLATION
                        ? YearMonth.from(value.getReferenceDate()).atEndOfMonth()
                        : value.getReferenceDate();
                if (date.isAfter(start) && !date.isAfter(end)) {
                    factor = factor.multiply(BigDecimal.ONE.add(value.getValue().movePointLeft(2)))
                            .setScale(FinancialConstants.BENCHMARK_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
                    points.add(new BenchmarkPointDTO(date,
                            twrCalculator.calculatePeriodTwrPercentage(BigDecimal.ONE, factor)));
                }
            }
        }
        return List.copyOf(points);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
    }

    private static PortfolioDailySnapshot toSnapshot(Portfolio portfolio, DailyResult result) {
        DailyObservation observation = result.observation();
        return new PortfolioDailySnapshot(null, portfolio, observation.date(), observation.totalPatrimony(),
                observation.totalInvested(), observation.netCashFlow(), observation.proventsReceived(),
                result.dailyReturnFactor(), result.quota(), observation.pricingComplete());
    }

    private static PortfolioNotFoundException portfolioNotFound() {
        return new PortfolioNotFoundException("Carteira nao encontrada.");
    }

    private static class Holding {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal averagePrice = BigDecimal.ZERO;

        private BigDecimal apply(Transaction transaction) {
            BigDecimal tradedQuantity = transaction.getQuantity();
            BigDecimal amount = tradedQuantity.multiply(transaction.getUnitPrice());
            if (transaction.getType() == TransactionType.BUY) {
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
            return amount.negate();
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
