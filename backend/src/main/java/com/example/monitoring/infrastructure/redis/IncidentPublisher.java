package com.example.monitoring.infrastructure.redis;

import com.example.monitoring.dto.IncidentCreatedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.redis.incident-stream-key:stream:incidents}")
    private String streamKey;

    public RecordId publish(IncidentCreatedEvent incident) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(incident);
            Map<String, String> body = Collections.singletonMap("payload", jsonPayload);

            MapRecord<String, String, String> record = MapRecord.create(streamKey, body);
            RecordId recordId = stringRedisTemplate.opsForStream().add(record);

            log.info("Published IncidentCreatedEvent to Redis Stream '{}' [Id: {}, Severity: {}, Rule: {}, dbId: {}]",
                    streamKey, incident.getIncidentId(), incident.getSeverity(), incident.getRuleType(), incident.getDatabaseConfigId());
            return recordId;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize IncidentCreatedEvent for dbId: {}", incident.getDatabaseConfigId(), e);
            throw new RuntimeException("Serialization failure during Redis Stream incident publish", e);
        } catch (Exception e) {
            log.error("Failed to publish incident event to Redis Stream '{}' for dbId: {}", streamKey, incident.getDatabaseConfigId(), e);
            return null;
        }
    }
}
