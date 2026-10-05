package com.example.monitoring.common.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "partc.stream.integration", matches = "true")
class RedisStreamWorkerIntegrationTest {

    private static final byte[] PAYLOAD = bytes("payload");
    private static final byte[] GROUP = bytes("cg:test-part-c");

    private byte[] stream;
    private byte[] deadLetterStream;
    private RedisClient redisClient;
    private StatefulRedisConnection<byte[], byte[]> redisConnection;
    private RedisCommands<byte[], byte[]> redis;
    private LettuceConnectionFactory connectionFactory;
    private RedisStreamWorker worker;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        stream = bytes("test:part-c:source:" + suffix);
        deadLetterStream = bytes("test:part-c:dead-letter:" + suffix);
        int port = Integer.parseInt(System.getProperty("partc.stream.redis.port"));

        redisClient = RedisClient.create("redis://127.0.0.1:" + port);
        redisConnection = redisClient.connect(ByteArrayCodec.INSTANCE);
        redis = redisConnection.sync();

        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
    }

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.stop();
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
    void preexistingRecordIsHandledThenAcknowledged() {
        AtomicInteger handled = new AtomicInteger();
        redis.xadd(stream, Map.of(PAYLOAD, bytes("{}")));
        worker = worker(record -> handled.incrementAndGet());

        worker.start();

        await(() -> handled.get() == 1 && pendingCount() == 0);
        assertThat(redis.xlen(stream)).isOne();
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadConsumerRecordIsAutoClaimedAndAcknowledged() throws Exception {
        redis.xgroupCreate(
                XReadArgs.StreamOffset.from(stream, "0-0"),
                GROUP,
                new XGroupCreateArgs().mkstream(true));
        redis.xadd(stream, Map.of(PAYLOAD, bytes("{}")));
        redis.xreadgroup(
                Consumer.from(GROUP, bytes("dead-consumer")),
                new XReadArgs().count(1),
                XReadArgs.StreamOffset.lastConsumed(stream));
        Thread.sleep(20);
        AtomicInteger handled = new AtomicInteger();
        worker = worker(record -> handled.incrementAndGet());

        worker.start();

        await(() -> handled.get() == 1 && pendingCount() == 0);
    }

    @Test
    void malformedShapeIsWrittenToDlqBeforeSourceAck() {
        AtomicInteger handled = new AtomicInteger();
        worker = worker(record -> handled.incrementAndGet());
        worker.start();

        redis.xadd(stream, Map.of(bytes("unexpected"), bytes("{}")));

        await(() -> redis.xlen(deadLetterStream) == 1 && pendingCount() == 0);
        assertThat(handled).hasValue(0);
    }

    @Test
    void dlqOutageNeverAcknowledgesInvalidSourceRecord() {
        redis.set(deadLetterStream, bytes("wrong-type"));
        AtomicInteger attempts = new AtomicInteger();
        worker = worker(record -> {
            attempts.incrementAndGet();
            throw new InvalidStreamRecordException("INVALID_JSON", "invalid", null);
        });
        worker.start();

        redis.xadd(stream, Map.of(PAYLOAD, bytes("{bad json")));

        await(() -> attempts.get() >= 1 && pendingCount() == 1);
        assertThat(redis.xlen(stream)).isOne();
    }

    private RedisStreamWorker worker(StreamRecordHandler handler) {
        StreamWorkerSpec spec = new StreamWorkerSpec(
                text(stream),
                text(GROUP),
                "part-c-stream-integration",
                text(deadLetterStream),
                Duration.ofMillis(1),
                Duration.ofMillis(20));
        return new RedisStreamWorker(connectionFactory, spec, handler, new ObjectMapper());
    }

    private long pendingCount() {
        try {
            return redis.xpending(stream, GROUP).getCount();
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for Redis stream state", exception);
            }
        }
        throw new AssertionError("Timed out waiting for Redis stream state");
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
