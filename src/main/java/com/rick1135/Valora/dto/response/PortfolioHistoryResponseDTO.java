package com.rick1135.Valora.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record PortfolioHistoryResponseDTO(
        String range,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal twrPercentage,
        boolean pricingComplete,
        List<HistoryPointDTO> points,
        Map<String, List<BenchmarkPointDTO>> benchmarks
) {
    public record HistoryPointDTO(
            LocalDate date,
            BigDecimal totalPatrimony,
            BigDecimal totalInvested,
            BigDecimal twrPercentage,
            boolean pricingComplete
    ) {}

    public record BenchmarkPointDTO(
            LocalDate date,
            BigDecimal percentage
    ) {}
}
