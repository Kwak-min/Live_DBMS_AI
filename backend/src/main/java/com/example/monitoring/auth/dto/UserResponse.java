package com.example.monitoring.auth.dto;

import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Safe public account representation: never expose password hashes or authVersion. */
public record UserResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Positive account id") Long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"USER", "ADMIN"}) UserRole role,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UTC creation time") Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UTC last update time") Instant updatedAt
) {
    public static UserResponse from(UserAccount user) {
        return new UserResponse(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(), user.isEnabled(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
