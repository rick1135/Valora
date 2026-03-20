package com.rick1135.Valora.dto.response;

import com.rick1135.Valora.entity.ProventRateBasis;
import com.rick1135.Valora.entity.ProventSource;
import com.rick1135.Valora.entity.ProventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProventResponseDTO(
        UUID proventId,
        UUID assetId,
        String ticker,
        ProventType type,
        BigDecimal amountPerShare,
        Instant comDate,
        Instant paymentDate,
        int provisionedUsers,
        ProventSource originSource,
        String originEventKey,
        ProventRateBasis originRateBasis
) {
}
