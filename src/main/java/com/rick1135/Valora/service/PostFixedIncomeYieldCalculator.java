package com.rick1135.Valora.service;

import ch.obermuhlner.math.big.BigDecimalMath;
import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.FixedIncomeRateType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

@Component
public class PostFixedIncomeYieldCalculator {

    private static final MathContext MATH_CONTEXT = new MathContext(32, FinancialConstants.DEFAULT_ROUNDING);
    private static final BigDecimal BUSINESS_DAYS_PER_YEAR = BigDecimal.valueOf(252);

    private final MarketCalendar marketCalendar;

    @Autowired
    public PostFixedIncomeYieldCalculator(MarketCalendar marketCalendar) {
        this.marketCalendar = marketCalendar;
    }

    public PostFixedIncomeYieldCalculator() {
        this(new MarketCalendar(Clock.systemDefaultZone()));
    }

    public record PostFixedValuation(BigDecimal currentValue, boolean reliable, LocalDate valuedThrough) {
        public PostFixedValuation(BigDecimal currentValue, boolean reliable) {
            this(currentValue, reliable, null);
        }
    }

    /**
     * Benchmark rates for CDI and SELIC are percentages per day keyed by observation date.
     * For daily accrual, {@code valuedThrough} is the last date whose rate was applied.
     * IPCA falls back to principal until the contractual monetary adjustment convention
     * (including anniversary and lag) and ANBIMA projections are modeled.
     */
    public PostFixedValuation calculateCurrentValue(
            Asset asset,
            BigDecimal principal,
            LocalDate purchaseDate,
            Map<LocalDate, BigDecimal> benchmarkRates
    ) {
        if (!supportsRateModel(asset)) {
            return valuation(principal, false, null);
        }
        LocalDate today = marketCalendar.today();
        LocalDate endDate = effectiveEndDate(today, asset.getExpirationDate());
        if (purchaseDate == null) {
            return valuation(principal, true, null);
        }
        if (!purchaseDate.isBefore(endDate)) {
            return valuation(principal, true, endDate);
        }
        if (principal.signum() <= 0) {
            return valuation(principal, true, null);
        }
        return dailyValuation(asset, principal, purchaseDate, endDate, today, benchmarkRates);
    }

    static LocalDate effectiveEndDate(LocalDate today, LocalDate expirationDate) {
        return expirationDate != null && expirationDate.isBefore(today) ? expirationDate : today;
    }

    static boolean supportsRateModel(Asset asset) {
        FixedIncomeIndexer indexer = asset.getIndexer();
        if (indexer != FixedIncomeIndexer.CDI && indexer != FixedIncomeIndexer.SELIC) {
            return false;
        }
        if (asset.getRateType() == FixedIncomeRateType.PERCENTAGE_OF_INDEX) {
            return asset.getAnnualRate() != null && asset.getAnnualRate().signum() > 0;
        }
        return asset.getRateType() == FixedIncomeRateType.SPREAD
                && asset.getSpreadRate() != null
                && asset.getSpreadRate().signum() >= 0;
    }

    private PostFixedValuation dailyValuation(
            Asset asset,
            BigDecimal principal,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate today,
            Map<LocalDate, BigDecimal> rates
    ) {
        boolean spread = asset.getRateType() == FixedIncomeRateType.SPREAD;
        BigDecimal indexMultiplier = spread ? BigDecimal.ONE : asset.getAnnualRate().movePointLeft(2);
        BigDecimal spreadFactor = spread ? dailySpreadFactor(asset.getSpreadRate()) : BigDecimal.ONE;
        LocalDate latestBusinessDay = marketCalendar.previousBusinessDay(today);
        BigDecimal accumulated = BigDecimal.ONE;
        LocalDate lastCoveredDate = null;
        for (LocalDate date = startDate; date.isBefore(endDate); date = date.plusDays(1)) {
            if (!marketCalendar.isBusinessDay(date)) {
                continue;
            }
            BigDecimal rate = rates.get(date);
            if (rate == null) {
                // Only the latest daily observation can be delayed by publication.
                if (lastCoveredDate != null && date.equals(latestBusinessDay)) {
                    break;
                }
                return valuation(principal, false, null);
            }
            BigDecimal indexFactor = rateFactor(rate.multiply(indexMultiplier));
            if (indexFactor.signum() <= 0) {
                return valuation(principal, false, null);
            }
            accumulated = accumulated.multiply(indexFactor, MATH_CONTEXT).multiply(spreadFactor, MATH_CONTEXT);
            lastCoveredDate = date;
        }
        LocalDate valuedThrough = lastCoveredDate == null ? startDate : lastCoveredDate;
        return valuation(principal.multiply(accumulated), true, valuedThrough);
    }

    private static BigDecimal dailySpreadFactor(BigDecimal spreadRate) {
        BigDecimal exponent = BigDecimal.ONE.divide(BUSINESS_DAYS_PER_YEAR, MATH_CONTEXT);
        return BigDecimalMath.pow(rateFactor(spreadRate), exponent, MATH_CONTEXT);
    }

    private static BigDecimal rateFactor(BigDecimal percentage) {
        return BigDecimal.ONE.add(percentage.movePointLeft(2));
    }

    private static PostFixedValuation valuation(BigDecimal value, boolean reliable, LocalDate valuedThrough) {
        return new PostFixedValuation(
                value.setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING),
                reliable,
                valuedThrough
        );
    }
}
