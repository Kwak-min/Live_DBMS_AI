package com.example.monitoring.common.outbox;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * 공통 outbox 기록기 (A 공통 기반, A·B·C 사용).
 * 호출자의 PostgreSQL 트랜잭션 안에서만 기록하며, 업무 데이터와 이벤트가 함께 커밋/롤백된다.
 * 공통 필드(schemaVersion, eventId, eventType, publishedAt)는 이 클래스가 채우므로 payload DTO에 넣지 않는다.
 */
@Service
@RequiredArgsConstructor
public class OutboxWriter {

    static final int SCHEMA_VERSION = 1;
    static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final Set<String> RESERVED_FIELDS = Set.of("schemaVersion", "eventId", "eventType", "publishedAt");

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    private Clock clock = Clock.systemUTC();

    @Value("${app.redis.stream-key:stream:metrics}")
    private String metricsStreamKey;

    @Value("${app.redis.status-stream-key:stream:statuses}")
    private String statusStreamKey;

    @Value("${app.redis.incident-stream-key:stream:incidents}")
    private String incidentStreamKey;

    /** 순서 제약 없이 기록한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID eventId, OutboxEventType type, Object payload) {
        append(eventId, type, null, payload);
    }

    /**
     * @param eventId     논리 이벤트 ID. 같은 논리 이벤트를 다시 기록할 때도 같은 값을 쓴다.
     * @param orderingKey 같은 키끼리 생성 순서대로 발행된다. 예: {@code database:12}
     * @param payload     JSON 객체로 직렬화되는 이벤트 본문 (공통 필드 제외)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID eventId, OutboxEventType type, String orderingKey, Object payload) {
        if (eventId == null || type == null || payload == null) {
            throw new IllegalArgumentException("eventId, type and payload are required");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String json = toEventJson(eventId, type, now, payload);

        outboxEventRepository.save(OutboxEvent.builder()
                .eventId(eventId)
                .eventType(type.wireName())
                .streamKey(streamKey(type))
                .orderingKey(orderingKey)
                .payload(json)
                .createdAt(now)
                .attempts(0)
                .nextAttemptAt(now)
                .build());
    }

    private String toEventJson(UUID eventId, OutboxEventType type, Instant publishedAt, Object payload) {
        JsonNode body = objectMapper.valueToTree(payload);
        if (!body.isObject()) {
            throw new IllegalArgumentException("Outbox payload must serialize to a JSON object: " + type.wireName());
        }
        body.fieldNames().forEachRemaining(field -> {
            if (RESERVED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Outbox payload must not contain common field '" + field + "'");
            }
        });

        ObjectNode event = objectMapper.createObjectNode();
        event.put("schemaVersion", SCHEMA_VERSION);
        event.put("eventId", eventId.toString());
        event.put("eventType", type.wireName());
        event.put("publishedAt", UtcInstantJacksonConfig.format(publishedAt));
        event.setAll((ObjectNode) body);

        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize outbox payload: " + type.wireName(), e);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Outbox payload exceeds 64KiB: " + type.wireName());
        }
        return json;
    }

    private String streamKey(OutboxEventType type) {
        return switch (type.streamGroup()) {
            case METRICS -> metricsStreamKey;
            case STATUSES -> statusStreamKey;
            case INCIDENTS -> incidentStreamKey;
        };
    }
}
