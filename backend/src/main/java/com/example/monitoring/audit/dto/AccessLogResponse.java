package com.example.monitoring.audit.dto;

import com.example.monitoring.domain.AuditLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record AccessLogResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long id,
        @Schema(nullable = true) Long actorId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String method,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Request path without query string") String path,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer statusCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Request duration in milliseconds") Long durationMs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientIp,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UTC request time") Instant occurredAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId) {
    public static AccessLogResponse from(AuditLog value) {
        return new AccessLogResponse(value.getId(), value.getActorId(), value.getMethod(), value.getPath(),
                value.getStatusCode(), value.getDurationMs(), value.getClientIp(), value.getOccurredAt(), value.getRequestId());
    }
}
