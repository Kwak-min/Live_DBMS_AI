package com.example.monitoring.infrastructure.redis;

import com.example.monitoring.dto.MetricCollectedEvent;
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
public class RedisStreamPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.redis.stream-key:stream:metrics}")
    private String streamKey;

    public RecordId publish(MetricCollectedEvent event) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(event);
            Map<String, String> body = Collections.singletonMap("payload", jsonPayload);

            MapRecord<String, String, String> record = MapRecord.create(streamKey, body);
            RecordId recordId = stringRedisTemplate.opsForStream().add(record);

            log.debug("Successfully published MetricCollectedEvent to Redis Stream '{}', RecordId: {}, dbId: {}",
                    streamKey, recordId, event.getDatabaseConfigId());
            return recordId;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize MetricCollectedEvent for dbId: {}", event.getDatabaseConfigId(), e);
            throw new RuntimeException("Serialization failure during Redis Stream publish", e);
        } catch (Exception e) {
            log.error("Failed to publish metric event to Redis Stream '{}' for dbId: {}", streamKey, event.getDatabaseConfigId(), e);
            return null;
        }
    }
}
