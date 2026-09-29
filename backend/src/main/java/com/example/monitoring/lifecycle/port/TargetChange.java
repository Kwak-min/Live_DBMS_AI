package com.example.monitoring.lifecycle.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TargetChange(
        long databaseConfigId,
        long configVersion,
        TargetChangeType changeType,
        boolean enabled,
        String name,
        Instant occurredAt,
        Long actorId,
        UUID requestId
) {
    public TargetChange {
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(requestId, "requestId");
    }
}
