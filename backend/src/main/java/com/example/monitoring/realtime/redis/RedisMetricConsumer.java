package com.example.monitoring.realtime.redis;

import com.example.monitoring.realtime.event.MetricCollectedPayloadV1;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.models.stream.ClaimedMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public final class RedisMetricConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisMetricConsumer.class);
    private static final String GROUP_NAME = "cg:realtime";
    private static final int BATCH_SIZE = 100;
    private static final Duration READ_BLOCK = Duration.ofMillis(2_000);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_INVARIANT_ATTEMPTS = 5;

    private final LettuceConnectionFactory connectionFactory;
    private final RealtimeRedisSettings settings;
    private final RedisMetricRecordProcessor processor;
    private final RealtimeMetricTransaction transaction;
    private final String workerName = GROUP_NAME + ":" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile StatefulRedisConnection<byte[], byte[]> activeConnection;
    private volatile Thread worker;
    private RedisClient redisClient;

    public RedisMetricConsumer(
            LettuceConnectionFactory connectionFactory,
            RealtimeRedisSettings settings,
            RedisMetricRecordProcessor processor,
            RealtimeMetricTransaction transaction
    ) {
        this.connectionFactory = connectionFactory;
        this.settings = settings;
        this.processor = processor;
        this.transaction = transaction;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        transaction.verifyPrerequisite();
        if (!(connectionFactory.getRequiredNativeClient() instanceof RedisClient client)) {
            throw new IllegalStateException("Realtime consumer requires a standalone Lettuce RedisClient");
        }
        redisClient = client;
        running.set(true);
        worker = new Thread(this::consume, "realtime-metric-consumer");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        StatefulRedisConnection<byte[], byte[]> connection = activeConnection;
        if (connection != null) {
            connection.close();
        }
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
            try {
                currentWorker.join(5_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    public String workerName() {
        return workerName;
    }

    private void consume() {
        RetryBackoff backoff = new RetryBackoff();
        while (running.get()) {
            try (StatefulRedisConnection<byte[], byte[]> connection =
                         redisClient.connect(ByteArrayCodec.INSTANCE)) {
                activeConnection = connection;
                connection.setTimeout(COMMAND_TIMEOUT);
                RedisCommands<byte[], byte[]> commands = connection.sync();
                createGroup(commands);
                drainOwnPending(commands, backoff);
                reclaimPending(commands, backoff);
                readNewMessages(commands, backoff);
            } catch (RuntimeException exception) {
                if (running.get()) {
                    log.warn("Realtime Redis consumer dependency failure; retrying: {}",
                            exception.getClass().getSimpleName());
                    backoff.pause();
                }
            } finally {
                activeConnection = null;
            }
        }
    }

    private void createGroup(RedisCommands<byte[], byte[]> commands) {
        try {
            commands.xgroupCreate(
                    XReadArgs.StreamOffset.from(sourceStream(), "0-0"),
                    group(),
                    new XGroupCreateArgs().mkstream(true));
        } catch (RedisCommandExecutionException exception) {
            if (exception.getMessage() == null || !exception.getMessage().startsWith("BUSYGROUP")) {
                throw exception;
            }
        }
    }

    private void drainOwnPending(RedisCommands<byte[], byte[]> commands, RetryBackoff backoff) {
        while (running.get()) {
            List<StreamMessage<byte[], byte[]>> messages = readOwnPending(commands);
            if (messages.isEmpty()) {
                return;
            }
            process(messages, commands, backoff);
        }
    }

    private void reclaimPending(RedisCommands<byte[], byte[]> commands, RetryBackoff backoff) {
        String cursor = "0-0";
        do {
            ClaimedMessages<byte[], byte[]> claimed = commands.xautoclaim(
                    sourceStream(),
                    new XAutoClaimArgs<byte[]>()
                            .consumer(consumer())
                            .minIdleTime(settings.reclaimMinIdle())
                            .startId(cursor)
                            .count(BATCH_SIZE));
            process(claimed.getMessages(), commands, backoff);
            cursor = claimed.getId();
        } while (running.get() && !"0-0".equals(cursor));
    }

    private void readNewMessages(RedisCommands<byte[], byte[]> commands, RetryBackoff backoff) {
        long nextReclaim = System.nanoTime() + settings.reclaimInterval().toNanos();
        while (running.get()) {
            if (System.nanoTime() >= nextReclaim) {
                reclaimPending(commands, backoff);
                nextReclaim = System.nanoTime() + settings.reclaimInterval().toNanos();
            }
            List<StreamMessage<byte[], byte[]>> messages = readNewBatch(commands);
            if (messages.isEmpty()) {
                backoff.reset();
            }
            process(messages, commands, backoff);
        }
    }

    @SuppressWarnings("unchecked") // Lettuce exposes StreamOffset<K> as a generic varargs API.
    private List<StreamMessage<byte[], byte[]>> readOwnPending(
            RedisCommands<byte[], byte[]> commands
    ) {
        return commands.xreadgroup(
                consumer(),
                new XReadArgs().count(BATCH_SIZE),
                XReadArgs.StreamOffset.from(sourceStream(), "0"));
    }

    @SuppressWarnings("unchecked") // Lettuce exposes StreamOffset<K> as a generic varargs API.
    private List<StreamMessage<byte[], byte[]>> readNewBatch(
            RedisCommands<byte[], byte[]> commands
    ) {
        return commands.xreadgroup(
                consumer(),
                new XReadArgs().count(BATCH_SIZE).block(READ_BLOCK),
                XReadArgs.StreamOffset.lastConsumed(sourceStream()));
    }

    private void process(
            List<StreamMessage<byte[], byte[]>> messages,
            RedisCommands<byte[], byte[]> commands,
            RetryBackoff backoff
    ) {
        for (StreamMessage<byte[], byte[]> message : messages) {
            processOne(message, commands, backoff);
        }
    }

    private void processOne(
            StreamMessage<byte[], byte[]> message,
            RedisCommands<byte[], byte[]> commands,
            RetryBackoff backoff
    ) {
        byte[] rawPayload;
        try {
            rawPayload = processor.extractPayload(message);
        } catch (MetricPayloadException exception) {
            processor.reject(commands, message, processor.payloadForRejectedShape(message),
                    exception.eventId(), exception.reasonCode(), 1);
            backoff.reset();
            return;
        }

        MetricCollectedPayloadV1 payload;
        try {
            payload = processor.parse(rawPayload);
        } catch (MetricPayloadException exception) {
            processor.reject(commands, message, rawPayload,
                    exception.eventId(), exception.reasonCode(), 1);
            backoff.reset();
            return;
        }

        int invariantAttempts = 0;
        while (running.get()) {
            try {
                processor.transact(payload);
                processor.acknowledge(commands, message.getId());
                backoff.reset();
                return;
            } catch (FutureConfigVersionException exception) {
                invariantAttempts++;
                if (invariantAttempts >= MAX_INVARIANT_ATTEMPTS) {
                    processor.reject(commands, message, rawPayload, payload.eventId(),
                            "FUTURE_CONFIG_VERSION", invariantAttempts);
                    backoff.reset();
                    return;
                }
                backoff.pause();
            } catch (RedisException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                log.warn("Realtime metric handoff failed; event remains pending: eventId={}, cause={}",
                        payload.eventId(), exception.getClass().getSimpleName());
                backoff.pause();
            }
        }
    }

    private Consumer<byte[]> consumer() {
        return Consumer.from(group(), workerName.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sourceStream() {
        return settings.sourceStream().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] group() {
        return GROUP_NAME.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RetryBackoff {

        private static final long[] DELAYS_MILLIS = {1_000, 2_000, 4_000, 8_000, 16_000, 30_000};
        private int index;

        void reset() {
            index = 0;
        }

        void pause() {
            long delay = DELAYS_MILLIS[Math.min(index, DELAYS_MILLIS.length - 1)];
            if (index < DELAYS_MILLIS.length - 1) {
                index++;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
