package com.example.monitoring.realtime.status;

import com.example.monitoring.risk.contract.StatusSnapshot;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StatusStreamEvent(
        UUID eventId,
        Instant publishedAt,
        StatusSnapshot status
) {
    public StatusStreamEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(status, "status");
    }
}
