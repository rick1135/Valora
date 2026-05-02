package com.rick1135.Valora.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PortfolioRequestDTO(
        @NotBlank(message = "O nome da carteira e obrigatorio.")
        @Size(max = 100, message = "O nome da carteira deve ter no maximo 100 caracteres.")
        String name,
        @Size(max = 255, message = "A descricao da carteira deve ter no maximo 255 caracteres.")
        String description
) {
}
