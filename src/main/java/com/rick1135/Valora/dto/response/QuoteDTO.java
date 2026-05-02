package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;

public record QuoteDTO(
        String symbol,
        BigDecimal price,
        BigDecimal changePercent,
        Long volume,
        BigDecimal high,
        BigDecimal low
) {
}
