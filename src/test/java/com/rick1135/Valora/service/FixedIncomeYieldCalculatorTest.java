package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FixedIncomeYieldCalculatorTest {

    private final FixedIncomeYieldCalculator calculator = new FixedIncomeYieldCalculator();

    @Test
    void calculateCurrentValueShouldApplyCompoundInterestCorrectlyAfterOneYear() {
        BigDecimal averagePrice = new BigDecimal("1000.00");
        BigDecimal quantity = BigDecimal.ONE;
        LocalDate purchaseDate = LocalDate.now().minusYears(1);
        BigDecimal annualRate = new BigDecimal("10.00"); // 10%

        BigDecimal currentValue = calculator.calculateCurrentValue(averagePrice, quantity, purchaseDate, annualRate);

        // Expected: 1000 * (1 + 0.10)^1 = 1100
        assertThat(currentValue).isEqualByComparingTo("1100.00000000");
    }

    @Test
    void calculateCurrentValueShouldReturnPrincipalWhenDateIsToday() {
        BigDecimal averagePrice = new BigDecimal("1000.00");
        BigDecimal quantity = BigDecimal.ONE;
        LocalDate purchaseDate = LocalDate.now();
        BigDecimal annualRate = new BigDecimal("10.00");

        BigDecimal currentValue = calculator.calculateCurrentValue(averagePrice, quantity, purchaseDate, annualRate);

        assertThat(currentValue).isEqualByComparingTo("1000.00000000");
    }

    @Test
    void calculateCurrentValueShouldHandleZeroRate() {
        BigDecimal averagePrice = new BigDecimal("1000.00");
        BigDecimal quantity = BigDecimal.ONE;
        LocalDate purchaseDate = LocalDate.now().minusYears(1);
        BigDecimal annualRate = BigDecimal.ZERO;

        BigDecimal currentValue = calculator.calculateCurrentValue(averagePrice, quantity, purchaseDate, annualRate);

        assertThat(currentValue).isEqualByComparingTo("1000.00000000");
    }

    @Test
    void calculateCurrentValueShouldCapAccrualAtExpirationDateWhenMatured() {
        MarketCalendar fixedCalendar = new MarketCalendar(
                Clock.fixed(Instant.parse("2026-06-01T15:00:00Z"), ZoneOffset.UTC)
        );
        FixedIncomeYieldCalculator fixedCalculator = new FixedIncomeYieldCalculator(fixedCalendar);

        LocalDate purchaseDate = LocalDate.of(2025, 1, 1);
        LocalDate expirationDate = LocalDate.of(2026, 1, 1);

        BigDecimal currentValue = fixedCalculator.calculateCurrentValue(
                new BigDecimal("1000.00"),
                BigDecimal.ONE,
                purchaseDate,
                new BigDecimal("10.00"),
                expirationDate
        );

        // Must stop accruing on 2026-01-01 (365 days = 1 year -> 1100.00), NOT continue until 2026-06-01
        assertThat(currentValue).isEqualByComparingTo("1100.00000000");
    }

    @Test
    void calculateCurrentValueShouldAccrueUntilTodayWhenExpirationDateIsInFuture() {
        MarketCalendar fixedCalendar = new MarketCalendar(
                Clock.fixed(Instant.parse("2026-01-01T15:00:00Z"), ZoneOffset.UTC)
        );
        FixedIncomeYieldCalculator fixedCalculator = new FixedIncomeYieldCalculator(fixedCalendar);

        BigDecimal currentValue = fixedCalculator.calculateCurrentValue(
                new BigDecimal("1000.00"),
                BigDecimal.ONE,
                LocalDate.of(2025, 1, 1),
                new BigDecimal("10.00"),
                LocalDate.of(2028, 1, 1)
        );

        assertThat(currentValue).isEqualByComparingTo("1100.00000000");
    }

    @Test
    void calculateCurrentValueShouldReturnPrincipalWhenPurchaseDateIsNull() {
        BigDecimal currentValue = calculator.calculateCurrentValue(
                new BigDecimal("1000.00"),
                new BigDecimal("2"),
                null,
                new BigDecimal("10.00"),
                LocalDate.now().plusYears(1)
        );

        assertThat(currentValue).isEqualByComparingTo("2000.00000000");
    }
}
