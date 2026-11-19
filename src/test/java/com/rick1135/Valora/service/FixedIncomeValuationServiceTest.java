package com.rick1135.Valora.service;

import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class FixedIncomeValuationServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    private MarketCalendar marketCalendar;
    private FixedIncomeYieldCalculator yieldCalculator;
    private FixedIncomeValuationService valuationService;

    @BeforeEach
    void setUp() {
        marketCalendar = new MarketCalendar(Clock.fixed(Instant.parse("2026-01-01T15:00:00Z"), ZoneOffset.UTC));
        yieldCalculator = new FixedIncomeYieldCalculator(marketCalendar);
        valuationService = new FixedIncomeValuationService(yieldCalculator, marketCalendar, transactionRepository);
    }

    @Test
    void valuatePositionShouldEvaluateMultipleLotsSeparatelyByTransactionDate() {
        Asset asset = preAsset("CDB_PRE_2028", "10.00", LocalDate.of(2028, 1, 1));
        Position position = position(asset, "2", "1000.00", LocalDate.of(2025, 1, 1));

        // Lot 1: 2025-01-01 -> 365 days -> 1000 * 1.10 = 1100.00
        // Lot 2: 2026-01-01 -> 0 days -> 1000.00
        // Total must be 2100.00 (NOT 2200.00 from using position.purchaseDate for both lots)
        List<Transaction> txs = List.of(
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2025-01-01T15:00:00Z")),
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2026-01-01T15:00:00Z"))
        );

        FixedIncomeValuationService.FixedIncomeValuationResult result = valuationService.valuatePosition(position, txs);

        assertThat(result.reliable()).isTrue();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("2100.00000000");
        assertThat(result.currentUnitPrice()).isEqualByComparingTo("1050.00000000");
    }

    @Test
    void valuatePositionShouldConsumeOldestLotsFifoOnPartialSell() {
        Asset asset = preAsset("CDB_FIFO", "10.00", LocalDate.of(2028, 1, 1));
        Position position = position(asset, "1", "1000.00", LocalDate.of(2025, 1, 1));

        // Buy 1 @ 1000 on 2025-01-01, Buy 1 @ 1000 on 2026-01-01, Sell 1 on 2026-01-01
        // FIFO consumes the 2025-01-01 lot, leaving only the 2026-01-01 lot (0 days -> 1000.00)
        List<Transaction> txs = List.of(
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2025-01-01T15:00:00Z")),
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2026-01-01T15:00:00Z")),
                tx(asset, TransactionType.SELL, "1", "1050.00", Instant.parse("2026-01-01T16:00:00Z"))
        );

        FixedIncomeValuationService.FixedIncomeValuationResult result = valuationService.valuatePosition(position, txs);

        assertThat(result.reliable()).isTrue();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("1000.00000000");
        assertThat(result.currentUnitPrice()).isEqualByComparingTo("1000.00000000");
    }

    @Test
    void valuatePositionShouldCapAtExpirationDateAcrossLots() {
        // Today is 2026-01-01, expirationDate is 2025-07-02 (182 days from 2025-01-01)
        LocalDate expiration = LocalDate.of(2025, 7, 2);
        Asset asset = preAsset("CDB_VENCIDO", "10.00", expiration);
        Position position = position(asset, "1", "1000.00", LocalDate.of(2025, 1, 1));

        List<Transaction> txs = List.of(
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2025-01-01T15:00:00Z"))
        );

        BigDecimal expectedAtExpiration = yieldCalculator.calculateCurrentValue(
                new BigDecimal("1000.00"),
                BigDecimal.ONE,
                LocalDate.of(2025, 1, 1),
                new BigDecimal("10.00"),
                expiration
        );

        FixedIncomeValuationService.FixedIncomeValuationResult result = valuationService.valuatePosition(position, txs);

        assertThat(result.reliable()).isTrue();
        assertThat(result.currentTotalValue()).isEqualByComparingTo(expectedAtExpiration);
    }

    @Test
    void valuatePositionShouldMarkPostFixedIndexersUnreliableWithoutFalseYield() {
        for (FixedIncomeIndexer indexer : List.of(FixedIncomeIndexer.CDI, FixedIncomeIndexer.IPCA, FixedIncomeIndexer.SELIC)) {
            Asset asset = preAsset("RF_" + indexer.name(), "110.00", LocalDate.of(2028, 1, 1));
            asset.setIndexer(indexer);
            Position position = position(asset, "2", "1000.00", LocalDate.of(2025, 1, 1));

            FixedIncomeValuationService.FixedIncomeValuationResult result = valuationService.valuatePosition(position, List.of());

            assertThat(result.reliable()).isFalse();
            assertThat(result.currentTotalValue()).isEqualByComparingTo("2000.00000000");
            assertThat(result.currentUnitPrice()).isEqualByComparingTo("1000.00000000");
        }
    }

    @Test
    void valuatePositionShouldMarkLegacyPositionWithoutPurchaseDateUnreliable() {
        Asset asset = preAsset("CDB_LEGACY", "10.00", LocalDate.of(2028, 1, 1));
        Position legacyPosition = position(asset, "2", "1000.00", null);

        FixedIncomeValuationService.FixedIncomeValuationResult result = valuationService.valuatePosition(legacyPosition, List.of());

        assertThat(result.reliable()).isFalse();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("2000.00000000");
        assertThat(result.currentUnitPrice()).isEqualByComparingTo("1000.00000000");
    }

    @Test
    void valuatePositionShouldMarkInconsistentTransactionHistoryUnreliable() {
        Asset asset = preAsset("CDB_MISMATCH", "10.00", LocalDate.of(2028, 1, 1));
        Position position = position(asset, "5", "1000.00", LocalDate.of(2025, 1, 1));

        // Transaction history only accounts for 1 unit, while position has 5
        List<Transaction> txs = List.of(
                tx(asset, TransactionType.BUY, "1", "1000.00", Instant.parse("2025-01-01T15:00:00Z"))
        );

        FixedIncomeValuationService.FixedIncomeValuationResult result = valuationService.valuatePosition(position, txs);

        assertThat(result.reliable()).isFalse();
        assertThat(result.currentTotalValue()).isEqualByComparingTo("5000.00000000");
    }

    private Asset preAsset(String ticker, String annualRate, LocalDate expirationDate) {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker(ticker);
        asset.setName(ticker);
        asset.setCategory(AssetCategory.RENDA_FIXA);
        asset.setIndexer(FixedIncomeIndexer.PRE);
        asset.setAnnualRate(new BigDecimal(annualRate));
        asset.setExpirationDate(expirationDate);
        return asset;
    }

    private Position position(Asset asset, String quantity, String averagePrice, LocalDate purchaseDate) {
        Position position = new Position();
        position.setId(UUID.randomUUID());
        position.setPortfolio(new Portfolio());
        position.setAsset(asset);
        position.setQuantity(new BigDecimal(quantity));
        position.setAveragePrice(new BigDecimal(averagePrice));
        position.setPurchaseDate(purchaseDate);
        return position;
    }

    private Transaction tx(Asset asset, TransactionType type, String quantity, String unitPrice, Instant date) {
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());
        tx.setAsset(asset);
        tx.setType(type);
        tx.setQuantity(new BigDecimal(quantity));
        tx.setUnitPrice(new BigDecimal(unitPrice));
        tx.setTransactionDate(date);
        return tx;
    }
}
