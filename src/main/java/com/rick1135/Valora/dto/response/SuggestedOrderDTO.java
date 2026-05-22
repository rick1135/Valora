package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;

public record SuggestedOrderDTO(
        String category,
        BigDecimal currentAmount,
        BigDecimal idealAmount,
        BigDecimal gapAmount,
        BigDecimal suggestedInvestment,
        boolean marketPriceReliable
) {
}
