package com.example.monitoring.lifecycle.adapter;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LifecycleOutboxWriterTest {

    @Test
    void writesOnlyTheSevenColumnPrivateContractWithMatchingTimes() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        LifecycleOutboxWriter writer = new LifecycleOutboxWriter(jdbc);
        Instant publishedAt = Instant.parse("2026-09-29T05:06:07.123Z");
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000333");

        writer.append(new SerializedLifecycleEvent(
                eventId, "MonitoringStatusChangedEvent", publishedAt, "{\"schemaVersion\":1}"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), arguments.capture());
        String normalizedSql = sql.getValue().replaceAll("\\s+", " ").trim();
        assertThat(normalizedSql).contains(
                "event_id, event_type, payload, created_at, published_at, attempts, next_attempt_at");
        assertThat(normalizedSql).contains("CAST(? AS jsonb), ?, NULL, 0, ?");
        assertThat(arguments.getValue()).containsExactly(
                eventId,
                "MonitoringStatusChangedEvent",
                "{\"schemaVersion\":1}",
                Timestamp.from(publishedAt),
                Timestamp.from(publishedAt));
    }

    @Test
    void rejectsAnOutboxInsertThatDoesNotWriteExactlyOneRow() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        LifecycleOutboxWriter writer = new LifecycleOutboxWriter(jdbc);

        assertThatThrownBy(() -> writer.append(new SerializedLifecycleEvent(
                UUID.randomUUID(), "MonitoringStatusChangedEvent", Instant.EPOCH, "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Monitoring lifecycle failed to append outbox event");
    }
}
