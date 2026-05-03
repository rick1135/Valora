package com.rick1135.Valora.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MarketCalendarTest {

    private final MarketCalendar marketCalendar = new MarketCalendar(
            Clock.fixed(Instant.parse("2026-03-20T12:00:00Z"), ZoneOffset.UTC)
    );

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
}
