package com.example.monitoring.auth.dto;

import com.example.monitoring.auth.domain.UserRole;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

public record UpdateUserRoleRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        allowableValues = {"USER", "ADMIN"}) UserRole role) {
}
