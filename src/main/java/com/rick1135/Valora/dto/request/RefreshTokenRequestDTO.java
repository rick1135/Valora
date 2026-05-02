package com.rick1135.Valora.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequestDTO(
        @NotBlank(message = "Refresh token e obrigatorio.")
        String refreshToken
) {
}
