package com.rick1135.Valora.service;

import ch.obermuhlner.math.big.BigDecimalMath;
import com.rick1135.Valora.common.FinancialConstants;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Component
public class FixedIncomeYieldCalculator {

    public BigDecimal calculateCurrentValue(BigDecimal averagePrice, BigDecimal quantity, LocalDate purchaseDate, BigDecimal annualRate) {
        if (averagePrice == null || quantity == null || purchaseDate == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal principal = averagePrice.multiply(quantity);

        if (annualRate == null || annualRate.compareTo(BigDecimal.ZERO) == 0) {
            return principal.setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        }

        long days = ChronoUnit.DAYS.between(purchaseDate, LocalDate.now());
        if (days <= 0) {
            return principal.setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        }

        // rate is annualRate / 100
        BigDecimal rate = annualRate.divide(new BigDecimal("100"), FinancialConstants.INTERMEDIATE_CALCULATION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        BigDecimal base = BigDecimal.ONE.add(rate);

        if (base.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Taxa anual invalida: resulta em base negativa ou zero");
        }

        // Formula: FV = PV * (1 + rate)^(days / 365)
        BigDecimal exponent = new BigDecimal(days).divide(new BigDecimal("365"), FinancialConstants.INTERMEDIATE_CALCULATION_SCALE, FinancialConstants.DEFAULT_ROUNDING);

        // Using high precision (32 digits) for intermediate exponentiation to protect decimals in large values
        MathContext mc = new MathContext(32, FinancialConstants.DEFAULT_ROUNDING);
        BigDecimal factor = BigDecimalMath.pow(base, exponent, mc);

        return principal.multiply(factor).setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
    }
}
