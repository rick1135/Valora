package com.rick1135.Valora.dto.request;

import com.rick1135.Valora.entity.ProventType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ProventRequestDTO(
        @NotNull(message = "O ID do ativo e obrigatorio.")
        UUID assetId,
        @NotNull(message = "O tipo de provento e obrigatorio.")
        ProventType type,
        @NotNull(message = "O valor por cota e obrigatorio.")
        @DecimalMin(value = "0.00000001", message = "O valor por cota deve ser positivo.")
        BigDecimal amountPerShare,
        @NotNull(message = "A data COM e obrigatoria.")
        LocalDate comDate,
        @NotNull(message = "A data de pagamento e obrigatoria.")
        LocalDate paymentDate
) {
}
