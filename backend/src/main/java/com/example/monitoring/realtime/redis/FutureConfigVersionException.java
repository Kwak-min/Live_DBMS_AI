package com.example.monitoring.realtime.redis;

import java.util.UUID;

public final class FutureConfigVersionException extends RuntimeException {

    private final UUID eventId;

    FutureConfigVersionException(UUID eventId) {
        super("Metric configVersion is newer than the current target version");
        this.eventId = eventId;
    }

    public UUID eventId() {
        return eventId;
    }
}
