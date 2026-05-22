package com.rick1135.Valora.dto.brapi;

import java.math.BigDecimal;
import java.util.List;

public record BrapiResultDTO(
        String symbol,
        BigDecimal regularMarketPrice,
        BigDecimal regularMarketChange,
        BigDecimal regularMarketChangePercent,
        Long regularMarketVolume,
        BigDecimal regularMarketDayHigh,
        BigDecimal regularMarketDayLow,
        BrapiDividendsDataDTO dividendsData,
        List<BrapiHistoricalDataDTO> historicalDataPrice
) {
    public BrapiResultDTO(String symbol, BigDecimal regularMarketPrice) {
        this(symbol, regularMarketPrice, null, null, null, null, null, null, null);
    }

    public BrapiResultDTO(String symbol, BigDecimal regularMarketPrice, BrapiDividendsDataDTO dividendsData) {
        this(symbol, regularMarketPrice, null, null, null, null, null, dividendsData, null);
    }

    public BrapiResultDTO(
            String symbol,
            BigDecimal regularMarketPrice,
            BigDecimal regularMarketChangePercent,
            Long regularMarketVolume,
            BigDecimal regularMarketDayHigh,
            BigDecimal regularMarketDayLow
    ) {
        this(symbol, regularMarketPrice, null, regularMarketChangePercent, regularMarketVolume, regularMarketDayHigh, regularMarketDayLow, null, null);
    }

    public BrapiResultDTO(
            String symbol,
            BigDecimal regularMarketPrice,
            BigDecimal regularMarketChange,
            BigDecimal regularMarketChangePercent,
            Long regularMarketVolume,
            BigDecimal regularMarketDayHigh,
            BigDecimal regularMarketDayLow
    ) {
        this(symbol, regularMarketPrice, regularMarketChange, regularMarketChangePercent, regularMarketVolume, regularMarketDayHigh, regularMarketDayLow, null, null);
    }
}
