package com.rick1135.Valora.dto.response;

import java.time.LocalDate;

public record BenchmarkStatusResponseDTO(
        String ticker,
        String name,
        String type,
        String provider,
        LocalDate earliestDate,
        LocalDate latestDate,
        long totalRecords,
        long missingBusinessDaysLast30Days,
        boolean upToDate
) {
}
