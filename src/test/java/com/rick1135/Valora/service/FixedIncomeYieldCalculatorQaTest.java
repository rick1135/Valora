package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class FixedIncomeYieldCalculatorQaTest {

    private final FixedIncomeYieldCalculator calculator = new FixedIncomeYieldCalculator();

    @Test
    void shouldNotReturnZeroWhenAnnualRateIsNull() {
        BigDecimal averagePrice = new BigDecimal("1000.00");
        BigDecimal quantity = BigDecimal.ONE;
        LocalDate purchaseDate = LocalDate.now().minusYears(1);
        
        BigDecimal currentValue = calculator.calculateCurrentValue(averagePrice, quantity, purchaseDate, null);

        // Principal should be preserved (1000 * 1 = 1000)
        assertThat(currentValue).isEqualByComparingTo("1000.00000000");
    }

    @Test
    void shouldThrowExceptionWhenRateResultsInNegativeBase() {
        BigDecimal averagePrice = new BigDecimal("1000.00");
        BigDecimal quantity = BigDecimal.ONE;
        LocalDate purchaseDate = LocalDate.now().minusYears(1);
        BigDecimal annualRate = new BigDecimal("-110.00"); // -110% results in base -0.1

        assertThatThrownBy(() -> calculator.calculateCurrentValue(averagePrice, quantity, purchaseDate, annualRate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Taxa anual invalida");
    }
    
    @Test
    void shouldMaintainPrecisionOverLongTerm() {
        BigDecimal averagePrice = new BigDecimal("1000000.00"); // 1 Million
        BigDecimal quantity = BigDecimal.ONE;
        // Using exactly 3650 days (10 years of 365 days)
        LocalDate now = LocalDate.now();
        LocalDate purchaseDate = now.minusDays(3650);
        BigDecimal annualRate = new BigDecimal("15.00"); // 15% aa

        BigDecimal currentValue = calculator.calculateCurrentValue(averagePrice, quantity, purchaseDate, annualRate);
        
        // factor = (1.15)^10 = 4.04555773570790859375
        // 1,000,000 * factor = 4045557.73570790859375
        // Rounded to 8 places: 4045557.73570791
        assertThat(currentValue).isEqualByComparingTo("4045557.73570791");
    }
}
