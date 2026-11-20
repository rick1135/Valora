package com.rick1135.Valora.dto.response;

public record TransactionImportResultDTO(
        int totalRows,
        int importedCount,
        int skippedDuplicatesCount
) {
}
