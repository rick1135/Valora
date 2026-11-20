package com.rick1135.Valora.dto.response;

import java.time.LocalDate;
import java.util.List;

public record BenchmarkSyncResponseDTO(
        LocalDate startDate,
        LocalDate endDate,
        int benchmarksProcessed,
        int recordsInserted,
        List<String> errors
) {
}
