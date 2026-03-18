package com.rick1135.Valora.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterDTO(
        @NotBlank(message = "Nome e obrigatorio.")
        String name,
        @NotBlank(message = "Email e obrigatorio.")
        @Email(message = "Email invalido.")
        String email,
        @NotBlank(message = "Senha e obrigatoria.")
        @Size(min = 8, message = "Senha deve ter no minimo 8 caracteres.")
        String password
) {
}
