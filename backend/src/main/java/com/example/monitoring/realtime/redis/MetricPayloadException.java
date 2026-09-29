package com.example.monitoring.realtime.redis;

import java.util.UUID;

public final class MetricPayloadException extends RuntimeException {

    private final String reasonCode;
    private final UUID eventId;

    MetricPayloadException(String reasonCode, String message, UUID eventId, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
        this.eventId = eventId;
    }

    MetricPayloadException(String reasonCode, String message, UUID eventId) {
        this(reasonCode, message, eventId, null);
    }

    public String reasonCode() {
        return reasonCode;
    }

    public UUID eventId() {
        return eventId;
    }
}
