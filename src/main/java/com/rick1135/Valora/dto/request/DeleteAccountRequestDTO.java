package com.rick1135.Valora.dto.request;

import jakarta.validation.constraints.NotBlank;

public record DeleteAccountRequestDTO(
        @NotBlank(message = "Senha atual e obrigatoria.")
        String currentPassword
) {
}
