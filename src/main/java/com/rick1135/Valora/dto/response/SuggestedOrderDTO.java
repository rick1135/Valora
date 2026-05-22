package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record SuggestedOrderDTO(
        String category,
        UUID assetId,
        String ticker,
        BigDecimal currentAmount,
        BigDecimal idealAmount,
        BigDecimal gapAmount,
        BigDecimal suggestedInvestment,
        BigDecimal currentPrice,
        Long suggestedQuantity,
        BigDecimal actualInvestmentAmount,
        BigDecimal residualCashAmount,
        boolean marketPriceReliable
) {
}
