package com.rick1135.Valora.service;

import com.rick1135.Valora.entity.MarketHoliday;
import com.rick1135.Valora.repository.MarketHolidayRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MarketCalendar {
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final ZoneOffset API_DATE_ZONE = ZoneOffset.UTC;
    private static final List<MonthDay> FIXED_HOLIDAYS = List.of(
            MonthDay.of(1, 1),
            MonthDay.of(4, 21),
            MonthDay.of(5, 1),
            MonthDay.of(9, 7),
            MonthDay.of(10, 12),
            MonthDay.of(11, 2),
            MonthDay.of(11, 15),
            MonthDay.of(12, 25)
    );

    private final Clock clock;
    private final MarketHolidayRepository marketHolidayRepository;
    private final Map<Integer, Set<LocalDate>> holidaysByYear = new ConcurrentHashMap<>();

    public MarketCalendar(Clock clock) {
        this(clock, (MarketHolidayRepository) null);
    }

    @Autowired
    public MarketCalendar(Clock clock, ObjectProvider<MarketHolidayRepository> repositoryProvider) {
        this(clock, repositoryProvider.getIfAvailable());
    }

    private MarketCalendar(Clock clock, MarketHolidayRepository marketHolidayRepository) {
        this.clock = clock;
        this.marketHolidayRepository = marketHolidayRepository;
    }

    public Instant now() {
        return Instant.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(MARKET_ZONE));
    }

    public LocalDate marketDate(Instant instant) {
        return instant.atZone(MARKET_ZONE).toLocalDate();
    }

    public LocalDate financialDate(Instant instant) {
        return instant.atOffset(API_DATE_ZONE).toLocalDate();
    }

    public Instant endOfMarketDay(LocalDate date) {
        return date.plusDays(1)
                .atStartOfDay(MARKET_ZONE)
                .toInstant()
                .minusNanos(1);
    }

    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !isMarketHoliday(date);
    }

    public boolean isMarketHoliday(LocalDate date) {
        return holidaysByYear.computeIfAbsent(date.getYear(), this::loadHolidays).contains(date);
    }

    public long businessDaysBetween(LocalDate startInclusive, LocalDate endInclusive) {
        if (startInclusive == null || endInclusive == null || startInclusive.isAfter(endInclusive)) {
            return 0;
        }
        long count = startInclusive.datesUntil(endInclusive).filter(this::isBusinessDay).count();
        return isBusinessDay(endInclusive) ? count + 1 : count;
    }

    public LocalDate previousBusinessDay(LocalDate date) {
        return lastBusinessDayOnOrBefore(date.minusDays(1));
    }

    public LocalDate lastBusinessDayOnOrBefore(LocalDate date) {
        while (!isBusinessDay(date)) {
            date = date.minusDays(1);
        }
        return date;
    }

    private Set<LocalDate> loadHolidays(int year) {
        Set<LocalDate> holidays = new HashSet<>();
        FIXED_HOLIDAYS.forEach(holiday -> holidays.add(holiday.atYear(year)));
        if (year >= 2024) {
            holidays.add(LocalDate.of(year, 11, 20));
        }

        LocalDate easter = easterSunday(year);
        holidays.add(easter.minusDays(48));
        holidays.add(easter.minusDays(47));
        holidays.add(easter.minusDays(2));
        holidays.add(easter.plusDays(60));

        if (marketHolidayRepository != null) {
            marketHolidayRepository.findByHolidayDateBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                    .stream()
                    .map(MarketHoliday::getHolidayDate)
                    .forEach(holidays::add);
        }
        return Set.copyOf(holidays);
    }

    private static LocalDate easterSunday(int year) {
        // Meeus/Jones/Butcher Gregorian computus.
        int yearInMetonicCycle = year % 19;
        int century = year / 100;
        int yearOfCentury = year % 100;
        int solarCorrection = century / 4;
        int lunarCorrection = (century - (century + 8) / 25 + 1) / 3;
        int epact = (19 * yearInMetonicCycle + century - solarCorrection - lunarCorrection + 15) % 30;
        int weekdayCorrection = (32 + 2 * (century % 4) + 2 * (yearOfCentury / 4)
                - epact - yearOfCentury % 4) % 7;
        int paschalCorrection = (yearInMetonicCycle + 11 * epact + 22 * weekdayCorrection) / 451;
        int dateCode = epact + weekdayCorrection - 7 * paschalCorrection + 114;
        return LocalDate.of(year, dateCode / 31, dateCode % 31 + 1);
    }
}
