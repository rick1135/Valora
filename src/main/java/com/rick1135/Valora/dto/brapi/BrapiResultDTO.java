package com.rick1135.Valora.dto.brapi;

import java.math.BigDecimal;

public record BrapiResultDTO(
        String symbol,
        BigDecimal regularMarketPrice,
        BrapiDividendsDataDTO dividendsData
) {
    public BrapiResultDTO(String symbol, BigDecimal regularMarketPrice) {
        this(symbol, regularMarketPrice, null);
    }
}
