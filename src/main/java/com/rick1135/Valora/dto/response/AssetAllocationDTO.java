package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;

public record AssetAllocationDTO(
        String category,
        BigDecimal totalValue,
        BigDecimal percentage,
        BigDecimal currentPrice,
        boolean marketPriceReliable
) {

    public AssetAllocationDTO(String category, BigDecimal totalValue, BigDecimal percentage) {
        this(category, totalValue, percentage, BigDecimal.ZERO, true);
    }
}
