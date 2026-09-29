package com.example.monitoring.dto;

import com.example.monitoring.domain.TargetDbStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbPingResponseDto {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Long databaseConfigId;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"UP", "DOWN"})
    private TargetDbStatus status;
    @Schema(nullable = true, description = "MariaDB version, null when DOWN")
    private String version;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Diagnostic duration in milliseconds")
    private Long responseTimeMs;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Legacy local date-time; UTC conversion awaits A/B shared DTO migration")
    private Instant timestamp;
    @Schema(nullable = true, description = "AUTH_FAILED, CONNECT_TIMEOUT, CONNECTION_REFUSED, QUERY_FAILED, or UNKNOWN")
    private String errorCode;
    @Schema(nullable = true, description = "Safe diagnostic message")
    private String errorMessage;
}
