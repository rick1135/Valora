package com.rick1135.Valora.dto.response;

import com.rick1135.Valora.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TransactionImportPreviewDTO(
        int totalRows,
        int validCount,
        int errorCount,
        List<String> missingTickers,
        List<PreviewRow> validRows,
        List<RowError> errors
) {
    public record PreviewRow(
            int lineNumber,
            LocalDate date,
            String ticker,
            TransactionType type,
            BigDecimal quantity,
            BigDecimal unitPrice
    ) {
    }

    public record RowError(int lineNumber, String message) {
    }
}
