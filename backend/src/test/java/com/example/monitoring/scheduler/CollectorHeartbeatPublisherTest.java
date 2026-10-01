package com.example.monitoring.scheduler;

import com.example.monitoring.collector.DbMetricsCollector;
import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.metric.MetricCollectionRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectorHeartbeatPublisherTest {

    private static final Path CONTRACT_EXAMPLES = Path.of("..", "docs", "contract-examples.json");
    private static final String UTC_MILLIS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    private final TargetProvider targetProvider = mock(TargetProvider.class);
    private final DbMetricsCollector collector = mock(DbMetricsCollector.class);
    private final MetricCollectionRecorder recorder = mock(MetricCollectionRecorder.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
    private final ObjectMapper objectMapper = utcObjectMapper();

    private MetricSchedulerWorker worker;
    private CollectorHeartbeatPublisher publisher;

    @BeforeEach
    void setUp() {
        worker = new MetricSchedulerWorker(targetProvider, collector, recorder);
        publisher = new CollectorHeartbeatPublisher(worker, redisTemplate, objectMapper);
        ReflectionTestUtils.setField(publisher, "streamKey", "stream:collector-heartbeats");
        when(redisTemplate.opsForStream()).thenReturn(streams);
        when(recorder.record(any(CollectorTarget.class), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    @Test
    @DisplayName("Heartbeat JSON has exactly the contract fixture fields with UTC millis times")
    void heartbeatMatchesContract() throws Exception {
        JsonNode fixture = objectMapper.readTree(Files.readString(CONTRACT_EXAMPLES))
                .get("fixtures").get("collectorHeartbeatEvent");

        JsonNode event = objectMapper.readTree(publisher.buildEvent());

        assertThat(fieldNames(event)).containsExactlyInAnyOrderElementsOf(fieldNames(fixture));
        assertThat(event.get("eventType").asText()).isEqualTo("CollectorHeartbeatEvent");
        assertThat(event.get("collectorId").asText()).startsWith("collector-");
        assertThat(event.get("timestamp").asText()).matches(UTC_MILLIS);
        assertThat(event.get("lastCycleStartedAt").isNull()).isTrue();
        assertThat(event.get("cycleInProgress").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Cycle is in progress while its collections run and completes when all of them finish")
    void cycleStatusFollowsCollections() throws Exception {
        CollectorTarget target = new CollectorTarget(1L, 1L, "db", "127.0.0.1", 13306, null, "u", "p", true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(targetProvider.listEnabled()).thenReturn(List.of(new TargetMetadata(1L, 1L, "db", "127.0.0.1",
                13306, null, true)));
        when(targetProvider.getForCollection(1L)).thenReturn(Optional.of(target));
        when(collector.collectMetrics(target)).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new MetricData();
        });

        worker.executeCollectionCycle();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        JsonNode running = objectMapper.readTree(publisher.buildEvent());
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (worker.cycleStatus().cycleInProgress() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        JsonNode done = objectMapper.readTree(publisher.buildEvent());

        assertThat(running.get("cycleInProgress").asBoolean()).isTrue();
        assertThat(running.get("lastCycleStartedAt").asText()).matches(UTC_MILLIS);
        assertThat(running.get("lastCycleCompletedAt").isNull()).isTrue();
        assertThat(done.get("cycleInProgress").asBoolean()).isFalse();
        assertThat(done.get("lastCycleCompletedAt").asText()).matches(UTC_MILLIS);
    }

    @Test
    @DisplayName("A cycle with no targets completes immediately")
    void emptyCycleCompletes() {
        when(targetProvider.listEnabled()).thenReturn(List.of());

        worker.executeCollectionCycle();

        MetricSchedulerWorker.CycleStatus status = worker.cycleStatus();
        assertThat(status.cycleInProgress()).isFalse();
        assertThat(status.lastCycleCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Redis failure while publishing a heartbeat is logged, not thrown")
    void redisFailureIsContained() {
        when(streams.add(any(StringRecord.class))).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(publisher::publishHeartbeat).doesNotThrowAnyException();
    }

    private static ObjectMapper utcObjectMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new UtcInstantJacksonConfig().utcInstantCustomizer().customize(builder);
        return builder.build();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
