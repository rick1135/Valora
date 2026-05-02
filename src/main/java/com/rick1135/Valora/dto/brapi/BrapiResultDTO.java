package com.rick1135.Valora.dto.brapi;

import java.math.BigDecimal;

public record BrapiResultDTO(
        String symbol,
        BigDecimal regularMarketPrice,
        BigDecimal regularMarketChangePercent,
        Long regularMarketVolume,
        BigDecimal regularMarketDayHigh,
        BigDecimal regularMarketDayLow,
        BrapiDividendsDataDTO dividendsData
) {
    public BrapiResultDTO(String symbol, BigDecimal regularMarketPrice) {
        this(symbol, regularMarketPrice, null, null, null, null, null);
    }

    public BrapiResultDTO(String symbol, BigDecimal regularMarketPrice, BrapiDividendsDataDTO dividendsData) {
        this(symbol, regularMarketPrice, null, null, null, null, dividendsData);
    }

    public BrapiResultDTO(
            String symbol,
            BigDecimal regularMarketPrice,
            BigDecimal regularMarketChangePercent,
            Long regularMarketVolume,
            BigDecimal regularMarketDayHigh,
            BigDecimal regularMarketDayLow
    ) {
        this(symbol, regularMarketPrice, regularMarketChangePercent, regularMarketVolume, regularMarketDayHigh, regularMarketDayLow, null);
    }
}
