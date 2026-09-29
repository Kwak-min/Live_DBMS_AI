package com.example.monitoring.realtime.redis;

import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.realtime.event.MetricBroadcastPort;
import com.example.monitoring.realtime.event.MetricPayloadParserTest;
import com.example.monitoring.realtime.event.RealtimeMetricMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "realtime.redis.integration", matches = "true")
class RedisMetricConsumerIntegrationTest {

    private static final byte[] PAYLOAD = bytes("payload");
    private static final byte[] GROUP = bytes("cg:realtime");

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private byte[] stream;
    private byte[] deadLetterStream;
    private JdbcTemplate jdbc;
    private LettuceConnectionFactory connectionFactory;
    private RedisClient redisClient;
    private StatefulRedisConnection<byte[], byte[]> redisConnection;
    private RedisCommands<byte[], byte[]> redis;
    private RecordingBroadcastPort broadcastPort;
    private RedisMetricConsumer consumer;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        stream = bytes("test:realtime:metrics:" + suffix);
        deadLetterStream = bytes("test:realtime:dead-letter:" + suffix);

        int redisPort = Integer.parseInt(System.getProperty("realtime.redis.port"));
        redisClient = RedisClient.create("redis://127.0.0.1:" + redisPort);
        redisConnection = redisClient.connect(ByteArrayCodec.INSTANCE);
        redis = redisConnection.sync();

        RedisStandaloneConfiguration redisConfiguration =
                new RedisStandaloneConfiguration("127.0.0.1", redisPort);
        connectionFactory = new LettuceConnectionFactory(redisConfiguration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        jdbc = new JdbcTemplate(dataSource);
        recreateProcessedEvents();
        broadcastPort = new RecordingBroadcastPort();
        consumer = newConsumer();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.stop();
        }
        if (redis != null) {
            redis.del(stream, deadLetterStream);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redisConnection != null) {
            redisConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void duplicateRedisRecordsAndRestartReplayPublishOnceAndAckAll() {
        consumer.start();
        redis.xadd(stream, Map.of(PAYLOAD, validPayload()));
        redis.xadd(stream, Map.of(PAYLOAD, validPayload()));

        await(() -> rowCount() == 1 && pendingCount() == 0 && broadcastPort.size() == 1);
        consumer.stop();

        redis.xadd(stream, Map.of(PAYLOAD, validPayload()));
        consumer = newConsumer();
        consumer.start();
        await(() -> pendingCount() == 0);

        assertThat(broadcastPort.size()).isOne();
        assertThat(rowCount()).isOne();
        assertThat(redis.xlen(stream)).isEqualTo(3);
    }

    @Test
    @SuppressWarnings("unchecked") // Lettuce exposes StreamOffset<K> as a generic varargs API.
    void deadConsumerRecordIsAutoClaimedAndAcknowledged() throws Exception {
        redis.xgroupCreate(
                XReadArgs.StreamOffset.from(stream, "0-0"),
                GROUP,
                new XGroupCreateArgs().mkstream(true));
        redis.xadd(stream, Map.of(PAYLOAD, validPayload()));
        List<StreamMessage<byte[], byte[]>> delivered = redis.xreadgroup(
                Consumer.from(GROUP, bytes("dead-consumer")),
                XReadArgs.StreamOffset.lastConsumed(stream));
        assertThat(delivered).hasSize(1);
        Thread.sleep(20);

        consumer.start();
        await(() -> pendingCount() == 0 && broadcastPort.size() == 1);

        assertThat(rowCount()).isOne();
    }

    @Test
    void malformedRecordGoesToSafeDiagnosticDlqAndDoesNotBlockFollowingValidRecord() throws Exception {
        consumer.start();
        String invalid = MetricPayloadParserTest.validPayload()
                .replace("\"schemaVersion\":1,",
                        "\"schemaVersion\":2,\"password\":\"do-not-leak\","
                                + "\"note\":\"Bearer PROBE_CREDENTIAL_42\",")
                .replace("\"errorMessage\":null",
                        "\"errorMessage\":\"password=ERROR_PROBE_CREDENTIAL_42\"");
        redis.xadd(stream, Map.of(PAYLOAD, bytes(invalid)));
        redis.xadd(stream, Map.of(PAYLOAD, validPayload()));

        await(() -> redis.xlen(deadLetterStream) == 1
                && broadcastPort.size() == 1
                && pendingCount() == 0);

        StreamMessage<byte[], byte[]> deadLetter =
                redis.xrange(deadLetterStream, Range.unbounded()).get(0);
        assertThat(deadLetter.getBody()).hasSize(1);
        Map.Entry<byte[], byte[]> deadLetterEntry = deadLetter.getBody().entrySet().iterator().next();
        assertThat(Arrays.equals(deadLetterEntry.getKey(), PAYLOAD)).isTrue();
        JsonNode body = new ObjectMapper().readTree(deadLetterEntry.getValue());
        assertThat(body.get("reasonCode").textValue()).isEqualTo("UNSUPPORTED_SCHEMA_VERSION");
        assertThat(body.get("attemptCount").intValue()).isOne();
        assertThat(body.get("failedAt").textValue())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        JsonNode sanitizedPayload = new ObjectMapper().readTree(
                body.get("payload").textValue());
        assertThat(sanitizedPayload.path("schemaVersion").intValue()).isEqualTo(2);
        assertThat(sanitizedPayload.path("eventId").textValue())
                .isEqualTo("7f6a1c08-9ef4-45bd-bc2e-5b2d1c1a2f11");
        assertThat(sanitizedPayload.path("databaseConfigId").longValue()).isEqualTo(12);
        assertThat(sanitizedPayload.path("eventType").textValue())
                .isEqualTo("MetricCollectedEvent");
        assertThat(sanitizedPayload.path("publishedAt").textValue())
                .isEqualTo("2026-09-28T03:00:20.050Z");
        assertThat(sanitizedPayload.has("databaseName")).isFalse();
        assertThat(sanitizedPayload.has("errorMessage")).isFalse();
        assertThat(sanitizedPayload.has("note")).isFalse();
        assertThat(sanitizedPayload.has("cpuUsage")).isFalse();
        assertThat(sanitizedPayload.has("unavailableMetrics")).isFalse();
        assertThat(sanitizedPayload.has("password")).isFalse();
        assertThat(sanitizedPayload.toString()).doesNotContain(
                "do-not-leak",
                "PROBE_CREDENTIAL_42",
                "ERROR_PROBE_CREDENTIAL_42");
    }

    @Test
    void dlqFailureLeavesInvalidSourceRecordPending() {
        redis.set(deadLetterStream, bytes("wrong-type"));
        long startedAt = System.nanoTime();
        consumer.start();
        redis.xadd(stream, Map.of(PAYLOAD, bytes("{bad json")));

        await(() -> pendingDeliveryCount() >= 4);

        assertThat(redis.xlen(stream)).isOne();
        assertThat(broadcastPort.size()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void brokerFailureRollsBackDedupMarkerAndLeavesRecordPending() {
        broadcastPort.failuresRemaining.set(Integer.MAX_VALUE);
        consumer.start();
        redis.xadd(stream, Map.of(PAYLOAD, validPayload()));

        await(() -> broadcastPort.attempts.get() >= 1 && pendingCount() == 1);

        assertThat(rowCount()).isZero();
    }

    @Test
    void databaseFailureLeavesRecordPendingWithoutBrokerHandoff() {
        consumer.start();
        jdbc.execute("DROP TABLE processed_events");
        redis.xadd(stream, Map.of(PAYLOAD, validPayload()));

        await(() -> pendingCount() == 1);

        assertThat(broadcastPort.size()).isZero();
    }

    @Test
    void futureConfigVersionRetriesFiveTimesThenDlqsWithoutCommittingMarker() throws Exception {
        consumer = newConsumer(1);
        consumer.start();
        redis.xadd(stream, Map.of(PAYLOAD, validPayload()));

        await(Duration.ofSeconds(20), () -> redis.xlen(deadLetterStream) == 1 && pendingCount() == 0);

        StreamMessage<byte[], byte[]> deadLetter =
                redis.xrange(deadLetterStream, Range.unbounded()).get(0);
        byte[] deadLetterPayload = deadLetter.getBody().entrySet().iterator().next().getValue();
        JsonNode body = new ObjectMapper().readTree(deadLetterPayload);
        assertThat(body.get("reasonCode").textValue()).isEqualTo("FUTURE_CONFIG_VERSION");
        assertThat(body.get("attemptCount").intValue()).isEqualTo(5);
        assertThat(rowCount()).isZero();
        assertThat(broadcastPort.size()).isZero();
    }

    private RedisMetricConsumer newConsumer() {
        return newConsumer(2);
    }

    private RedisMetricConsumer newConsumer(long currentConfigVersion) {
        ObjectMapper mapper = new ObjectMapper();
        TargetProvider targetProvider = mock(TargetProvider.class);
        when(targetProvider.getMetadata(12L))
                .thenReturn(Optional.of(new TargetMetadata(
                        12, currentConfigVersion, "db", "127.0.0.1", 3306, null, true)));
        RealtimeMetricTransaction transaction = new RealtimeMetricTransaction(
                jdbc,
                new DataSourceTransactionManager(dataSource),
                broadcastPort,
                targetProvider);
        RealtimeRedisSettings settings = new RealtimeRedisSettings(
                new String(stream, StandardCharsets.UTF_8),
                new String(deadLetterStream, StandardCharsets.UTF_8),
                Duration.ofMillis(1),
                Duration.ofMillis(20));
        RedisMetricRecordProcessor processor = new RedisMetricRecordProcessor(
                new MetricPayloadParser(mapper),
                transaction,
                settings,
                mapper);
        return new RedisMetricConsumer(connectionFactory, settings, processor, transaction);
    }

    private void recreateProcessedEvents() {
        jdbc.execute("DROP TABLE IF EXISTS processed_events");
        jdbc.execute("""
                CREATE TABLE processed_events (
                    stream varchar(100) NOT NULL,
                    consumer_group varchar(100) NOT NULL,
                    event_id uuid NOT NULL,
                    processed_at timestamptz NOT NULL,
                    PRIMARY KEY (stream, consumer_group, event_id)
                )
                """);
    }

    private int rowCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class);
        return count == null ? 0 : count;
    }

    private long pendingCount() {
        try {
            return redis.xpending(stream, GROUP).getCount();
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private long pendingDeliveryCount() {
        try {
            return redis.xpending(stream, GROUP, Range.unbounded(), Limit.from(1)).stream()
                    .findFirst()
                    .map(io.lettuce.core.models.stream.PendingMessage::getRedeliveryCount)
                    .orElse(0L);
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private byte[] validPayload() {
        return bytes(MetricPayloadParserTest.validPayload());
    }

    private void await(BooleanSupplier condition) {
        await(Duration.ofSeconds(10), condition);
    }

    private void await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for consumer", exception);
            }
        }
        throw new AssertionError("Timed out waiting for realtime consumer state");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingBroadcastPort implements MetricBroadcastPort {

        private final List<RealtimeMetricMessage> messages = new ArrayList<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger failuresRemaining = new AtomicInteger();

        @Override
        public synchronized void publish(RealtimeMetricMessage message) {
            attempts.incrementAndGet();
            if (failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("test broker failure");
            }
            messages.add(message);
        }

        synchronized int size() {
            return messages.size();
        }
    }
}
