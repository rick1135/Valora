package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioPerformanceCalculatorTest {
    private final PortfolioPerformanceCalculator calculator = new PortfolioPerformanceCalculator();

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
                BigDecimal.ZERO
        );

        assertThat(summary.totalPatrimony()).isEqualByComparingTo("320.0000000000");
        assertThat(summary.totalInvested()).isEqualByComparingTo("320.0000000000");
        assertThat(summary.fallbackQuoteUsed()).isTrue();
        assertThat(summary.fallbackTickers()).containsExactly("ITSA4", "BOVA11", "BTC");
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
                BigDecimal.ZERO
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
                BigDecimal.ZERO
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
                BigDecimal.ZERO
        );

        assertThat(summary.allocations()).hasSize(2);
        assertThat(summary.allocations().getFirst().category()).isEqualTo("ACOES");
        assertThat(summary.allocations().getFirst().totalValue()).isEqualByComparingTo("320.0000000000");
        assertThat(summary.allocations().getFirst().percentage()).isEqualByComparingTo("76.1905");
        assertThat(summary.allocations().get(1).category()).isEqualTo("ETF");
        assertThat(summary.allocations().get(1).totalValue()).isEqualByComparingTo("100.0000000000");
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
                BigDecimal.ZERO
        );

        assertThat(summary.totalPatrimony()).isEqualByComparingTo("560.0000000000");
        assertThat(summary.profitability().dayAbsoluteVariation()).isEqualByComparingTo("20.0000000000");
        assertThat(summary.profitability().dayPercentageVariation()).isEqualByComparingTo("3.7037");
        assertThat(summary.profitability().dayChangeAvailable()).isTrue();
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
