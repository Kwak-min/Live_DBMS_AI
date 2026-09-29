package com.example.monitoring.lifecycle.adapter;

import org.springframework.jdbc.core.JdbcOperations;

import java.sql.Timestamp;

final class LifecycleOutboxWriter {

    static final String INSERT_OUTBOX = """
            INSERT INTO event_outbox (
                event_id, event_type, payload, created_at, published_at, attempts, next_attempt_at
            ) VALUES (?, ?, CAST(? AS jsonb), ?, NULL, 0, ?)
            """;

    private final JdbcOperations jdbc;

    LifecycleOutboxWriter(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    void append(SerializedLifecycleEvent event) {
        int inserted = jdbc.update(
                INSERT_OUTBOX,
                event.eventId(),
                event.eventType(),
                event.json(),
                Timestamp.from(event.publishedAt()),
                Timestamp.from(event.publishedAt()));
        if (inserted != 1) {
            throw new IllegalStateException("Monitoring lifecycle failed to append outbox event");
        }
    }
}
