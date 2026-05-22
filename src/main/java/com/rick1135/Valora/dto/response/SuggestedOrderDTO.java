package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;

public record SuggestedOrderDTO(
        String category,
        BigDecimal currentAmount,
        BigDecimal idealAmount,
        BigDecimal gapAmount,
        BigDecimal suggestedInvestment,
        BigDecimal currentPrice,
        /**
         * Theoretical whole-share quantity calculated from the category reference price.
         * While targets are defined by AssetCategory, this quantity is based on the
         * category weighted average price and does not represent an executable order for
         * a specific ticker. Phase 9 should replace this approximation with ticker-level
         * suggestions.
         */
        Long theoreticalCategoryQuantity,
        BigDecimal actualInvestmentAmount,
        BigDecimal residualCashAmount,
        boolean marketPriceReliable
) {
}
