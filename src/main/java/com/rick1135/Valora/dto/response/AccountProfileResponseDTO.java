package com.rick1135.Valora.dto.response;

import com.rick1135.Valora.entity.UserRole;

import java.time.LocalDateTime;
import java.util.UUID;

public record AccountProfileResponseDTO(UUID id, String name, String email, UserRole role, LocalDateTime createdAt) {
}
