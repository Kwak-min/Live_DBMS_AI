package com.example.monitoring.common.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
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
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RedisStreamWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamWorker.class);
    private static final int BATCH_SIZE = 100;
    private static final Duration READ_BLOCK = Duration.ofSeconds(2);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);

    private final LettuceConnectionFactory connectionFactory;
    private final StreamWorkerSpec spec;
    private final StreamRecordHandler handler;
    private final RedisStreamRecordProcessor processor;
    private final StreamRetryBackoff.Sleeper sleeper;
    private final String consumerName;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile StatefulRedisConnection<byte[], byte[]> activeConnection;
    private volatile Thread worker;
    private RedisClient redisClient;

    public RedisStreamWorker(
            LettuceConnectionFactory connectionFactory,
            StreamWorkerSpec spec,
            StreamRecordHandler handler,
            ObjectMapper mapper
    ) {
        this(
                connectionFactory,
                spec,
                handler,
                mapper,
                Clock.systemUTC(),
                Thread::sleep,
                spec.consumerGroup() + ":" + UUID.randomUUID());
    }

    RedisStreamWorker(
            LettuceConnectionFactory connectionFactory,
            StreamWorkerSpec spec,
            StreamRecordHandler handler,
            ObjectMapper mapper,
            Clock clock,
            StreamRetryBackoff.Sleeper sleeper,
            String consumerName
    ) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.processor = new RedisStreamRecordProcessor(
                spec,
                handler,
                Objects.requireNonNull(mapper, "mapper"),
                Objects.requireNonNull(clock, "clock"));
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName must not be blank");
        }
        this.consumerName = consumerName;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        handler.verifyPrerequisite();
        if (!(connectionFactory.getRequiredNativeClient() instanceof RedisClient client)) {
            throw new IllegalStateException("Part C stream worker requires a standalone Lettuce RedisClient");
        }
        redisClient = client;
        running.set(true);
        worker = new Thread(this::consume, spec.threadName());
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

    public String consumerName() {
        return consumerName;
    }

    private void consume() {
        StreamRetryBackoff backoff = new StreamRetryBackoff(sleeper);
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
                    log.warn(
                            "Part C Redis stream dependency failure; stream={}, group={}, cause={}",
                            spec.sourceStream(),
                            spec.consumerGroup(),
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
                    consumerGroup(),
                    new XGroupCreateArgs().mkstream(true));
        } catch (RedisCommandExecutionException exception) {
            if (exception.getMessage() == null || !exception.getMessage().startsWith("BUSYGROUP")) {
                throw exception;
            }
        }
    }

    private void drainOwnPending(
            RedisCommands<byte[], byte[]> commands,
            StreamRetryBackoff backoff
    ) {
        while (running.get()) {
            List<StreamMessage<byte[], byte[]>> messages = readOwnPending(commands);
            if (messages.isEmpty()) {
                return;
            }
            process(messages, commands, backoff);
        }
    }

    private void reclaimPending(
            RedisCommands<byte[], byte[]> commands,
            StreamRetryBackoff backoff
    ) {
        String cursor = "0-0";
        do {
            ClaimedMessages<byte[], byte[]> claimed = commands.xautoclaim(
                    sourceStream(),
                    new XAutoClaimArgs<byte[]>()
                            .consumer(consumer())
                            .minIdleTime(spec.reclaimMinIdle())
                            .startId(cursor)
                            .count(BATCH_SIZE));
            process(claimed.getMessages(), commands, backoff);
            cursor = claimed.getId();
        } while (running.get() && !"0-0".equals(cursor));
    }

    private void readNewMessages(
            RedisCommands<byte[], byte[]> commands,
            StreamRetryBackoff backoff
    ) {
        long nextReclaim = System.nanoTime() + spec.reclaimInterval().toNanos();
        while (running.get()) {
            if (System.nanoTime() >= nextReclaim) {
                reclaimPending(commands, backoff);
                nextReclaim = System.nanoTime() + spec.reclaimInterval().toNanos();
            }
            List<StreamMessage<byte[], byte[]>> messages = readNewBatch(commands);
            if (messages.isEmpty()) {
                backoff.reset();
            }
            process(messages, commands, backoff);
        }
    }

    @SuppressWarnings("unchecked")
    private List<StreamMessage<byte[], byte[]>> readOwnPending(
            RedisCommands<byte[], byte[]> commands
    ) {
        return commands.xreadgroup(
                consumer(),
                new XReadArgs().count(BATCH_SIZE),
                XReadArgs.StreamOffset.from(sourceStream(), "0"));
    }

    @SuppressWarnings("unchecked")
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
            StreamRetryBackoff backoff
    ) {
        for (StreamMessage<byte[], byte[]> message : messages) {
            processor.process(message, commands, running::get, backoff);
        }
    }

    private Consumer<byte[]> consumer() {
        return Consumer.from(consumerGroup(), consumerName.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sourceStream() {
        return spec.sourceStream().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] consumerGroup() {
        return spec.consumerGroup().getBytes(StandardCharsets.UTF_8);
    }
}
