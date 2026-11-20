package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.FixedIncomeRateType;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.repository.BenchmarkRepository;
import com.rick1135.Valora.repository.BenchmarkValueRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import com.rick1135.Valora.service.PostFixedIncomeYieldCalculator.PostFixedValuation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class FixedIncomeValuationService {

    private static final BigDecimal QUANTITY_TOLERANCE = new BigDecimal("0.00000001");

    private final FixedIncomeYieldCalculator fixedIncomeYieldCalculator;
    private final MarketCalendar marketCalendar;
    private final TransactionRepository transactionRepository;
    private final PostFixedIncomeYieldCalculator postFixedIncomeYieldCalculator;
    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkValueRepository benchmarkValueRepository;

    @Autowired
    public FixedIncomeValuationService(
            FixedIncomeYieldCalculator fixedIncomeYieldCalculator,
            MarketCalendar marketCalendar,
            TransactionRepository transactionRepository,
            PostFixedIncomeYieldCalculator postFixedIncomeYieldCalculator,
            BenchmarkRepository benchmarkRepository,
            BenchmarkValueRepository benchmarkValueRepository
    ) {
        this.fixedIncomeYieldCalculator = fixedIncomeYieldCalculator;
        this.marketCalendar = marketCalendar;
        this.transactionRepository = transactionRepository;
        this.postFixedIncomeYieldCalculator = postFixedIncomeYieldCalculator;
        this.benchmarkRepository = benchmarkRepository;
        this.benchmarkValueRepository = benchmarkValueRepository;
    }

    public FixedIncomeValuationService(
            FixedIncomeYieldCalculator fixedIncomeYieldCalculator,
            MarketCalendar marketCalendar,
            TransactionRepository transactionRepository
    ) {
        this(fixedIncomeYieldCalculator, marketCalendar, transactionRepository,
                new PostFixedIncomeYieldCalculator(marketCalendar), null, null);
    }

    public record FixedIncomeValuationResult(
            BigDecimal currentTotalValue,
            BigDecimal currentUnitPrice,
            boolean reliable,
            LocalDate valuedThrough
    ) {
        public FixedIncomeValuationResult(BigDecimal currentTotalValue, BigDecimal currentUnitPrice, boolean reliable) {
            this(currentTotalValue, currentUnitPrice, reliable, null);
        }
    }

    private record ValuationLot(
            LocalDate purchaseDate,
            BigDecimal quantity,
            BigDecimal unitPrice
    ) {
    }

    private record BenchmarkRange(LocalDate start, LocalDate end) {
        BenchmarkRange merge(BenchmarkRange other) {
            return new BenchmarkRange(
                    start.isBefore(other.start) ? start : other.start,
                    end.isAfter(other.end) ? end : other.end
            );
        }
    }

    public Map<Position, FixedIncomeValuationResult> valuatePortfolioPositions(
            Portfolio portfolio,
            List<Position> fixedIncomePositions
    ) {
        if (fixedIncomePositions == null || fixedIncomePositions.isEmpty()) {
            return Map.of();
        }
        List<Transaction> transactions = portfolio == null
                ? List.of()
                : transactionRepository.findTransactionHistoryByPortfolioAndFilters(
                        portfolio, null, null, null, null, Pageable.unpaged()
                ).getContent();
        return valuatePositions(fixedIncomePositions, transactions);
    }

    public Map<Position, FixedIncomeValuationResult> valuatePositions(
            List<Position> fixedIncomePositions,
            List<Transaction> portfolioTransactions
    ) {
        if (fixedIncomePositions == null || fixedIncomePositions.isEmpty()) {
            return Map.of();
        }

        Map<UUID, List<Transaction>> transactionsByAssetId = groupTransactionsByAssetId(portfolioTransactions);
        return valuateWithTransactions(fixedIncomePositions, position -> {
            UUID assetId = position.getAsset().getId();
            return assetId != null
                    ? transactionsByAssetId.getOrDefault(assetId, List.of())
                    : List.of();
        });
    }

    public FixedIncomeValuationResult valuatePosition(
            Position position,
            List<Transaction> assetTransactions
    ) {
        return position == null
                ? principalFallback(null)
                : valuateWithTransactions(List.of(position), ignored -> assetTransactions).get(position);
    }

    private Map<Position, FixedIncomeValuationResult> valuateWithTransactions(
            List<Position> positions,
            Function<Position, List<Transaction>> transactionsForPosition
    ) {
        Map<Position, FixedIncomeValuationResult> results = new IdentityHashMap<>();
        Map<Position, List<ValuationLot>> lotsByPosition = new IdentityHashMap<>();
        Map<FixedIncomeIndexer, BenchmarkRange> ranges = new EnumMap<>(FixedIncomeIndexer.class);
        LocalDate today = marketCalendar.today();
        for (Position position : positions) {
            if (position == null || position.getQuantity() == null || position.getQuantity().signum() <= 0
                    || position.getAveragePrice() == null || position.getAsset() == null
                    || !hasDefensibleRateModel(position.getAsset())) {
                results.put(position, principalFallback(position));
                continue;
            }

            List<ValuationLot> lots = openLots(position, transactionsForPosition.apply(position));
            if (lots == null) {
                results.put(position, principalFallback(position));
                continue;
            }
            lotsByPosition.put(position, lots);
            Asset asset = position.getAsset();
            if (!isFixedRate(asset)) {
                addBenchmarkRange(ranges, asset, lots, today);
            }
        }

        Map<FixedIncomeIndexer, Map<LocalDate, BigDecimal>> benchmarks = loadBenchmarks(ranges);
        lotsByPosition.forEach((position, lots) -> results.put(position, valuateLots(
                position, lots, benchmarks.getOrDefault(position.getAsset().getIndexer(), Map.of())
        )));
        return results;
    }

    private List<ValuationLot> openLots(Position position, List<Transaction> assetTransactions) {
        if (assetTransactions != null && !assetTransactions.isEmpty()) {
            return reconstructOpenLots(assetTransactions, position.getQuantity());
        }
        if (position.getPurchaseDate() == null) {
            return null;
        }
        return List.of(new ValuationLot(position.getPurchaseDate(), position.getQuantity(), position.getAveragePrice()));
    }

    private void addBenchmarkRange(
            Map<FixedIncomeIndexer, BenchmarkRange> ranges,
            Asset asset,
            List<ValuationLot> lots,
            LocalDate today
    ) {
        // The FIFO lots are already in purchase order.
        LocalDate start = lots.getFirst().purchaseDate();
        LocalDate end = PostFixedIncomeYieldCalculator.effectiveEndDate(today, asset.getExpirationDate());
        if (!start.isBefore(end)) {
            return;
        }
        ranges.merge(asset.getIndexer(), new BenchmarkRange(start, end.minusDays(1)), BenchmarkRange::merge);
    }

    private Map<FixedIncomeIndexer, Map<LocalDate, BigDecimal>> loadBenchmarks(
            Map<FixedIncomeIndexer, BenchmarkRange> ranges
    ) {
        Map<FixedIncomeIndexer, Map<LocalDate, BigDecimal>> benchmarks = new EnumMap<>(FixedIncomeIndexer.class);
        if (benchmarkRepository == null || benchmarkValueRepository == null) {
            return benchmarks;
        }
        ranges.forEach((indexer, range) -> benchmarkRepository.findByTicker(indexer.name()).ifPresent(benchmark -> {
            Map<LocalDate, BigDecimal> rates = new HashMap<>();
            benchmarkValueRepository.findByBenchmarkInRange(benchmark, range.start(), range.end())
                    .forEach(value -> rates.put(value.getReferenceDate(), value.getValue()));
            benchmarks.put(indexer, rates);
        }));
        return benchmarks;
    }

    private FixedIncomeValuationResult valuateLots(
            Position position,
            List<ValuationLot> lots,
            Map<LocalDate, BigDecimal> benchmarkRates
    ) {
        Asset asset = position.getAsset();
        BigDecimal totalValue = BigDecimal.ZERO;
        LocalDate valuedThrough = null;
        for (ValuationLot lot : lots) {
            if (isFixedRate(asset)) {
                totalValue = totalValue.add(fixedIncomeYieldCalculator.calculateCurrentValue(
                        lot.unitPrice(), lot.quantity(), lot.purchaseDate(), asset.getAnnualRate(), asset.getExpirationDate()
                ));
                continue;
            }
            PostFixedValuation valuation = postFixedIncomeYieldCalculator.calculateCurrentValue(
                    asset, lot.unitPrice().multiply(lot.quantity()), lot.purchaseDate(), benchmarkRates
            );
            if (!valuation.reliable()) {
                return principalFallback(position);
            }
            totalValue = totalValue.add(valuation.currentValue());
            LocalDate lotValuedThrough = valuation.valuedThrough();
            if (lotValuedThrough != null && (valuedThrough == null || lotValuedThrough.isBefore(valuedThrough))) {
                valuedThrough = lotValuedThrough;
            }
        }
        totalValue = totalValue.setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        BigDecimal unitPrice = totalValue.divide(position.getQuantity(),
                FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        return new FixedIncomeValuationResult(totalValue, unitPrice, true, valuedThrough);
    }

    private FixedIncomeValuationResult principalFallback(Position position) {
        if (position == null
                || position.getQuantity() == null
                || position.getQuantity().compareTo(BigDecimal.ZERO) <= 0
                || position.getAveragePrice() == null) {
            return new FixedIncomeValuationResult(BigDecimal.ZERO, BigDecimal.ZERO, false);
        }

        BigDecimal quantity = position.getQuantity();
        BigDecimal averagePrice = position.getAveragePrice();
        BigDecimal principalFallback = averagePrice.multiply(quantity)
                .setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        BigDecimal unitPriceFallback = averagePrice
                .setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);

        return new FixedIncomeValuationResult(principalFallback, unitPriceFallback, false);
    }

    private boolean hasDefensibleRateModel(Asset asset) {
        return isFixedRate(asset)
                ? asset.getAnnualRate() != null
                        && (asset.getRateType() == null || asset.getRateType() == FixedIncomeRateType.FIXED)
                : PostFixedIncomeYieldCalculator.supportsRateModel(asset);
    }

    private boolean isFixedRate(Asset asset) {
        return asset.getIndexer() == null || asset.getIndexer() == FixedIncomeIndexer.PRE;
    }

    private Map<UUID, List<Transaction>> groupTransactionsByAssetId(List<Transaction> portfolioTransactions) {
        if (portfolioTransactions == null || portfolioTransactions.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Transaction>> grouped = new HashMap<>();
        for (Transaction tx : portfolioTransactions) {
            if (tx.getAsset() != null && tx.getAsset().getId() != null) {
                grouped.computeIfAbsent(tx.getAsset().getId(), ignored -> new java.util.ArrayList<>()).add(tx);
            }
        }
        return grouped;
    }

    private List<ValuationLot> reconstructOpenLots(List<Transaction> transactions, BigDecimal expectedQuantity) {
        for (Transaction tx : transactions) {
            if (tx.getTransactionDate() == null
                    || tx.getType() == null
                    || tx.getQuantity() == null
                    || tx.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            if (tx.getType() == TransactionType.BUY && tx.getUnitPrice() == null) {
                return null;
            }
        }

        List<Transaction> ordered = transactions.stream()
                .sorted(Comparator.comparing(Transaction::getTransactionDate)
                        .thenComparing(tx -> tx.getType() == TransactionType.BUY ? 0 : 1))
                .toList();

        Deque<ValuationLot> openLots = new ArrayDeque<>();
        for (Transaction tx : ordered) {
            if (tx.getType() == TransactionType.BUY) {
                LocalDate purchaseDate = marketCalendar.marketDate(tx.getTransactionDate());
                openLots.addLast(new ValuationLot(purchaseDate, tx.getQuantity(), tx.getUnitPrice()));
            } else if (tx.getType() == TransactionType.SELL) {
                BigDecimal remainingToSell = tx.getQuantity();
                while (remainingToSell.compareTo(BigDecimal.ZERO) > 0) {
                    ValuationLot oldest = openLots.pollFirst();
                    if (oldest == null) {
                        return null;
                    }
                    if (oldest.quantity().compareTo(remainingToSell) > 0) {
                        openLots.addFirst(new ValuationLot(
                                oldest.purchaseDate(),
                                oldest.quantity().subtract(remainingToSell),
                                oldest.unitPrice()
                        ));
                        remainingToSell = BigDecimal.ZERO;
                    } else {
                        remainingToSell = remainingToSell.subtract(oldest.quantity());
                    }
                }
            }
        }

        BigDecimal totalLotQuantity = openLots.stream()
                .map(ValuationLot::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (openLots.isEmpty() || totalLotQuantity.subtract(expectedQuantity).abs().compareTo(QUANTITY_TOLERANCE) > 0) {
            return null;
        }

        return List.copyOf(openLots);
    }
}
