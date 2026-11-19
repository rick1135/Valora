package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.exception.InsufficientPositionException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PositionTimelineCalculator {
    private static final Comparator<Transaction> CHRONOLOGICAL_ORDER = Comparator
            .comparing(Transaction::getTransactionDate)
            .thenComparing(tx -> tx.getType() == TransactionType.BUY ? 0 : 1)
            .thenComparing(tx -> tx.getQuantity(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(tx -> tx.getUnitPrice(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(tx -> tx.getId() != null ? 0 : 1)
            .thenComparing(Transaction::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final MarketCalendar marketCalendar;

    public PositionTimelineCalculator(MarketCalendar marketCalendar) {
        this.marketCalendar = marketCalendar;
    }

    public PositionTimelineResult replay(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new PositionTimelineResult(BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO);
        }

        List<Transaction> ordered = transactions.stream()
                .sorted(CHRONOLOGICAL_ORDER)
                .toList();

        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal averagePrice = BigDecimal.ZERO;
        LocalDate purchaseDate = null;
        BigDecimal realizedProfit = BigDecimal.ZERO;
        MarketCalendar calendar = effectiveMarketCalendar();

        for (Transaction transaction : ordered) {
            if (transaction.getType() == TransactionType.BUY) {
                boolean openingPosition = quantity.compareTo(BigDecimal.ZERO) == 0;
                BigDecimal buyQuantity = transaction.getQuantity();
                BigDecimal buyUnitPrice = transaction.getUnitPrice();

                BigDecimal currentTotalValue = quantity.multiply(averagePrice);
                BigDecimal buyTotalValue = buyQuantity.multiply(buyUnitPrice);
                BigDecimal newQuantity = quantity.add(buyQuantity);

                averagePrice = currentTotalValue.add(buyTotalValue)
                        .divide(newQuantity, FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
                quantity = newQuantity;

                if (openingPosition || purchaseDate == null) {
                    purchaseDate = calendar.marketDate(transaction.getTransactionDate());
                }
            } else if (transaction.getType() == TransactionType.SELL) {
                BigDecimal sellQuantity = transaction.getQuantity();
                if (quantity.compareTo(sellQuantity) < 0) {
                    throw new InsufficientPositionException("Quantidade insuficiente para venda");
                }

                BigDecimal costBasis = sellQuantity.multiply(averagePrice);
                BigDecimal proceeds = sellQuantity.multiply(transaction.getUnitPrice());
                realizedProfit = realizedProfit.add(proceeds.subtract(costBasis));

                quantity = quantity.subtract(sellQuantity);
                if (quantity.compareTo(BigDecimal.ZERO) == 0) {
                    quantity = BigDecimal.ZERO;
                    averagePrice = BigDecimal.ZERO;
                    purchaseDate = null;
                }
            }
        }

        return new PositionTimelineResult(quantity, averagePrice, purchaseDate, realizedProfit);
    }

    public BigDecimal calculateRealizedProfit(List<Transaction> portfolioTransactions) {
        if (portfolioTransactions == null || portfolioTransactions.isEmpty()) {
            return BigDecimal.ZERO;
        }

        Map<UUID, List<Transaction>> byAsset = portfolioTransactions.stream()
                .filter(tx -> tx.getAsset() != null && tx.getAsset().getId() != null)
                .collect(Collectors.groupingBy(
                        tx -> tx.getAsset().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return byAsset.values().stream()
                .map(this::replay)
                .map(PositionTimelineResult::realizedProfit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private MarketCalendar effectiveMarketCalendar() {
        return marketCalendar != null ? marketCalendar : new MarketCalendar(Clock.systemDefaultZone());
    }

    public record PositionTimelineResult(
            BigDecimal quantity,
            BigDecimal averagePrice,
            LocalDate purchaseDate,
            BigDecimal realizedProfit
    ) {
    }
}
