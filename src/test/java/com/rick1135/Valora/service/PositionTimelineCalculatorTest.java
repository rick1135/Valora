package com.rick1135.Valora.service;

import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.exception.InsufficientPositionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionTimelineCalculatorTest {
    private final MarketCalendar marketCalendar = new MarketCalendar(
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneId.of("America/Sao_Paulo"))
    );
    private final PositionTimelineCalculator calculator = new PositionTimelineCalculator(marketCalendar);

    @Test
    void replayShouldCalculatePartialSellRealizedProfitAndPreserveAveragePriceAndPurchaseDate() {
        Asset asset = asset("PETR4");
        Transaction buy = tx(asset, TransactionType.BUY, "10.00000000", "10.00000000", "2026-01-10T14:00:00Z");
        Transaction sell = tx(asset, TransactionType.SELL, "4.00000000", "15.00000000", "2026-01-20T14:00:00Z");

        PositionTimelineCalculator.PositionTimelineResult result = calculator.replay(List.of(buy, sell));

        assertThat(result.quantity()).isEqualByComparingTo("6.00000000");
        assertThat(result.averagePrice()).isEqualByComparingTo("10.00000000");
        assertThat(result.purchaseDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(result.realizedProfit()).isEqualByComparingTo("20.00000000");
    }

    @Test
    void replayShouldPreserveRealizedProfitAndResetPositionOnTotalSell() {
        Asset asset = asset("VALE3");
        Transaction buy = tx(asset, TransactionType.BUY, "10.00000000", "10.00000000", "2026-01-10T14:00:00Z");
        Transaction sell = tx(asset, TransactionType.SELL, "10.00000000", "12.00000000", "2026-01-20T14:00:00Z");

        PositionTimelineCalculator.PositionTimelineResult result = calculator.replay(List.of(buy, sell));

        assertThat(result.quantity()).isEqualByComparingTo("0");
        assertThat(result.averagePrice()).isEqualByComparingTo("0");
        assertThat(result.purchaseDate()).isNull();
        assertThat(result.realizedProfit()).isEqualByComparingTo("20.00000000");
    }

    @Test
    void replayShouldResetPurchaseDateOnReopenAndAccumulateRealizedProfit() {
        Asset asset = asset("ITSA4");
        Transaction buy1 = tx(asset, TransactionType.BUY, "10.00000000", "10.00000000", "2026-01-10T14:00:00Z");
        Transaction sell1 = tx(asset, TransactionType.SELL, "10.00000000", "12.00000000", "2026-01-20T14:00:00Z");
        Transaction buy2 = tx(asset, TransactionType.BUY, "5.00000000", "20.00000000", "2026-02-01T14:00:00Z");

        PositionTimelineCalculator.PositionTimelineResult result = calculator.replay(List.of(buy1, sell1, buy2));

        assertThat(result.quantity()).isEqualByComparingTo("5.00000000");
        assertThat(result.averagePrice()).isEqualByComparingTo("20.00000000");
        assertThat(result.purchaseDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(result.realizedProfit()).isEqualByComparingTo("20.00000000");
    }

    @Test
    void replayShouldRecalculateAveragePriceAndRealizedProfitForRetroactiveBuyBetweenBuyAndSell() {
        Asset asset = asset("BBAS3");
        Transaction firstBuy = tx(asset, TransactionType.BUY, "10.00000000", "10.00000000", "2026-01-10T14:00:00Z");
        Transaction existingSell = tx(asset, TransactionType.SELL, "10.00000000", "20.00000000", "2026-01-30T14:00:00Z");
        Transaction retroactiveBuy = tx(asset, TransactionType.BUY, "10.00000000", "14.00000000", "2026-01-20T14:00:00Z");

        PositionTimelineCalculator.PositionTimelineResult result = calculator.replay(
                List.of(firstBuy, existingSell, retroactiveBuy)
        );

        assertThat(result.quantity()).isEqualByComparingTo("10.00000000");
        assertThat(result.averagePrice()).isEqualByComparingTo("12.00000000");
        assertThat(result.purchaseDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(result.realizedProfit()).isEqualByComparingTo("80.00000000");
    }

    @Test
    void replayShouldUpdatePurchaseDateWhenRetroactiveBuyIsEarlierThanExistingOpeningBuy() {
        Asset asset = asset("CDB01");
        Transaction laterBuy = tx(asset, TransactionType.BUY, "10.00000000", "20.00000000", "2026-02-01T14:00:00Z");
        Transaction earlierBuy = tx(asset, TransactionType.BUY, "10.00000000", "10.00000000", "2026-01-15T14:00:00Z");

        PositionTimelineCalculator.PositionTimelineResult result = calculator.replay(List.of(laterBuy, earlierBuy));

        assertThat(result.quantity()).isEqualByComparingTo("20.00000000");
        assertThat(result.averagePrice()).isEqualByComparingTo("15.00000000");
        assertThat(result.purchaseDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void replayShouldRejectRetroactiveSellWhenHistoricalBalanceBecomesNegative() {
        Asset asset = asset("WEGE3");
        Transaction buy1 = tx(asset, TransactionType.BUY, "10.00000000", "10.00000000", "2026-06-01T14:00:00Z");
        Transaction sell1 = tx(asset, TransactionType.SELL, "8.00000000", "12.00000000", "2026-06-05T14:00:00Z");
        Transaction buy2 = tx(asset, TransactionType.BUY, "10.00000000", "11.00000000", "2026-06-10T14:00:00Z");
        Transaction retroactiveSell = tx(asset, TransactionType.SELL, "5.00000000", "12.00000000", "2026-06-06T14:00:00Z");

        assertThatThrownBy(() -> calculator.replay(List.of(buy1, sell1, buy2, retroactiveSell)))
                .isInstanceOf(InsufficientPositionException.class)
                .hasMessageContaining("Quantidade insuficiente");
    }

    @Test
    void calculateRealizedProfitShouldSumAcrossMultipleAssets() {
        Asset petr4 = asset("PETR4");
        Asset vale3 = asset("VALE3");

        List<Transaction> txs = List.of(
                tx(petr4, TransactionType.BUY, "10.00000000", "10.00000000", "2026-01-10T14:00:00Z"),
                tx(petr4, TransactionType.SELL, "10.00000000", "12.00000000", "2026-01-20T14:00:00Z"),
                tx(vale3, TransactionType.BUY, "5.00000000", "50.00000000", "2026-01-11T14:00:00Z"),
                tx(vale3, TransactionType.SELL, "2.00000000", "45.00000000", "2026-01-22T14:00:00Z")
        );

        BigDecimal totalRealized = calculator.calculateRealizedProfit(txs);

        assertThat(totalRealized).isEqualByComparingTo("10.00000000");
    }

    @Test
    void replayShouldKeepSameDayBuyBeforeSellConsistentBeforeAndAfterPersistingIds() {
        Asset asset = asset("PETR4");
        Transaction existingBuy = tx(asset, TransactionType.BUY, "10.00000000", "20.00000000", "2026-06-10T14:00:00Z");
        existingBuy.setId(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

        Transaction newSell = tx(asset, TransactionType.SELL, "10.00000000", "25.00000000", "2026-06-10T14:00:00Z");
        newSell.setId(null);

        PositionTimelineCalculator.PositionTimelineResult beforeSave = calculator.replay(List.of(existingBuy, newSell));

        newSell.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        PositionTimelineCalculator.PositionTimelineResult afterSave = calculator.replay(List.of(newSell, existingBuy));

        assertThat(beforeSave.quantity()).isEqualByComparingTo("0");
        assertThat(afterSave).isEqualTo(beforeSave);
    }

    @Test
    void replayShouldOrderSameTimestampBuyBeforeExistingSellConsistentlyBeforeAndAfterSave() {
        Asset asset = asset("VALE3");
        Transaction openingBuy = tx(asset, TransactionType.BUY, "10.00000000", "10.00000000", "2026-06-01T14:00:00Z");
        openingBuy.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

        Transaction existingSell = tx(asset, TransactionType.SELL, "10.00000000", "15.00000000", "2026-06-10T14:00:00Z");
        existingSell.setId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

        Transaction newBuy = tx(asset, TransactionType.BUY, "10.00000000", "20.00000000", "2026-06-10T14:00:00Z");
        newBuy.setId(null);

        PositionTimelineCalculator.PositionTimelineResult beforeSave = calculator.replay(
                List.of(openingBuy, existingSell, newBuy)
        );

        newBuy.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        PositionTimelineCalculator.PositionTimelineResult afterSaveSmallerId = calculator.replay(
                List.of(openingBuy, newBuy, existingSell)
        );

        newBuy.setId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
        PositionTimelineCalculator.PositionTimelineResult afterSaveLargerId = calculator.replay(
                List.of(openingBuy, existingSell, newBuy)
        );

        assertThat(beforeSave.quantity()).isEqualByComparingTo("10.00000000");
        assertThat(beforeSave.averagePrice()).isEqualByComparingTo("15.00000000");
        assertThat(beforeSave.purchaseDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(beforeSave.realizedProfit()).isEqualByComparingTo("0");
        assertThat(afterSaveSmallerId).isEqualTo(beforeSave);
        assertThat(afterSaveLargerId).isEqualTo(beforeSave);
    }

    @Test
    void replayShouldKeepSameTimestampBuysDeterministicBeforeAndAfterPersistingIds() {
        Asset asset = asset("ITUB4");
        Transaction initialBuy = tx(asset, TransactionType.BUY, "1.00000000", "10.00000000", "2026-06-01T14:00:00Z");
        initialBuy.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

        Transaction existingBuy = tx(asset, TransactionType.BUY, "2.00000000", "11.00000000", "2026-06-10T14:00:00Z");
        existingBuy.setId(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

        Transaction newBuy = tx(asset, TransactionType.BUY, "3.00000000", "12.00000000", "2026-06-10T14:00:00Z");
        newBuy.setId(null);

        PositionTimelineCalculator.PositionTimelineResult beforeSave = calculator.replay(
                List.of(initialBuy, existingBuy, newBuy)
        );

        newBuy.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        PositionTimelineCalculator.PositionTimelineResult afterSave = calculator.replay(
                List.of(initialBuy, newBuy, existingBuy)
        );

        assertThat(afterSave).isEqualTo(beforeSave);
    }

    private Asset asset(String ticker) {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker(ticker);
        return asset;
    }

    private Transaction tx(Asset asset, TransactionType type, String quantity, String unitPrice, String instant) {
        Transaction transaction = new Transaction();
        transaction.setId(UUID.randomUUID());
        transaction.setAsset(asset);
        transaction.setType(type);
        transaction.setQuantity(new BigDecimal(quantity));
        transaction.setUnitPrice(new BigDecimal(unitPrice));
        transaction.setTransactionDate(Instant.parse(instant));
        return transaction;
    }
}
