package com.rick1135.Valora.dto.request;

import com.rick1135.Valora.entity.UserRole;

public record RegisterDTO(String name, String email, String password, UserRole role) {
}
