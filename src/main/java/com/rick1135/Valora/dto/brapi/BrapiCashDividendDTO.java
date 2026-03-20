package com.rick1135.Valora.dto.brapi;

import java.math.BigDecimal;
import java.time.Instant;

public record BrapiCashDividendDTO(
        String assetIssued,
        Instant paymentDate,
        BigDecimal rate,
        String relatedTo,
        Instant approvedOn,
        String isinCode,
        String label,
        Instant lastDatePrior,
        String remarks
) {
}
