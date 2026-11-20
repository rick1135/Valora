package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class PortfolioTwrCalculator {
    private static final int FACTOR_SCALE = 12;
    private static final BigDecimal ONE = BigDecimal.ONE.setScale(FACTOR_SCALE);
    private static final BigDecimal MIN_FACTOR = BigDecimal.ONE.movePointLeft(FACTOR_SCALE);

    public record DailyObservation(
            LocalDate date,
            BigDecimal totalPatrimony,
            BigDecimal totalInvested,
            BigDecimal netCashFlow,
            BigDecimal proventsReceived,
            boolean pricingComplete
    ) {}

    public record DailyResult(DailyObservation observation, BigDecimal dailyReturnFactor, BigDecimal quota) {}

    public List<DailyResult> calculate(List<DailyObservation> observations) {
        return calculate(observations, BigDecimal.ZERO, ONE);
    }

    public List<DailyResult> calculate(
            List<DailyObservation> observations, BigDecimal previousPatrimony, BigDecimal previousQuota
    ) {
        List<DailyResult> results = new ArrayList<>(observations.size());
        for (DailyObservation observation : observations) {
            BigDecimal factor = calculateFactor(observation, previousPatrimony);
            BigDecimal quota = previousQuota.multiply(factor)
                    .setScale(FACTOR_SCALE, FinancialConstants.DEFAULT_ROUNDING)
                    .max(MIN_FACTOR);
            results.add(new DailyResult(observation, factor, quota));
            previousPatrimony = observation.totalPatrimony();
            previousQuota = quota;
        }
        return List.copyOf(results);
    }

    public BigDecimal calculatePeriodTwrPercentage(BigDecimal startQuota, BigDecimal endQuota) {
        return endQuota.subtract(startQuota).movePointRight(2)
                .divide(startQuota, FinancialConstants.PERCENTAGE_SCALE, FinancialConstants.DEFAULT_ROUNDING);
    }

    private BigDecimal calculateFactor(DailyObservation observation, BigDecimal previousPatrimony) {
        BigDecimal cashFlow = observation.netCashFlow();
        BigDecimal proceeds = observation.totalPatrimony().add(observation.proventsReceived());
        if (previousPatrimony.signum() <= 0) {
            return cashFlow.signum() > 0 && proceeds.signum() > 0 ? divide(proceeds, cashFlow) : ONE;
        }
        return cashFlow.signum() < 0
                ? divide(proceeds.subtract(cashFlow), previousPatrimony)
                : divide(proceeds, previousPatrimony.add(cashFlow));
    }

    private BigDecimal divide(BigDecimal proceeds, BigDecimal base) {
        return proceeds.divide(base, FACTOR_SCALE, FinancialConstants.DEFAULT_ROUNDING).max(MIN_FACTOR);
    }
}
