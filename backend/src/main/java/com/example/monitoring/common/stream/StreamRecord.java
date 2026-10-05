package com.example.monitoring.common.stream;

import java.util.Objects;

public record StreamRecord(String sourceStream, String recordId, byte[] payload) {

    public StreamRecord {
        if (sourceStream == null || sourceStream.isBlank()) {
            throw new IllegalArgumentException("sourceStream must not be blank");
        }
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank");
        }
        payload = Objects.requireNonNull(payload, "payload").clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
