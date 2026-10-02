package com.example.monitoring.lifecycle.adapter;

import com.example.monitoring.common.outbox.OutboxEventRepository;
import com.example.monitoring.common.outbox.OutboxWriter;

final class LifecycleOutboxWriter {

    private static final String DATABASE_ORDERING_KEY_PREFIX = "database:";

    private final OutboxWriter outboxWriter;
    private final OutboxEventRepository outboxEvents;

    LifecycleOutboxWriter(OutboxWriter outboxWriter, OutboxEventRepository outboxEvents) {
        this.outboxWriter = outboxWriter;
        this.outboxEvents = outboxEvents;
    }

    void append(PreparedLifecycleEvent event) {
        outboxWriter.append(
                event.eventId(),
                event.eventType(),
                DATABASE_ORDERING_KEY_PREFIX + event.databaseConfigId(),
                event.body());
        outboxEvents.flush();
    }
}
