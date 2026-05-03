package com.rick1135.Valora.dto.response;

import com.rick1135.Valora.entity.ProventRateBasis;
import com.rick1135.Valora.entity.ProventSource;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.entity.ProventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ProventProvisionResponseDTO(
        UUID provisionId,
        UUID portfolioId,
        UUID proventId,
        UUID assetId,
        String ticker,
        ProventType type,
        BigDecimal amountPerShare,
        LocalDate comDate,
        LocalDate paymentDate,
        BigDecimal quantityOnComDate,
        BigDecimal grossAmount,
        BigDecimal withholdingTaxAmount,
        BigDecimal netAmount,
        ProventStatus status,
        ProventSource originSource,
        String originEventKey,
        ProventRateBasis originRateBasis
) {
}
