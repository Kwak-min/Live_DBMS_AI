package com.example.monitoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "15-minute Access JWT; keep only in memory") String accessToken,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"Bearer"}) String tokenType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Access token lifetime in seconds", example = "900") long expiresIn,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UserResponse user
) {
    public static final String BEARER = "Bearer";
    public static final long EXPIRES_IN_SECONDS = 900L;
}
