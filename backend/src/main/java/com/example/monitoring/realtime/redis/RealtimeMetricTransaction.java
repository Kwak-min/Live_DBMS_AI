package com.example.monitoring.realtime.redis;

import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.realtime.event.MetricBroadcastPort;
import com.example.monitoring.realtime.event.MetricCollectedPayloadV1;
import com.example.monitoring.realtime.event.RealtimeMetricMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public final class RealtimeMetricTransaction {

    static final String CONSUMER_GROUP = "cg:realtime";

    private static final String INSERT_PROCESSED = """
            INSERT INTO processed_events (stream, consumer_group, event_id, processed_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (stream, consumer_group, event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MetricBroadcastPort broadcastPort;
    private final TargetProvider targetProvider;

    public RealtimeMetricTransaction(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            MetricBroadcastPort broadcastPort,
            TargetProvider targetProvider
    ) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.broadcastPort = broadcastPort;
        this.targetProvider = targetProvider;
    }

    public void verifyPrerequisite() {
        try {
            String table = jdbc.queryForObject("SELECT to_regclass('processed_events')", String.class);
            if (table == null) {
                throw missingTable(null);
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw missingTable(exception);
        }
    }

    public Outcome process(String sourceStream, MetricCollectedPayloadV1 payload) {
        Objects.requireNonNull(sourceStream, "sourceStream");
        Objects.requireNonNull(payload, "payload");
        return Objects.requireNonNull(transaction.execute(status -> processInTransaction(sourceStream, payload)));
    }

    private Outcome processInTransaction(String sourceStream, MetricCollectedPayloadV1 payload) {
        int inserted = jdbc.update(
                INSERT_PROCESSED,
                sourceStream,
                CONSUMER_GROUP,
                payload.eventId());
        if (inserted == 0) {
            return Outcome.DUPLICATE;
        }

        Optional<TargetMetadata> target = targetProvider.getMetadata(payload.databaseConfigId());
        if (target.isEmpty() || !target.get().enabled()) {
            return Outcome.SKIPPED;
        }
        long currentVersion = target.get().configVersion();
        if (payload.configVersion() < currentVersion) {
            return Outcome.SKIPPED;
        }
        if (payload.configVersion() > currentVersion) {
            throw new FutureConfigVersionException(payload.eventId());
        }

        broadcastPort.publish(RealtimeMetricMessage.from(payload));
        return Outcome.PUBLISHED;
    }

    private IllegalStateException missingTable(Throwable cause) {
        String message = "Realtime consumer requires A V3 table processed_events; "
                + "disable monitoring.realtime.enabled until the prerequisite migration exists";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    public enum Outcome {
        PUBLISHED,
        DUPLICATE,
        SKIPPED
    }
}
