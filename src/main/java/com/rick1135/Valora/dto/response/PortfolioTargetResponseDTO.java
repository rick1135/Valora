package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record PortfolioTargetResponseDTO(
        UUID id,
        String assetCategory,
        UUID assetId,
        String ticker,
        String assetName,
        BigDecimal targetPercentage
) {
}
