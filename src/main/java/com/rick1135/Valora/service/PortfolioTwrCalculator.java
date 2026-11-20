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
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(FACTOR_SCALE);

    public record DailyObservation(
            LocalDate date,
            BigDecimal totalPatrimony,
            BigDecimal totalInvested,
            BigDecimal inflows,
            BigDecimal outflows,
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
            boolean reentry = previousQuota.signum() == 0 && previousPatrimony.signum() <= 0
                    && observation.inflows().signum() > 0;
            BigDecimal quota = reentry ? factor : previousQuota.multiply(factor)
                    .setScale(FACTOR_SCALE, FinancialConstants.DEFAULT_ROUNDING);
            results.add(new DailyResult(observation, factor, quota));
            previousPatrimony = observation.totalPatrimony();
            previousQuota = quota;
        }
        return List.copyOf(results);
    }

    public BigDecimal calculatePeriodTwrPercentage(BigDecimal startQuota, BigDecimal endQuota) {
        if (startQuota == null || endQuota == null || startQuota.signum() <= 0) {
            return null;
        }
        return endQuota.subtract(startQuota).movePointRight(2)
                .divide(startQuota, FinancialConstants.PERCENTAGE_SCALE, FinancialConstants.DEFAULT_ROUNDING);
    }

    private BigDecimal calculateFactor(DailyObservation observation, BigDecimal previousPatrimony) {
        BigDecimal base = previousPatrimony.add(observation.inflows());
        if (base.signum() <= 0) {
            return ONE;
        }
        BigDecimal proceeds = observation.totalPatrimony().add(observation.outflows())
                .add(observation.proventsReceived());
        return proceeds.signum() <= 0 ? ZERO
                : proceeds.divide(base, FACTOR_SCALE, FinancialConstants.DEFAULT_ROUNDING);
    }
}
