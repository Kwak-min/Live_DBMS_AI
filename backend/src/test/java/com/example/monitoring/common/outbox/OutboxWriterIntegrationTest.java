package com.example.monitoring.common.outbox;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@Import({EmbeddedPostgresSupport.Config.class, OutboxWriter.class, ProcessedEventStore.class,
        UtcInstantJacksonConfig.class})
class OutboxWriterIntegrationTest {

    private static final String UTC_MILLIS_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessedEventStore processedEventStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    record SamplePayload(long metricId, long databaseConfigId, Instant timestamp, Double qps) {
    }

    @Test
    @DisplayName("append stores the event JSON with common fields, UTC millis times and the mapped stream")
    void appendStoresEnvelope() throws Exception {
        UUID eventId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-09-28T03:00:00Z");

        outboxWriter.append(eventId, OutboxEventType.METRIC_COLLECTED, "database:12",
                new SamplePayload(501L, 12L, timestamp, null));
        outboxEventRepository.flush();

        OutboxEvent stored = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(stored.getEventType()).isEqualTo("MetricCollectedEvent");
        assertThat(stored.getStreamKey()).isEqualTo("stream:metrics");
        assertThat(stored.getOrderingKey()).isEqualTo("database:12");
        assertThat(stored.getPublishedAt()).isNull();
        assertThat(stored.getAttempts()).isZero();
        assertThat(stored.getNextAttemptAt()).isEqualTo(stored.getCreatedAt());

        String jsonType = jdbcTemplate.queryForObject(
                "SELECT jsonb_typeof(payload) FROM event_outbox WHERE event_id = ?", String.class, eventId);
        assertThat(jsonType).isEqualTo("object");

        JsonNode event = objectMapper.readTree(stored.getPayload());
        assertThat(event.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(event.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(event.get("eventType").asText()).isEqualTo("MetricCollectedEvent");
        assertThat(event.get("publishedAt").asText()).matches(UTC_MILLIS_PATTERN);
        assertThat(event.get("timestamp").asText()).isEqualTo("2026-09-28T03:00:00.000Z");
        assertThat(event.get("metricId").asLong()).isEqualTo(501L);
        assertThat(event.has("qps")).isTrue();
        assertThat(event.get("qps").isNull()).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("append outside a caller transaction is rejected")
    void appendRequiresTransaction() {
        assertThatThrownBy(() -> outboxWriter.append(UUID.randomUUID(), OutboxEventType.METRIC_COLLECTED,
                new SamplePayload(1L, 1L, Instant.now(), 1.0)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("payload must not override common fields and must fit in 64KiB")
    void appendRejectsInvalidPayload() {
        assertThatThrownBy(() -> outboxWriter.append(UUID.randomUUID(), OutboxEventType.INCIDENT_CREATED,
                Map.of("eventId", "other")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outboxWriter.append(UUID.randomUUID(), OutboxEventType.INCIDENT_CREATED,
                Map.of("message", "x".repeat(EventJson.MAX_PAYLOAD_BYTES))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outboxWriter.append(UUID.randomUUID(), OutboxEventType.INCIDENT_CREATED, "text"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("unpublished events are returned in creation order and only published ones are purged")
    void unpublishedOrderAndRetention() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        outboxWriter.append(first, OutboxEventType.MONITORING_STATUS_CHANGED, Map.of("n", 1));
        outboxWriter.append(second, OutboxEventType.INCIDENT_UPDATED, Map.of("n", 2));
        outboxWriter.append(third, OutboxEventType.INCIDENT_RESOLVED, Map.of("n", 3));
        outboxEventRepository.flush();

        List<UUID> order = outboxEventRepository.findUnpublished(PageRequest.of(0, 100)).stream()
                .map(OutboxEvent::getEventId).toList();
        assertThat(order).containsSubsequence(first, second, third);

        OutboxEvent published = outboxEventRepository.findById(first).orElseThrow();
        published.setPublishedAt(Instant.parse("2026-01-01T00:00:00Z"));
        outboxEventRepository.saveAndFlush(published);

        int deleted = outboxEventRepository.deletePublishedBefore(Instant.parse("2026-06-01T00:00:00Z"));

        assertThat(deleted).isEqualTo(1);
        assertThat(outboxEventRepository.findById(first)).isEmpty();
        assertThat(outboxEventRepository.findById(second)).isPresent();
    }

    @Test
    @DisplayName("processed event store reports duplicates per stream and consumer group")
    void processedEventDeduplication() {
        UUID eventId = UUID.randomUUID();

        assertThat(processedEventStore.markProcessed("stream:metrics", "cg:risk", eventId)).isTrue();
        assertThat(processedEventStore.markProcessed("stream:metrics", "cg:risk", eventId)).isFalse();
        assertThat(processedEventStore.markProcessed("stream:metrics", "cg:realtime", eventId)).isTrue();
    }
}
