package com.rick1135.Valora.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record PortfolioResponseDTO(
        UUID id,
        String name,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
