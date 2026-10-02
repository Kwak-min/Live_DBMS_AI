package com.example.monitoring.risk.contract;

import java.util.UUID;

public final class IncidentEventContractException extends IllegalArgumentException {

    private final String code;
    private final UUID eventId;

    public IncidentEventContractException(String code, String message, UUID eventId) {
        super(message);
        this.code = code;
        this.eventId = eventId;
    }

    public String code() {
        return code;
    }

    public UUID eventId() {
        return eventId;
    }
}
