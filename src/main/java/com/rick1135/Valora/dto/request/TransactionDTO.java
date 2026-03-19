package com.rick1135.Valora.dto.request;

import com.rick1135.Valora.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionDTO(
        @NotNull(message = "O ID do ativo é obrigatório")
        UUID assetId,
        @NotNull(message = "O tipo de transação é obrigatório")
        TransactionType type,
        @NotNull(message = "A quantidade é obrigatória")
        @DecimalMin(value = "0.00000001", message = "A quantidade deve ser positiva")
        BigDecimal quantity,
        @NotNull(message = "O preço unitário é obrigatório")
        @DecimalMin(value = "0.00000001", message = "Preço unitário deve ser positivo")
        BigDecimal unitPrice,
        @NotNull(message = "Data da transação é obrigatória")
        @PastOrPresent(message = "A data da transação não pode ser futura")
        Instant transactionDate) {
}
