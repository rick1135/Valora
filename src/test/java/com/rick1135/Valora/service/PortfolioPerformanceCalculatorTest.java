package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioPerformanceCalculatorTest {
    private final FixedIncomeYieldCalculator fixedIncomeYieldCalculator = new FixedIncomeYieldCalculator();
    private final PortfolioPerformanceCalculator calculator = new PortfolioPerformanceCalculator(fixedIncomeYieldCalculator);

    @Test
    void calculateShouldUseAveragePriceFallbackForNullZeroAndNegativeQuotePrices() {
        Position nullPrice = position("ITSA4", AssetCategory.ACOES, "10.00000000", "12.00");
        Position zeroPrice = position("BOVA11", AssetCategory.ETF, "2.00000000", "50.00");
        Position negativePrice = position("BTC", AssetCategory.CRIPTO, "1.00000000", "100.00");

        PortfolioSummaryDTO summary = calculator.calculate(
                List.of(nullPrice, zeroPrice, negativePrice),
                Map.of(
                        "ITSA4", new QuoteDTO("ITSA4", null, null, null, null, null, null),
                        "BOVA11", new QuoteDTO("BOVA11", BigDecimal.ZERO, null, null, null, null, null),
                        "BTC", new QuoteDTO("BTC", new BigDecimal("-1.00"), null, null, null, null, null)
                ),
                BigDecimal.ZERO,
                List.of()
        );

        assertThat(summary.totalPatrimony()).isEqualByComparingTo("320.0000000000");
        assertThat(summary.totalInvested()).isEqualByComparingTo("320.0000000000");
        assertThat(summary.fallbackQuoteUsed()).isTrue();
        assertThat(summary.fallbackTickers()).containsExactly("ITSA4", "BOVA11", "BTC");
        assertThat(summary.allocations().getFirst().marketPriceReliable()).isFalse();
        assertThat(summary.profitability().dayChangeAvailable()).isFalse();
    }

    @Test
    void calculateShouldDeriveDayVariationFromChangePercentWhenAbsoluteChangeIsMissing() {
        Position position = position("VALE3", AssetCategory.ACOES, "5.00000000", "40.00");

        PortfolioSummaryDTO summary = calculator.calculate(
                List.of(position),
                Map.of("VALE3", new QuoteDTO(
                        "VALE3",
                        new BigDecimal("50.00"),
                        null,
                        new BigDecimal("25.00"),
                        null,
                        null,
                        null
                )),
                BigDecimal.ZERO,
                List.of()
        );

        assertThat(summary.totalPatrimony()).isEqualByComparingTo("250.0000000000");
        assertThat(summary.profitability().dayAbsoluteVariation()).isEqualByComparingTo("50.0000000000");
        assertThat(summary.profitability().dayPercentageVariation()).isEqualByComparingTo("25.0000");
        assertThat(summary.profitability().dayChangeAvailable()).isTrue();
    }

    @Test
    void calculateShouldIgnoreDayVariationWhenChangePercentWouldDivideByZero() {
        Position position = position("VALE3", AssetCategory.ACOES, "5.00000000", "40.00");

        PortfolioSummaryDTO summary = calculator.calculate(
                List.of(position),
                Map.of("VALE3", new QuoteDTO(
                        "VALE3",
                        new BigDecimal("50.00"),
                        null,
                        new BigDecimal("-100.00"),
                        null,
                        null,
                        null
                )),
                BigDecimal.ZERO,
                List.of()
        );

        assertThat(summary.profitability().dayAbsoluteVariation()).isEqualByComparingTo("0");
        assertThat(summary.profitability().dayPercentageVariation()).isEqualByComparingTo("0");
        assertThat(summary.profitability().dayChangeAvailable()).isFalse();
    }

    @Test
    void calculateShouldAggregateAllocationsByCategory() {
        Position firstStock = position("VALE3", AssetCategory.ACOES, "5.00000000", "40.00");
        Position secondStock = position("PETR4", AssetCategory.ACOES, "2.00000000", "30.00");
        Position etf = position("BOVA11", AssetCategory.ETF, "1.00000000", "100.00");

        PortfolioSummaryDTO summary = calculator.calculate(
                List.of(firstStock, secondStock, etf),
                Map.of(
                        "VALE3", new QuoteDTO("VALE3", new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO, null, null, null),
                        "PETR4", new QuoteDTO("PETR4", new BigDecimal("35.00"), BigDecimal.ZERO, BigDecimal.ZERO, null, null, null),
                        "BOVA11", new QuoteDTO("BOVA11", new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null, null, null)
                ),
                BigDecimal.ZERO,
                List.of()
        );

        assertThat(summary.allocations()).hasSize(2);
        assertThat(summary.allocations().getFirst().category()).isEqualTo("ACOES");
        assertThat(summary.allocations().getFirst().totalValue()).isEqualByComparingTo("320.0000000000");
        assertThat(summary.allocations().getFirst().percentage()).isEqualByComparingTo("76.1905");
        assertThat(summary.allocations().getFirst().currentPrice()).isEqualByComparingTo("0");
        assertThat(summary.allocations().getFirst().marketPriceReliable()).isTrue();
        assertThat(summary.allocations().get(1).category()).isEqualTo("ETF");
        assertThat(summary.allocations().get(1).totalValue()).isEqualByComparingTo("100.0000000000");
        assertThat(summary.allocations().get(1).currentPrice()).isEqualByComparingTo("100.00000000");
    }

    @Test
    void calculateShouldHandlePartialCombinationOfAssetsWithAndWithoutDayVariation() {
        Position withVariation = position("VALE3", AssetCategory.ACOES, "10.00000000", "40.00");
        Position withoutVariation = position("ITSA4", AssetCategory.ACOES, "5.00000000", "10.00");

        PortfolioSummaryDTO summary = calculator.calculate(
                List.of(withVariation, withoutVariation),
                Map.of(
                        "VALE3", new QuoteDTO("VALE3", new BigDecimal("50.00"), new BigDecimal("2.00"), new BigDecimal("4.16"), null, null, null),
                        "ITSA4", new QuoteDTO("ITSA4", new BigDecimal("12.00"), null, null, null, null, null)
                ),
                BigDecimal.ZERO,
                List.of()
        );

        assertThat(summary.totalPatrimony()).isEqualByComparingTo("560.0000000000");
        assertThat(summary.profitability().dayAbsoluteVariation()).isEqualByComparingTo("20.0000000000");
        assertThat(summary.profitability().dayPercentageVariation()).isEqualByComparingTo("3.7037");
        assertThat(summary.profitability().dayChangeAvailable()).isTrue();
    }

    @Test
    void calculateShouldHandleFixedIncomeYield() {
        Asset asset = new Asset();
        asset.setTicker("CDB_TEST");
        asset.setCategory(AssetCategory.RENDA_FIXA);
        asset.setAnnualRate(new BigDecimal("10.00"));

        Position position = new Position();
        position.setAsset(asset);
        position.setQuantity(new BigDecimal("1"));
        position.setAveragePrice(new BigDecimal("1000.00"));
        position.setPurchaseDate(LocalDate.now().minusYears(1));

        PortfolioSummaryDTO summary = calculator.calculate(
                List.of(position),
                Map.of(), // No quotes needed for Renda Fixa
                BigDecimal.ZERO,
                List.of()
        );

        assertThat(summary.totalPatrimony()).isEqualByComparingTo("1100.0000000000");
        assertThat(summary.totalInvested()).isEqualByComparingTo("1000.0000000000");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void calculateShouldExposeFixedIncomeCoverageInPositionSummary(boolean pendingLatestObservation) {
        Position position = position("CDB_CDI", AssetCategory.RENDA_FIXA, "1", "1000.00");
        LocalDate valuedThrough = LocalDate.of(2025, 4, 22);
        BigDecimal currentValue = new BigDecimal("1001.10");
        var valuation = new FixedIncomeValuationService.FixedIncomeValuationResult(
                currentValue, currentValue, true, valuedThrough, pendingLatestObservation
        );

        PortfolioSummaryDTO summary = calculator.calculate(
                List.of(position), Map.of(), BigDecimal.ZERO, BigDecimal.ZERO, List.of(), Map.of(position, valuation)
        );

        assertThat(summary.positions()).hasSize(1);
        var positionSummary = summary.positions().getFirst();
        assertThat(positionSummary.marketPriceReliable()).isTrue();
        assertThat(positionSummary.valuedThrough()).isEqualTo(valuedThrough);
        assertThat(positionSummary.pendingLatestObservation()).isEqualTo(pendingLatestObservation);
    }

    @Test
    void calculateShouldDistinguishUnrealizedRealizedAndTotalProfit() {
        Position openPosition = position("PETR4", AssetCategory.ACOES, "6.00000000", "10.00");

        PortfolioSummaryDTO summary = calculator.calculate(
                List.of(openPosition),
                Map.of("PETR4", new QuoteDTO("PETR4", new BigDecimal("15.00"), BigDecimal.ZERO, BigDecimal.ZERO, null, null, null)),
                new BigDecimal("5.00"),
                new BigDecimal("20.00"),
                List.of()
        );

        assertThat(summary.totalInvested()).isEqualByComparingTo("60.0000000000");
        assertThat(summary.totalPatrimony()).isEqualByComparingTo("90.0000000000");
        assertThat(summary.profitability().unrealizedProfit()).isEqualByComparingTo("30.0000000000");
        assertThat(summary.profitability().realizedProfit()).isEqualByComparingTo("20.00");
        assertThat(summary.profitability().absoluteProfit()).isEqualByComparingTo("55.0000000000");
    }

    @Test
    void calculateShouldPreserveRealizedProfitWhenAllPositionsAreClosed() {
        PortfolioSummaryDTO summary = calculator.calculate(
                List.of(),
                Map.of(),
                BigDecimal.ZERO,
                new BigDecimal("20.00"),
                List.of()
        );

        assertThat(summary.totalInvested()).isEqualByComparingTo("0");
        assertThat(summary.totalPatrimony()).isEqualByComparingTo("0");
        assertThat(summary.profitability().unrealizedProfit()).isEqualByComparingTo("0");
        assertThat(summary.profitability().realizedProfit()).isEqualByComparingTo("20.00");
        assertThat(summary.profitability().absoluteProfit()).isEqualByComparingTo("20.00");
        assertThat(summary.profitability().totalPercentage()).isNull();
    }

    @Test
    void calculateShouldFlagUnreliableFixedIncomeValuationAndUsePrincipalFallback() {
        Asset asset = new Asset();
        asset.setTicker("CDB_CDI");
        asset.setCategory(AssetCategory.RENDA_FIXA);
        asset.setIndexer(FixedIncomeIndexer.CDI);
        asset.setAnnualRate(new BigDecimal("100.00"));

        Position position = new Position();
        position.setAsset(asset);
        position.setQuantity(new BigDecimal("2"));
        position.setAveragePrice(new BigDecimal("1000.00"));
        position.setPurchaseDate(LocalDate.now().minusYears(1));

        PortfolioSummaryDTO summary = calculator.calculate(
                List.of(position),
                Map.of(),
                BigDecimal.ZERO,
                List.of()
        );

        assertThat(summary.totalPatrimony()).isEqualByComparingTo("2000.0000000000");
        assertThat(summary.totalInvested()).isEqualByComparingTo("2000.0000000000");
        assertThat(summary.fallbackQuoteUsed()).isTrue();
        assertThat(summary.fallbackTickers()).containsExactly("CDB_CDI");
        assertThat(summary.allocations().getFirst().marketPriceReliable()).isFalse();
        assertThat(summary.positions().getFirst().marketPriceReliable()).isFalse();
        assertThat(summary.positions().getFirst().valuedThrough()).isNull();
        assertThat(summary.positions().getFirst().pendingLatestObservation()).isFalse();
    }

    private Position position(String ticker, AssetCategory category, String quantity, String averagePrice) {
        Asset asset = new Asset();
        asset.setTicker(ticker);
        asset.setCategory(category);

        Position position = new Position();
        position.setAsset(asset);
        position.setQuantity(new BigDecimal(quantity));
        position.setAveragePrice(new BigDecimal(averagePrice));
        return position;
    }
}
