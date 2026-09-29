package com.example.monitoring.lifecycle.adapter;

import com.example.monitoring.common.outbox.OutboxEventRepository;
import com.example.monitoring.common.outbox.OutboxEventType;
import com.example.monitoring.common.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LifecycleOutboxWriterTest {

    @Test
    void delegatesBodyToCommonWriterWithDatabaseOrderingAndFlushesForDeterministicSequence() {
        OutboxWriter commonWriter = mock(OutboxWriter.class);
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        LifecycleOutboxWriter writer = new LifecycleOutboxWriter(commonWriter, repository);
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000333");
        Map<String, Object> body = Map.of("databaseConfigId", 12L, "stateVersion", 3L);
        PreparedLifecycleEvent event = new PreparedLifecycleEvent(
                eventId, OutboxEventType.MONITORING_STATUS_CHANGED, 12L, body);

        writer.append(event);

        var ordered = inOrder(commonWriter, repository);
        ordered.verify(commonWriter).append(
                eventId, OutboxEventType.MONITORING_STATUS_CHANGED, "database:12", body);
        ordered.verify(repository).flush();
    }

    @Test
    void doesNotFlushWhenCommonWriterRejectsTheEvent() {
        OutboxWriter commonWriter = mock(OutboxWriter.class);
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        LifecycleOutboxWriter writer = new LifecycleOutboxWriter(commonWriter, repository);
        UUID eventId = UUID.randomUUID();
        Map<String, Object> body = Map.of("databaseConfigId", 12L);
        doThrow(new IllegalArgumentException("invalid envelope"))
                .when(commonWriter)
                .append(eventId, OutboxEventType.INCIDENT_RESOLVED, "database:12", body);

        assertThatThrownBy(() -> writer.append(new PreparedLifecycleEvent(
                eventId, OutboxEventType.INCIDENT_RESOLVED, 12L, body)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid envelope");

        verify(repository, never()).flush();
    }
}
