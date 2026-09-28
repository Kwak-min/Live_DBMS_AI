package com.example.monitoring.auth.dto;

import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

public record UpdateUserStatusRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Boolean enabled) {
}
