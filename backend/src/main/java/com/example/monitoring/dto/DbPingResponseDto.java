package com.example.monitoring.dto;

import com.example.monitoring.domain.TargetDbStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** PingResult (docs/api.md 3절). 대상 접속 실패도 200 + DOWN이며 정기 수집 상태를 바꾸지 않는다. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "PingResult", description = "One-off diagnostic result. Every field is always present.")
public class DbPingResponseDto {
    @Schema(requiredMode = REQUIRED, description = "Target database Id")
    private Long databaseConfigId;
    @Schema(requiredMode = REQUIRED, implementation = String.class, allowableValues = {"UP", "DOWN"},
            description = "DOWN when the target could not be reached or queried")
    private TargetDbStatus status;
    @Schema(requiredMode = REQUIRED, nullable = true, description = "MariaDB version; null when DOWN")
    private String version;
    @Schema(requiredMode = REQUIRED,
            description = "Milliseconds for connect + SELECT 1 + SELECT VERSION(), or until failure")
    private Long responseTimeMs;
    @Schema(requiredMode = REQUIRED, description = "Diagnostic time, UTC Time (YYYY-MM-DDTHH:mm:ss.SSSZ)")
    private Instant timestamp;
    @Schema(requiredMode = REQUIRED, nullable = true, implementation = String.class,
            allowableValues = {"AUTH_FAILED", "CONNECT_TIMEOUT", "CONNECTION_REFUSED", "QUERY_FAILED",
                    "INTERNAL_ERROR", "UNKNOWN"},
            description = "Failure cause; null when UP. INTERNAL_ERROR means an unexpected server-side error")
    private String errorCode;
    @Schema(requiredMode = REQUIRED, nullable = true,
            description = "Safe short description; never contains SQL, credentials or driver messages")
    private String errorMessage;
}
