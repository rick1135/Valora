package com.rick1135.Valora.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequestDTO(
        @NotBlank(message = "Nome e obrigatorio.")
        @Size(min = 2, max = 120, message = "Nome deve ter entre 2 e 120 caracteres.")
        String name
) {
}
