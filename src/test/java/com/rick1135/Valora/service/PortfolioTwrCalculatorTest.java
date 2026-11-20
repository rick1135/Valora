package com.rick1135.Valora.service;

import com.rick1135.Valora.service.PortfolioTwrCalculator.DailyObservation;
import com.rick1135.Valora.service.PortfolioTwrCalculator.DailyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        assertThat(results).extracting(DailyResult::segment).containsOnly(1);
        assertThat(results.getLast().quota()).isEqualTo(new BigDecimal("1.320000000000"));
        assertThat(percentage(results)).isEqualTo(new BigDecimal("32.0000"));

        DailyResult beforeReentry = results.get(2);
        assertThat(calculator.calculate(observations.subList(3, 4),
                beforeReentry.observation().totalPatrimony(), beforeReentry.quota()))
                .containsExactly(results.getLast());
    }

    @Test
    void startsANewSegmentAfterTotalLossAndAnEmptyDay() {
        List<DailyResult> results = calculator.calculate(List.of(
                day(0, "100", "100", "0"),
                day(1, "0", "0", "0"),
                day(2, "0", "0", "0"),
                day(3, "50", "100", "0"),
                day(4, "100", "0", "0")
        ));

        assertThat(results.get(1).dailyReturnFactor()).isEqualByComparingTo("0");
        assertThat(results.get(1).quota()).isEqualByComparingTo("0");
        assertThat(results.get(2).dailyReturnFactor()).isEqualByComparingTo("1");
        assertThat(results.get(2).quota()).isEqualByComparingTo("0");
        assertThat(results.get(3).quota()).isEqualByComparingTo("0.5");
        assertThat(results).extracting(DailyResult::segment).containsExactly(1, 1, 1, 2, 2);
        assertThat(calculator.calculatePeriodTwrPercentage(BigDecimal.ONE, results.get(1).quota()))
                .isEqualByComparingTo("-100");
        assertThat(calculator.calculatePeriodTwrPercentage(
                BigDecimal.ONE, results.getLast().quota(), 1, results.getLast().segment())).isNull();
        assertThat(calculator.calculatePeriodTwrPercentage(
                BigDecimal.ONE, results.getLast().quota(), 2, results.getLast().segment())).isEqualByComparingTo("0");
    }

    @ParameterizedTest
    @CsvSource({"200, 2", "50, 0.5"})
    void tracksTheLossAndReentryRegardlessOfThePriorReturn(String patrimony, String quota) {
        var results = calculator.calculate(List.of(
                day(0, patrimony, "100", "0"),
                day(1, "0", "0", "0"),
                day(2, "100", "100", "0")
        ));

        assertThat(results).extracting(DailyResult::segment).containsExactly(1, 1, 2);
        assertThat(results.getFirst().quota()).isEqualByComparingTo(quota);
        assertThat(results.get(1).quota()).isEqualByComparingTo("0");
        assertThat(results.getLast().quota()).isEqualByComparingTo("1");
        assertThat(calculator.calculatePeriodTwrPercentage(
                results.getFirst().quota(), results.getLast().quota(), 1, 2)).isNull();
    }

    @Test
    void compoundsFiftyPercentGainAndLossAfterReentry() {
        var results = calculator.calculate(List.of(day(0, "150", "100", "0"), day(1, "75", "0", "0")),
                BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(percentage(results)).isEqualByComparingTo("-25");
        assertThat(results).extracting(DailyResult::segment).containsOnly(2);
    }

    @ParameterizedTest
    @CsvSource({"110, 110", "100, 120", "120, 100"})
    void separatesSameDayInflowsAndOutflowsRegardlessOfNetFlow(String patrimony, String withdrawal) {
        var results = calculator.calculate(List.of(
                new DailyObservation(start, new BigDecimal(patrimony), BigDecimal.ZERO,
                        new BigDecimal("100"), new BigDecimal(withdrawal), BigDecimal.ZERO, true)),
                new BigDecimal("100"), BigDecimal.ONE);

        assertThat(percentage(results)).isEqualByComparingTo("10");
    }

    @Test
    void returnsNullWithoutAValidPeriodBaselineOrEnd() {
        assertThat(calculator.calculatePeriodTwrPercentage(null, BigDecimal.ONE)).isNull();
        assertThat(calculator.calculatePeriodTwrPercentage(BigDecimal.ONE, null)).isNull();
        assertThat(calculator.calculatePeriodTwrPercentage(BigDecimal.ZERO, BigDecimal.ONE)).isNull();
        assertThat(calculator.calculatePeriodTwrPercentage(BigDecimal.ONE.negate(), BigDecimal.ONE)).isNull();
    }

    private BigDecimal percentage(List<DailyResult> results) {
        return calculator.calculatePeriodTwrPercentage(BigDecimal.ONE, results.getLast().quota());
    }

    private DailyObservation day(int offset, String patrimony, String cashFlow, String provents) {
        BigDecimal flow = new BigDecimal(cashFlow);
        return new DailyObservation(start.plusDays(offset), new BigDecimal(patrimony), BigDecimal.ZERO,
                flow.max(BigDecimal.ZERO), flow.negate().max(BigDecimal.ZERO), new BigDecimal(provents), true);
    }
}
