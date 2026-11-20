package com.rick1135.Valora.service;

import com.rick1135.Valora.service.PortfolioTwrCalculator.DailyObservation;
import com.rick1135.Valora.service.PortfolioTwrCalculator.DailyResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioTwrCalculatorTest {
    private final PortfolioTwrCalculator calculator = new PortfolioTwrCalculator();
    private final LocalDate start = LocalDate.of(2026, 2, 2);

    @Test
    void largeMidPeriodDepositDoesNotInflateReturn() {
        List<DailyResult> results = calculator.calculate(List.of(
                day(0, "100", "100", "0"),
                day(1, "110", "0", "0"),
                day(2, "1100", "890", "0")
        ));

        assertThat(results.getLast().dailyReturnFactor()).isEqualTo(new BigDecimal("1.100000000000"));
        assertThat(percentage(results)).isEqualTo(new BigDecimal("21.0000"));
    }

    @Test
    void recognizesFirstDayReturnAgainstOpeningContribution() {
        List<DailyResult> results = calculator.calculate(List.of(day(0, "110", "100", "0")));

        assertThat(percentage(results)).isEqualTo(new BigDecimal("10.0000"));
    }

    @Test
    void treatsPartialWithdrawalAndFullLiquidationAsEndOfDayCashFlows() {
        List<DailyResult> results = calculator.calculate(List.of(
                day(0, "100", "100", "0"),
                day(1, "65", "-50", "0"),
                day(2, "0", "-71.50", "0")
        ));

        assertThat(results.get(1).dailyReturnFactor()).isEqualTo(new BigDecimal("1.150000000000"));
        assertThat(results.getLast().dailyReturnFactor()).isEqualTo(new BigDecimal("1.100000000000"));
        assertThat(percentage(results)).isEqualTo(new BigDecimal("26.5000"));
    }

    @Test
    void includesPaidProventsInTheReturnNumerator() {
        List<DailyResult> results = calculator.calculate(List.of(
                day(0, "100", "100", "0"),
                day(1, "90", "0", "12")
        ));

        assertThat(percentage(results)).isEqualTo(new BigDecimal("2.0000"));
    }

    @Test
    void carriesQuotaAcrossEmptyDaysAndMeasuresReturnOnReentry() {
        List<DailyObservation> observations = List.of(
                day(0, "100", "100", "0"),
                day(1, "0", "-120", "0"),
                day(2, "0", "0", "0"),
                day(3, "220", "200", "0")
        );
        List<DailyResult> results = calculator.calculate(observations);

        assertThat(results.get(2).dailyReturnFactor()).isEqualTo(new BigDecimal("1.000000000000"));
        assertThat(results.getLast().quota()).isEqualTo(new BigDecimal("1.320000000000"));
        assertThat(percentage(results)).isEqualTo(new BigDecimal("32.0000"));

        DailyResult beforeReentry = results.get(2);
        assertThat(calculator.calculate(observations.subList(3, 4),
                beforeReentry.observation().totalPatrimony(), beforeReentry.quota()))
                .containsExactly(results.getLast());
    }

    @Test
    void keepsFactorsAndQuotasPositiveAfterTotalLoss() {
        List<DailyResult> results = calculator.calculate(List.of(
                day(0, "100", "100", "0"),
                day(1, "0", "0", "0"),
                day(2, "1", "100", "0")
        ));

        assertThat(results.get(1).dailyReturnFactor()).isEqualTo(new BigDecimal("0.000000000001"));
        assertThat(results.getLast().quota()).isEqualTo(new BigDecimal("0.000000000001"));
        assertThat(percentage(results)).isEqualTo(new BigDecimal("-100.0000"));
    }

    private BigDecimal percentage(List<DailyResult> results) {
        return calculator.calculatePeriodTwrPercentage(BigDecimal.ONE, results.getLast().quota());
    }

    private DailyObservation day(int offset, String patrimony, String cashFlow, String provents) {
        return new DailyObservation(start.plusDays(offset), new BigDecimal(patrimony), BigDecimal.ZERO,
                new BigDecimal(cashFlow), new BigDecimal(provents), true);
    }
}
