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
import java.time.YearMonth;
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

    public record PostFixedValuation(BigDecimal currentValue, boolean reliable) {
    }

    /**
     * Benchmark rates are percentages per day for CDI and SELIC, and percentages
     * per month for IPCA, keyed by the first day of the reference month.
     */
    public PostFixedValuation calculateCurrentValue(
            Asset asset,
            BigDecimal principal,
            LocalDate purchaseDate,
            Map<LocalDate, BigDecimal> benchmarkRates
    ) {
        LocalDate today = marketCalendar.today();
        LocalDate endDate = effectiveEndDate(today, asset.getExpirationDate());
        if (purchaseDate == null || !purchaseDate.isBefore(endDate) || principal.signum() <= 0) {
            return valuation(principal, true);
        }
        if (!supportsRateModel(asset)) {
            return valuation(principal, false);
        }

        BigDecimal factor = asset.getIndexer() == FixedIncomeIndexer.IPCA
                ? inflationFactor(purchaseDate, endDate, asset.getSpreadRate(), benchmarkRates)
                : dailyFactor(asset, purchaseDate, endDate, today, benchmarkRates);
        return factor == null ? valuation(principal, false) : valuation(principal.multiply(factor), true);
    }

    static LocalDate effectiveEndDate(LocalDate today, LocalDate expirationDate) {
        return expirationDate != null && expirationDate.isBefore(today) ? expirationDate : today;
    }

    static boolean supportsRateModel(Asset asset) {
        FixedIncomeIndexer indexer = asset.getIndexer();
        boolean dailyIndexer = indexer == FixedIncomeIndexer.CDI || indexer == FixedIncomeIndexer.SELIC;
        if (asset.getRateType() == FixedIncomeRateType.PERCENTAGE_OF_INDEX) {
            return dailyIndexer && asset.getAnnualRate() != null;
        }
        return asset.getRateType() == FixedIncomeRateType.SPREAD
                && (dailyIndexer || indexer == FixedIncomeIndexer.IPCA)
                && asset.getSpreadRate() != null
                && rateFactor(asset.getSpreadRate()).signum() > 0;
    }

    private BigDecimal dailyFactor(
            Asset asset,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate today,
            Map<LocalDate, BigDecimal> rates
    ) {
        boolean spread = asset.getRateType() == FixedIncomeRateType.SPREAD;
        BigDecimal indexMultiplier = spread ? BigDecimal.ONE : asset.getAnnualRate().movePointLeft(2);
        BigDecimal spreadFactor = spread ? annualSpreadFactor(asset.getSpreadRate(), 1) : BigDecimal.ONE;
        LocalDate latestBusinessDay = marketCalendar.previousBusinessDay(today);
        BigDecimal accumulated = BigDecimal.ONE;
        boolean hasCoveredDay = false;
        for (LocalDate date = startDate; date.isBefore(endDate); date = date.plusDays(1)) {
            if (!marketCalendar.isBusinessDay(date)) {
                continue;
            }
            BigDecimal rate = rates.get(date);
            if (rate == null) {
                // Only the latest daily observation can be delayed by publication.
                if (hasCoveredDay && date.equals(latestBusinessDay)) {
                    continue;
                }
                return null;
            }
            BigDecimal indexFactor = rateFactor(rate.multiply(indexMultiplier));
            if (indexFactor.signum() <= 0) {
                return null;
            }
            accumulated = accumulated.multiply(indexFactor, MATH_CONTEXT).multiply(spreadFactor, MATH_CONTEXT);
            hasCoveredDay = true;
        }
        return accumulated;
    }

    private BigDecimal inflationFactor(
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal spreadRate,
            Map<LocalDate, BigDecimal> rates
    ) {
        long businessDays = marketCalendar.businessDaysBetween(startDate, endDate.minusDays(1));
        if (businessDays == 0) {
            return BigDecimal.ONE;
        }

        BigDecimal accumulated = annualSpreadFactor(spreadRate, businessDays);
        LocalDate periodStart = startDate;
        for (YearMonth month = YearMonth.from(startDate); periodStart.isBefore(endDate); month = month.plusMonths(1)) {
            LocalDate nextMonth = month.plusMonths(1).atDay(1);
            boolean fullMonthEnded = !nextMonth.isAfter(endDate);
            BigDecimal rate = rates.get(month.atDay(1));
            if (rate == null) {
                return fullMonthEnded ? null : accumulated;
            }
            BigDecimal monthlyFactor = rateFactor(rate);
            if (monthlyFactor.signum() <= 0) {
                return null;
            }
            if (!fullMonthEnded || periodStart.getDayOfMonth() != 1) {
                LocalDate periodEnd = fullMonthEnded ? nextMonth : endDate;
                long coveredDays = marketCalendar.businessDaysBetween(periodStart, periodEnd.minusDays(1));
                long monthDays = marketCalendar.businessDaysBetween(month.atDay(1), month.atEndOfMonth());
                BigDecimal fraction = BigDecimal.valueOf(coveredDays).divide(BigDecimal.valueOf(monthDays), MATH_CONTEXT);
                monthlyFactor = BigDecimalMath.pow(monthlyFactor, fraction, MATH_CONTEXT);
            }
            accumulated = accumulated.multiply(monthlyFactor, MATH_CONTEXT);
            periodStart = nextMonth;
        }
        return accumulated;
    }

    private static BigDecimal annualSpreadFactor(BigDecimal spreadRate, long businessDays) {
        BigDecimal exponent = BigDecimal.valueOf(businessDays).divide(BUSINESS_DAYS_PER_YEAR, MATH_CONTEXT);
        return BigDecimalMath.pow(rateFactor(spreadRate), exponent, MATH_CONTEXT);
    }

    private static BigDecimal rateFactor(BigDecimal percentage) {
        return BigDecimal.ONE.add(percentage.movePointLeft(2));
    }

    private static PostFixedValuation valuation(BigDecimal value, boolean reliable) {
        return new PostFixedValuation(
                value.setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING),
                reliable
        );
    }
}
