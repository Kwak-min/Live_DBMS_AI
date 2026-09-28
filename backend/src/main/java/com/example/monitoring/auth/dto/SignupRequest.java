package com.example.monitoring.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Public signup request. The server always assigns USER; role is not accepted.")
public record SignupRequest(
        @NotBlank @Email @Size(max = 254)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Email, trimmed and lowercased; 1..254 characters") String email,
        @NotBlank @Size(max = 100)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Display name, trimmed; 1..100 Unicode code points") String displayName,
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY,
                description = "12..128 Unicode code points, at most 1024 UTF-8 bytes; never trimmed") String password
) {
}
