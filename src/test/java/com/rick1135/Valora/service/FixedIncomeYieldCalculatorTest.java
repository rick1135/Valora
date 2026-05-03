package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

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
}
