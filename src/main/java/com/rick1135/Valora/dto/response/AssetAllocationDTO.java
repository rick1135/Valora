package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;

public record AssetAllocationDTO(
        String category,
        BigDecimal totalValue,
        BigDecimal percentage
) {
}
