package com.rick1135.Valora.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

@Component
public class MarketCalendar {
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final ZoneOffset API_DATE_ZONE = ZoneOffset.UTC;

    private final Clock clock;

    public MarketCalendar(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(MARKET_ZONE));
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
}
