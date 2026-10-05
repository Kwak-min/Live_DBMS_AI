package com.example.monitoring.common.stream;

import java.util.UUID;

public final class InvariantStreamRecordException extends RuntimeException {

    private final String reasonCode;
    private final UUID eventId;

    public InvariantStreamRecordException(String reasonCode, String message, UUID eventId) {
        this(reasonCode, message, eventId, null);
    }

    public InvariantStreamRecordException(
            String reasonCode,
            String message,
            UUID eventId,
            Throwable cause
    ) {
        super(message, cause);
        this.reasonCode = StreamRejectionReason.requireValid(reasonCode);
        this.eventId = eventId;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public UUID eventId() {
        return eventId;
    }
}
