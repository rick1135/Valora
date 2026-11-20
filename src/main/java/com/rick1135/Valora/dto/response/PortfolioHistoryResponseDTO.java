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
        Map<String, List<BenchmarkPointDTO>> benchmarks,
        Map<String, Boolean> benchmarksComplete
) {
    public PortfolioHistoryResponseDTO(
            String range, LocalDate startDate, LocalDate endDate, BigDecimal twrPercentage,
            boolean pricingComplete, List<HistoryPointDTO> points, Map<String, List<BenchmarkPointDTO>> benchmarks
    ) {
        this(range, startDate, endDate, twrPercentage, pricingComplete, points, benchmarks, Map.of());
    }

    public record HistoryPointDTO(
            LocalDate date,
            BigDecimal totalPatrimony,
            BigDecimal totalInvested,
            BigDecimal twrPercentage,
            boolean pricingComplete,
            int twrSegment
    ) {
        public HistoryPointDTO(LocalDate date, BigDecimal totalPatrimony, BigDecimal totalInvested,
                               BigDecimal twrPercentage, boolean pricingComplete) {
            this(date, totalPatrimony, totalInvested, twrPercentage, pricingComplete, 1);
        }
    }

    public record BenchmarkPointDTO(
            LocalDate date,
            BigDecimal percentage
    ) {}
}
