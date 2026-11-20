package com.rick1135.Valora.service;

import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.FixedIncomeRateType;
import com.rick1135.Valora.service.PostFixedIncomeYieldCalculator.PostFixedValuation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostFixedIncomeYieldCalculatorTest {

    private static final BigDecimal PRINCIPAL = new BigDecimal("1000.00");
    private static final LocalDate APRIL_17 = LocalDate.of(2025, 4, 17);
    private static final LocalDate APRIL_22 = LocalDate.of(2025, 4, 22);
    private static final LocalDate APRIL_23 = LocalDate.of(2025, 4, 23);
    private static final LocalDate APRIL_24 = LocalDate.of(2025, 4, 24);
    private static final BigDecimal DAILY_RATE = new BigDecimal("0.055");

    @ParameterizedTest
    @CsvSource({"100, 1001.65090767", "110, 1001.81609830"})
    void compoundsCdiOnBusinessDaysAcrossWeekendAndHolidays(String annualRate, String expectedValue) {
        Asset asset = asset(FixedIncomeIndexer.CDI, FixedIncomeRateType.PERCENTAGE_OF_INDEX, annualRate);

        PostFixedValuation result = calculatorOn(APRIL_24).calculateCurrentValue(
                asset, PRINCIPAL, APRIL_17,
                Map.of(APRIL_17, DAILY_RATE, APRIL_22, DAILY_RATE, APRIL_23, DAILY_RATE,
                        APRIL_17.plusDays(1), BigDecimal.TEN, APRIL_17.plusDays(2), BigDecimal.TEN,
                        APRIL_17.plusDays(4), BigDecimal.TEN, APRIL_24, BigDecimal.TEN)
        );

        assertValuation(result, expectedValue, true);
    }

    @Test
    void compoundsSelicAndAnnualSpreadOnEachCoveredDay() {
        Asset asset = asset(FixedIncomeIndexer.SELIC, FixedIncomeRateType.SPREAD, "0.15");

        PostFixedValuation result = calculatorOn(APRIL_24).calculateCurrentValue(
                asset, PRINCIPAL, APRIL_17, Map.of(APRIL_17, DAILY_RATE, APRIL_22, DAILY_RATE, APRIL_23, DAILY_RATE)
        );

        assertValuation(result, "1001.66878105", true);
    }

    @Test
    void proratesFirstIpcaMonthAndAccruesSpreadThroughUnpublishedMonth() {
        Asset asset = asset(FixedIncomeIndexer.IPCA, FixedIncomeRateType.SPREAD, "6.5");
        LocalDate purchaseDate = LocalDate.of(2025, 1, 16);
        LocalDate today = LocalDate.of(2025, 3, 6);

        // 12 of January's 22 business days, all of February, and 33 business days of spread.
        PostFixedValuation result = calculatorOn(today).calculateCurrentValue(
                asset, PRINCIPAL, purchaseDate, Map.of(
                        LocalDate.of(2025, 1, 1), new BigDecimal("0.6"),
                        LocalDate.of(2025, 2, 1), new BigDecimal("0.8")
                )
        );

        assertValuation(result, "1019.66874804", true);
    }

    @Test
    void capsDailyAccrualAtExclusiveExpirationDate() {
        Asset asset = asset(FixedIncomeIndexer.CDI, FixedIncomeRateType.PERCENTAGE_OF_INDEX, "100");
        asset.setExpirationDate(APRIL_23);

        PostFixedValuation result = calculatorOn(APRIL_24).calculateCurrentValue(
                asset, PRINCIPAL, APRIL_17, Map.of(APRIL_17, DAILY_RATE, APRIL_22, DAILY_RATE, APRIL_23, BigDecimal.TEN)
        );

        assertValuation(result, "1001.10030250", true);
    }

    @Test
    void toleratesOnlyOneRecentMissingDailyObservation() {
        Asset asset = asset(FixedIncomeIndexer.CDI, FixedIncomeRateType.PERCENTAGE_OF_INDEX, "100");
        PostFixedIncomeYieldCalculator calculator = calculatorOn(APRIL_24);

        assertValuation(calculator.calculateCurrentValue(
                asset, PRINCIPAL, APRIL_17, Map.of(APRIL_17, DAILY_RATE, APRIL_22, DAILY_RATE)
        ), "1001.10030250", true);
        assertValuation(calculator.calculateCurrentValue(
                asset, PRINCIPAL, APRIL_17, Map.of(APRIL_17, DAILY_RATE)
        ), "1000.00000000", false);
        assertValuation(calculator.calculateCurrentValue(
                asset, PRINCIPAL, APRIL_23, Map.of()
        ), "1000.00000000", false);
    }

    @Test
    void fallsBackWhenBusinessDayInsideSeriesIsMissing() {
        Asset asset = asset(FixedIncomeIndexer.SELIC, FixedIncomeRateType.SPREAD, "0.15");

        PostFixedValuation result = calculatorOn(APRIL_24).calculateCurrentValue(
                asset, PRINCIPAL, APRIL_17, Map.of(APRIL_17, DAILY_RATE, APRIL_23, DAILY_RATE)
        );

        assertValuation(result, "1000.00000000", false);
    }

    @Test
    void fallsBackWhenClosedIpcaReferenceMonthIsMissing() {
        Asset asset = asset(FixedIncomeIndexer.IPCA, FixedIncomeRateType.SPREAD, "6.5");

        PostFixedValuation result = calculatorOn(LocalDate.of(2025, 3, 6)).calculateCurrentValue(
                asset, PRINCIPAL, LocalDate.of(2025, 1, 16),
                Map.of(LocalDate.of(2025, 1, 1), new BigDecimal("0.6"))
        );

        assertValuation(result, "1000.00000000", false);
    }

    @Test
    void returnsPrincipalWhenNoAccrualIsDue() {
        Asset asset = asset(FixedIncomeIndexer.CDI, FixedIncomeRateType.PERCENTAGE_OF_INDEX, "100");
        PostFixedIncomeYieldCalculator calculator = calculatorOn(APRIL_24);

        assertValuation(calculator.calculateCurrentValue(asset, PRINCIPAL, null, Map.of()), "1000.00000000", true);
        assertValuation(calculator.calculateCurrentValue(asset, PRINCIPAL, APRIL_24, Map.of()), "1000.00000000", true);
        assertValuation(calculator.calculateCurrentValue(asset, BigDecimal.ZERO, APRIL_17, Map.of()), "0.00000000", true);
    }

    private PostFixedIncomeYieldCalculator calculatorOn(LocalDate date) {
        Clock clock = Clock.fixed(date.atTime(15, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return new PostFixedIncomeYieldCalculator(new MarketCalendar(clock));
    }

    private Asset asset(FixedIncomeIndexer indexer, FixedIncomeRateType rateType, String rate) {
        Asset asset = new Asset();
        asset.setIndexer(indexer);
        asset.setRateType(rateType);
        if (rateType == FixedIncomeRateType.SPREAD) {
            asset.setSpreadRate(new BigDecimal(rate));
        } else {
            asset.setAnnualRate(new BigDecimal(rate));
        }
        return asset;
    }

    private void assertValuation(PostFixedValuation result, String expectedValue, boolean reliable) {
        assertThat(result.reliable()).isEqualTo(reliable);
        assertThat(result.currentValue()).isEqualTo(new BigDecimal(expectedValue));
    }
}
