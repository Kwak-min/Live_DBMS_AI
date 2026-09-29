package com.example.monitoring.database.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record DatabaseCreateRequest(
        @NotBlank @Size(max = 100)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Display name, trimmed; 1..100 Unicode code points") String name,
        @NotBlank @Size(max = 253)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "DNS name or IP, without scheme or path") String host,
        @NotNull @Min(1) @Max(65535)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Allowed MariaDB port, 1..65535") Integer port,
        @Size(max = 100)
        @Schema(nullable = true, description = "Optional database name; letters, digits, underscore, dollar, hyphen") String databaseName,
        @NotBlank @Size(max = 100)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY,
                description = "MariaDB username; 1..100 Unicode code points; encrypted at rest") String username,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY,
                description = "MariaDB password; 1..4096 UTF-8 bytes; encrypted at rest") String password,
        @Schema(description = "Defaults to true when omitted") Boolean enabled
) {
}
