package com.example.monitoring.infrastructure.redis;

import com.example.monitoring.domain.RiskSeverity;
import com.example.monitoring.dto.IncidentCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IncidentPublisherTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private ObjectMapper objectMapper;
    private IncidentPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        publisher = new IncidentPublisher(stringRedisTemplate, objectMapper);
        ReflectionTestUtils.setField(publisher, "streamKey", "stream:incidents");
    }

    @Test
    @DisplayName("Publishes IncidentCreatedEvent to Redis Stream stream:incidents")
    @SuppressWarnings("unchecked")
    void publishIncident_success() {
        given(stringRedisTemplate.opsForStream()).willReturn((StreamOperations) streamOperations);
        given(streamOperations.add(any(MapRecord.class))).willReturn(RecordId.of("1600000000000-0"));

        IncidentCreatedEvent incident = IncidentCreatedEvent.builder()
                .incidentId("inc-123")
                .databaseConfigId(1L)
                .databaseName("TestDB")
                .severity(RiskSeverity.CRITICAL)
                .ruleType("CPU_USAGE_HIGH")
                .message("CPU usage reached 92.5%")
                .metricName("cpuUsage")
                .metricValue(92.5)
                .thresholdValue(90.0)
                .timestamp(LocalDateTime.now())
                .build();

        RecordId recordId = publisher.publish(incident);

        assertNotNull(recordId);
        verify(streamOperations).add(any(MapRecord.class));
    }
}
