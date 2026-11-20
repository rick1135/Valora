package com.rick1135.Valora.service;

import com.rick1135.Valora.entity.MarketHoliday;
import com.rick1135.Valora.repository.MarketHolidayRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketCalendarTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-20T12:00:00Z"), ZoneOffset.UTC);
    private final MarketCalendar marketCalendar = new MarketCalendar(clock);

    @Test
    void financialDateShouldUseUtcDateFromApiInstant() {
        assertThat(marketCalendar.financialDate(Instant.parse("2026-03-20T00:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 3, 20));
    }

    @Test
    void endOfMarketDayShouldReturnLastInstantOfSaoPauloDate() {
        assertThat(marketCalendar.endOfMarketDay(LocalDate.of(2026, 3, 20)))
                .isEqualTo(Instant.parse("2026-03-21T02:59:59.999999999Z"));
    }

    @Test
    void todayShouldUseMarketZone() {
        assertThat(marketCalendar.today()).isEqualTo(LocalDate.of(2026, 3, 20));
    }

    @Test
    void marketDateShouldConvertUtcInstantToSaoPauloDate() {
        assertThat(marketCalendar.marketDate(Instant.parse("2026-03-21T01:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 3, 20));
    }

    @Test
    void nowShouldReturnClockInstant() {
        assertThat(marketCalendar.now()).isEqualTo(Instant.parse("2026-03-20T12:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-01-01", "2026-04-21", "2026-05-01", "2026-09-07", "2026-10-12",
            "2026-11-02", "2026-11-15", "2026-12-25"
    })
    void shouldRecognizeFixedHolidays(String holiday) {
        LocalDate date = LocalDate.parse(holiday);
        assertThat(marketCalendar.isMarketHoliday(date)).isTrue();
        assertThat(marketCalendar.isBusinessDay(date)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-02-16", "2026-02-17", "2026-04-03", "2026-06-04",
            "2031-02-24", "2031-02-25", "2031-04-11", "2031-06-12"
    })
    void shouldCalculateMovableHolidaysIncludingYearsOutsideTheSeed(String holiday) {
        LocalDate date = LocalDate.parse(holiday);
        assertThat(marketCalendar.isMarketHoliday(date)).isTrue();
        assertThat(marketCalendar.isBusinessDay(date)).isFalse();
    }

    @Test
    void shouldRecognizeBlackAwarenessDayStartingIn2024() {
        LocalDate beforeNationalHoliday = LocalDate.of(2023, 11, 20);
        assertThat(marketCalendar.isMarketHoliday(beforeNationalHoliday)).isFalse();
        assertThat(marketCalendar.isBusinessDay(beforeNationalHoliday)).isTrue();
        assertThat(marketCalendar.isMarketHoliday(LocalDate.of(2024, 11, 20))).isTrue();
        assertThat(marketCalendar.isMarketHoliday(LocalDate.of(2025, 11, 20))).isTrue();
    }

    @Test
    void shouldRecognizeWeekendsAndOrdinaryBusinessDays() {
        assertThat(marketCalendar.isBusinessDay(LocalDate.of(2026, 2, 14))).isFalse();
        assertThat(marketCalendar.isBusinessDay(LocalDate.of(2026, 2, 15))).isFalse();
        assertThat(marketCalendar.isBusinessDay(LocalDate.of(2026, 2, 18))).isTrue();
    }

    @Test
    void shouldCountBusinessDaysInclusively() {
        LocalDate friday = LocalDate.of(2026, 2, 13);
        LocalDate wednesday = LocalDate.of(2026, 2, 18);

        assertThat(marketCalendar.businessDaysBetween(friday, wednesday)).isEqualTo(2);
        assertThat(marketCalendar.businessDaysBetween(wednesday, wednesday)).isEqualTo(1);
        assertThat(marketCalendar.businessDaysBetween(friday.plusDays(1), wednesday.minusDays(1))).isZero();
        assertThat(marketCalendar.businessDaysBetween(wednesday, friday)).isZero();
    }

    @Test
    void shouldFindPreviousBusinessDayAcrossCarnival() {
        LocalDate friday = LocalDate.of(2026, 2, 13);
        LocalDate wednesday = LocalDate.of(2026, 2, 18);

        assertThat(marketCalendar.previousBusinessDay(wednesday)).isEqualTo(friday);
        assertThat(marketCalendar.lastBusinessDayOnOrBefore(wednesday.minusDays(1))).isEqualTo(friday);
        assertThat(marketCalendar.lastBusinessDayOnOrBefore(wednesday)).isEqualTo(wednesday);
    }

    @Test
    void shouldCombineRepositoryAndCalculatedHolidaysAndCacheTheYear() {
        LocalDate additionalHoliday = LocalDate.of(2026, 2, 18);
        MarketHoliday holiday = new MarketHoliday();
        holiday.setHolidayDate(additionalHoliday);
        MarketHolidayRepository repository = mock(MarketHolidayRepository.class);
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        when(repository.findByHolidayDateBetween(start, end)).thenReturn(List.of(holiday));
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("marketHolidayRepository", repository));
        MarketCalendar calendarWithRepository = new MarketCalendar(
                clock, beans.getBeanProvider(MarketHolidayRepository.class));

        assertThat(calendarWithRepository.isMarketHoliday(additionalHoliday)).isTrue();
        assertThat(calendarWithRepository.isMarketHoliday(additionalHoliday.minusDays(1))).isTrue();
        assertThat(calendarWithRepository.isBusinessDay(additionalHoliday.plusDays(1))).isTrue();
        verify(repository).findByHolidayDateBetween(start, end);
    }
}
