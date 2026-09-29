package com.example.monitoring.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Email, trimmed and lowercased") String email,
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY,
                description = "Account password; never logged or returned") String password
) {
}
