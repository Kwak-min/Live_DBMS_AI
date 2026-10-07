package com.example.monitoring.common.stream;

import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "partc.stream.integration", matches = "true")
class RedisStreamRetentionIntegrationTest {

    private static final List<String> GROUPS = List.of("cg:risk", "cg:realtime");
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;
    private LettuceConnectionFactory factory;
    private RedisStreamRetentionScheduler retention;
    private String metrics;
    private String heartbeat;
    private String statuses;
    private String incidents;
    private long oldTime;

    @BeforeEach
    void setUp() {
        int port = Integer.parseInt(System.getProperty("partc.stream.redis.port"));
        client = RedisClient.create("redis://127.0.0.1:" + port);
        connection = client.connect();
        commands = connection.sync();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
        factory.afterPropertiesSet();
        factory.start();
        metrics = "test:retention:metrics:" + UUID.randomUUID();
        heartbeat = "test:retention:heartbeat:" + UUID.randomUUID();
        statuses = "test:retention:statuses:" + UUID.randomUUID();
        incidents = "test:retention:incidents:" + UUID.randomUUID();
        retention = new RedisStreamRetentionScheduler(new StringRedisTemplate(factory),
                metrics, heartbeat, statuses, incidents);
        oldTime = System.currentTimeMillis() - Duration.ofHours(25).toMillis();
    }

    @AfterEach
    void tearDown() {
        if (commands != null) {
            commands.del(metrics, heartbeat, statuses, incidents);
        }
        if (factory != null) {
            factory.destroy();
        }
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    @Test
    void removesOldAcknowledgedRecordsAndKeepsRecentRecords() {
        String old = add(metrics, oldTime, 0);
        String recent = add(metrics, System.currentTimeMillis(), 0);
        for (String group : GROUPS) {
            createGroup(metrics, group);
            read(metrics, group, 10);
            commands.xack(metrics, group, old, recent);
        }

        assertThat(retention.trim(metrics, GROUPS)).isOne();
        assertThat(commands.xrange(metrics, io.lettuce.core.Range.unbounded()))
                .extracting(io.lettuce.core.StreamMessage::getId).containsExactly(recent);
    }

    @Test
    void pendingPayloadSurvivesUntilAcknowledgement() {
        String first = add(metrics, oldTime, 0);
        String pending = add(metrics, oldTime, 1);
        String last = add(metrics, oldTime, 2);
        for (String group : GROUPS) {
            createGroup(metrics, group);
            read(metrics, group, 10);
            commands.xack(metrics, group, first, last);
        }
        assertThat(retention.trim(metrics, GROUPS)).isOne();
        assertThat(commands.xpending(metrics, "cg:risk").getCount()).isOne();
        assertThat(commands.xrange(metrics, io.lettuce.core.Range.create(pending, pending))).hasSize(1);
        GROUPS.forEach(group -> commands.xack(metrics, group, pending));
        assertThat(retention.trim(metrics, GROUPS)).isOne();
        assertThat(commands.xlen(metrics)).isOne();
    }

    @Test
    void slowerGroupKeepsAllUnreadEntries() {
        String first = add(metrics, oldTime, 0);
        String second = add(metrics, oldTime, 1);
        String unread = add(metrics, oldTime, 2);
        createGroup(metrics, "cg:risk");
        read(metrics, "cg:risk", 10);
        commands.xack(metrics, "cg:risk", first, second, unread);
        createGroup(metrics, "cg:realtime");
        read(metrics, "cg:realtime", 2);
        commands.xack(metrics, "cg:realtime", first, second);
        assertThat(retention.trim(metrics, GROUPS)).isOne();
        assertThat(commands.xrange(metrics, io.lettuce.core.Range.unbounded()))
                .extracting(io.lettuce.core.StreamMessage::getId).containsExactly(second, unread);
    }

    @Test
    void missingRequiredGroupPreventsTrimEvenIfOtherGroupHasAcknowledged() {
        String first = add(metrics, oldTime, 0);
        String last = add(metrics, oldTime, 1);
        createGroup(metrics, "cg:risk");
        read(metrics, "cg:risk", 10);
        commands.xack(metrics, "cg:risk", first, last);
        assertThat(retention.trim(metrics, GROUPS)).isZero();
        createGroup(metrics, "cg:realtime");
        assertThat(retention.trim(metrics, GROUPS)).isZero();
        read(metrics, "cg:realtime", 10);
        commands.xack(metrics, "cg:realtime", first, last);
        assertThat(retention.trim(metrics, GROUPS)).isOne();
    }

    @Test
    void additionalGroupAlsoProtectsItsPendingAndUnreadEntries() {
        String first = add(metrics, oldTime, 0);
        String last = add(metrics, oldTime, 1);
        for (String group : GROUPS) {
            createGroup(metrics, group);
            read(metrics, group, 10);
            commands.xack(metrics, group, first, last);
        }
        createGroup(metrics, "cg:extra");
        assertThat(retention.trim(metrics, GROUPS)).isZero();
        read(metrics, "cg:extra", 10);
        assertThat(retention.trim(metrics, GROUPS)).isZero();
        commands.xack(metrics, "cg:extra", first, last);
        assertThat(retention.trim(metrics, GROUPS)).isOne();
    }

    @Test
    void noGroupsAndAbsentKeysAreKeptWithoutCreatingKeys() {
        assertThat(retention.trim(metrics, GROUPS)).isZero();
        assertThat(commands.exists(metrics)).isZero();
        add(metrics, oldTime, 0);
        assertThat(retention.trim(metrics, GROUPS)).isZero();
        assertThat(commands.xlen(metrics)).isOne();
    }

    @Test
    void sub24HourAndFutureIdsAreRetained() {
        String old = add(metrics, oldTime, 0);
        String recent = add(metrics, System.currentTimeMillis() - Duration.ofHours(23).toMillis(), 0);
        String future = add(metrics, System.currentTimeMillis() + Duration.ofHours(1).toMillis(), 0);
        for (String group : GROUPS) {
            createGroup(metrics, group);
            read(metrics, group, 10);
            commands.xack(metrics, group, old, recent, future);
        }
        assertThat(retention.trim(metrics, GROUPS)).isOne();
        assertThat(commands.xrange(metrics, io.lettuce.core.Range.unbounded()))
                .extracting(io.lettuce.core.StreamMessage::getId).containsExactly(recent, future);
    }

    @Test
    void comparesSequenceIdsBeyondLuaIntegerPrecisionWithoutDroppingPending() {
        String first = add(metrics, oldTime, 9_007_199_254_740_992L);
        String pending = add(metrics, oldTime, 9_007_199_254_740_993L);
        String last = add(metrics, oldTime, 9_007_199_254_740_994L);
        for (String group : GROUPS) {
            createGroup(metrics, group);
            read(metrics, group, 10);
            commands.xack(metrics, group, first, last);
        }
        assertThat(retention.trim(metrics, GROUPS)).isOne();
        assertThat(commands.xrange(metrics, io.lettuce.core.Range.create(pending, pending))).hasSize(1);
    }

    @Test
    void boundsEachStreamToOneThousandDeletionsPerCycle() {
        for (int i = 0; i < 1_001; i++) {
            add(metrics, oldTime, i);
        }
        add(metrics, System.currentTimeMillis(), 0);
        for (String group : GROUPS) {
            createGroup(metrics, group);
            acknowledgeAll(metrics, group);
        }
        assertThat(retention.trim(metrics, GROUPS)).isEqualTo(1_000);
        assertThat(commands.xlen(metrics)).isEqualTo(2);
        assertThat(retention.trim(metrics, GROUPS)).isOne();
        assertThat(commands.xlen(metrics)).isOne();
    }

    @Test
    void failingStreamDoesNotBlockOtherStreamsOrNextCycle() {
        commands.set(metrics, "wrong-type");
        seedAcknowledged(heartbeat, List.of("cg:risk"));
        seedAcknowledged(statuses, List.of("cg:realtime"));
        seedAcknowledged(incidents, List.of("cg:realtime", "cg:notification"));
        retention.trimStreams();
        assertThat(commands.xlen(heartbeat)).isOne();
        assertThat(commands.xlen(statuses)).isOne();
        assertThat(commands.xlen(incidents)).isOne();
        commands.del(metrics);
        seedAcknowledged(metrics, GROUPS);
        retention.trimStreams();
        assertThat(commands.xlen(metrics)).isOne();
    }

    @Test
    void incidentNotificationGroupMustExistBeforeDeletingHistory() {
        seedAcknowledged(incidents, List.of("cg:realtime"));
        retention.trimStreams();
        assertThat(commands.xlen(incidents)).isEqualTo(2);
        createGroup(incidents, "cg:notification");
        acknowledgeAll(incidents, "cg:notification");
        retention.trimStreams();
        assertThat(commands.xlen(incidents)).isOne();
    }

    @Test
    void scheduledTriggerUsesConfiguredKeysWithoutDirectWorkerCall() {
        seedAcknowledged(metrics, GROUPS);
        new WebApplicationContextRunner()
                .withUserConfiguration(SchedulingConfiguration.class)
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(factory))
                .withPropertyValues("app.redis.stream-key=" + metrics,
                        "app.redis.heartbeat-stream-key=" + heartbeat,
                        "app.redis.status-stream-key=" + statuses,
                        "app.redis.incident-stream-key=" + incidents,
                        "app.redis.stream-retention.interval-ms=20")
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisStreamRetentionScheduler.class);
                    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (commands.xlen(metrics) != 1 && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                    }
                    assertThat(commands.xlen(metrics)).isOne();
                });
    }

    private void seedAcknowledged(String stream, List<String> groups) {
        add(stream, oldTime, 0);
        add(stream, System.currentTimeMillis(), 0);
        for (String group : groups) {
            createGroup(stream, group);
            acknowledgeAll(stream, group);
        }
    }

    @SuppressWarnings("unchecked")
    private void acknowledgeAll(String stream, String group) {
        var records = commands.xreadgroup(Consumer.from(group, "test-consumer"),
                new XReadArgs().count(2_000), XReadArgs.StreamOffset.lastConsumed(stream));
        commands.xack(stream, group, records.stream().map(io.lettuce.core.StreamMessage::getId)
                .toArray(String[]::new));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(RedisStreamRetentionScheduler.class)
    static class SchedulingConfiguration {
    }

    private String add(String stream, long time, long sequence) {
        return commands.xadd(stream, new XAddArgs().id(time + "-" + sequence), Map.of("payload", "{}"));
    }

    private void createGroup(String stream, String group) {
        commands.xgroupCreate(XReadArgs.StreamOffset.from(stream, "0-0"), group);
    }

    @SuppressWarnings("unchecked")
    private void read(String stream, String group, int count) {
        commands.xreadgroup(Consumer.from(group, "test-consumer"), new XReadArgs().count(count),
                XReadArgs.StreamOffset.lastConsumed(stream));
    }
}
