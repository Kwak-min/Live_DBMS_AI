package com.example.monitoring.database.dto;

import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.TargetDbStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** Deliberately excludes username, password, and future encrypted fields. */
public record DatabaseResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String host,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer port,
        @Schema(nullable = true) String databaseName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long configVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"UP", "DOWN", "UNKNOWN", "BLOCKED"},
                description = "BLOCKED is a legacy A-state pending shared v0.2 conversion") TargetDbStatus connectionStatus,
        @Schema(nullable = true, description = "Legacy local date-time; UTC conversion awaits A/B shared DTO migration") LocalDateTime lastAttemptAt,
        @Schema(nullable = true, description = "Legacy local date-time; UTC conversion awaits A/B shared DTO migration") LocalDateTime lastSuccessAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Legacy local date-time; UTC conversion awaits A/B shared DTO migration") LocalDateTime createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Legacy local date-time; UTC conversion awaits A/B shared DTO migration") LocalDateTime updatedAt
) {
    public static DatabaseResponse from(DatabaseConfig config) {
        return new DatabaseResponse(config.getId(), config.getName(), config.getHost(), config.getPort(),
                config.getDatabaseName(), config.getEnabled(), config.getConfigVersion(), config.getStatus(),
                config.getLastCheckedAt(), config.getLastSuccessAt(), config.getCreatedAt(), config.getUpdatedAt());
    }
}
