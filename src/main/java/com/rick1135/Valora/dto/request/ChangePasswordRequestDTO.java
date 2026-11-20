package com.rick1135.Valora.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequestDTO(
        @NotBlank(message = "Senha atual e obrigatoria.")
        String currentPassword,
        @NotBlank(message = "Nova senha e obrigatoria.")
        @Size(min = 8, max = 128, message = "A nova senha deve ter entre 8 e 128 caracteres.")
        String newPassword
) {
}
