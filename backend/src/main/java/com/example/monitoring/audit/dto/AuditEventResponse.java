package com.example.monitoring.audit.dto;

import com.example.monitoring.domain.AuditEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long id,
        @Schema(nullable = true) Long actorId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "AuditAction enum name") String action,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"USER", "DATABASE", "POLICY", "PUSH_SUBSCRIPTION", "WEBHOOK", "SESSION"}) String targetType,
        @Schema(nullable = true) String targetId,
        @Schema(nullable = true) Long databaseConfigId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"SUCCESS", "FAILURE"}) String result,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UTC event time") Instant occurredAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientIp,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Safe summary without secrets") String summary) {
    public static AuditEventResponse from(AuditEvent value) {
        return new AuditEventResponse(value.getId(), value.getActorId(), value.getAction().name(), value.getTargetType().name(),
                value.getTargetId(), value.getDatabaseConfigId(), value.getResult().name(), value.getOccurredAt(),
                value.getClientIp(), value.getRequestId(), value.getSummary());
    }
}
