package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FixedIncomeValuationService {

    private static final BigDecimal QUANTITY_TOLERANCE = new BigDecimal("0.00000001");

    private final FixedIncomeYieldCalculator fixedIncomeYieldCalculator;
    private final MarketCalendar marketCalendar;
    private final TransactionRepository transactionRepository;

    public record FixedIncomeValuationResult(
            BigDecimal currentTotalValue,
            BigDecimal currentUnitPrice,
            boolean reliable
    ) {
    }

    private record ValuationLot(
            LocalDate purchaseDate,
            BigDecimal quantity,
            BigDecimal unitPrice
    ) {
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
        Map<Position, FixedIncomeValuationResult> results = new IdentityHashMap<>();

        for (Position position : fixedIncomePositions) {
            UUID assetId = position.getAsset() != null ? position.getAsset().getId() : null;
            List<Transaction> assetTransactions = assetId != null
                    ? transactionsByAssetId.getOrDefault(assetId, List.of())
                    : List.of();
            results.put(position, valuatePosition(position, assetTransactions));
        }

        return results;
    }

    public FixedIncomeValuationResult valuatePosition(
            Position position,
            List<Transaction> assetTransactions
    ) {
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

        Asset asset = position.getAsset();
        if (asset == null || !hasDefensibleRateModel(asset)) {
            return new FixedIncomeValuationResult(principalFallback, unitPriceFallback, false);
        }

        if (assetTransactions != null && !assetTransactions.isEmpty()) {
            List<ValuationLot> openLots = reconstructOpenLots(assetTransactions, quantity);
            if (openLots == null) {
                return new FixedIncomeValuationResult(principalFallback, unitPriceFallback, false);
            }

            BigDecimal totalValue = BigDecimal.ZERO;
            for (ValuationLot lot : openLots) {
                totalValue = totalValue.add(fixedIncomeYieldCalculator.calculateCurrentValue(
                        lot.unitPrice(),
                        lot.quantity(),
                        lot.purchaseDate(),
                        asset.getAnnualRate(),
                        asset.getExpirationDate()
                ));
            }
            totalValue = totalValue.setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
            BigDecimal unitPrice = totalValue.divide(quantity, FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
            return new FixedIncomeValuationResult(totalValue, unitPrice, true);
        }

        // Legacy or single-lot fallback when no transaction history is available
        if (position.getPurchaseDate() == null) {
            return new FixedIncomeValuationResult(principalFallback, unitPriceFallback, false);
        }

        BigDecimal totalValue = fixedIncomeYieldCalculator.calculateCurrentValue(
                averagePrice,
                quantity,
                position.getPurchaseDate(),
                asset.getAnnualRate(),
                asset.getExpirationDate()
        );
        BigDecimal unitPrice = totalValue.divide(quantity, FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        return new FixedIncomeValuationResult(totalValue, unitPrice, true);
    }

    private boolean hasDefensibleRateModel(Asset asset) {
        // Only fixed-rate (PRE) or legacy fixed-rate assets (null indexer) have an unambiguous
        // contractual rate in annualRate. Post-fixed indexers (CDI, IPCA, SELIC) require complete
        // benchmark series and explicit spread vs. percentage-of-index semantics.
        FixedIncomeIndexer indexer = asset.getIndexer();
        if (indexer != null && indexer != FixedIncomeIndexer.PRE) {
            return false;
        }
        return asset.getAnnualRate() != null;
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
