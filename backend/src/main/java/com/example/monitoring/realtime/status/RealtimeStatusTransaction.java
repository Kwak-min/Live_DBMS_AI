package com.example.monitoring.realtime.status;

import com.example.monitoring.common.stream.InvariantStreamRecordException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public final class RealtimeStatusTransaction {

    static final String CONSUMER_GROUP = "cg:realtime";
    private static final String INSERT_PROCESSED = """
            INSERT INTO processed_events (stream, consumer_group, event_id, processed_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (stream, consumer_group, event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StatusBroadcastPort broadcastPort;

    public RealtimeStatusTransaction(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            StatusBroadcastPort broadcastPort
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.broadcastPort = Objects.requireNonNull(broadcastPort, "broadcastPort");
    }

    public void verifyPrerequisite() {
        try {
            requireTable("processed_events");
            requireTable("monitoring_states");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw missingPrerequisite(exception);
        }
    }

    public Outcome process(String sourceStream, StatusStreamEvent event) {
        Objects.requireNonNull(sourceStream, "sourceStream");
        Objects.requireNonNull(event, "event");
        return Objects.requireNonNull(transaction.execute(status -> processInTransaction(sourceStream, event)));
    }

    private Outcome processInTransaction(String sourceStream, StatusStreamEvent event) {
        if (jdbc.update(INSERT_PROCESSED, sourceStream, CONSUMER_GROUP, event.eventId()) == 0) {
            return Outcome.DUPLICATE;
        }
        List<StatusVersion> current = jdbc.query("""
                        SELECT config_version, state_version
                        FROM monitoring_states
                        WHERE database_config_id = ?
                        FOR SHARE
                        """,
                (result, ignored) -> new StatusVersion(
                        result.getLong("config_version"), result.getLong("state_version")),
                event.status().databaseConfigId());
        if (current.isEmpty()) {
            throw future(event);
        }
        StatusVersion version = current.get(0);
        int configOrder = Long.compare(event.status().configVersion(), version.configVersion());
        if (configOrder < 0) {
            return Outcome.SKIPPED;
        }
        if (configOrder > 0) {
            throw future(event);
        }
        int stateOrder = Long.compare(event.status().stateVersion(), version.stateVersion());
        if (stateOrder < 0) {
            return Outcome.SKIPPED;
        }
        if (stateOrder > 0) {
            throw future(event);
        }
        broadcastPort.publish(RealtimeStatusMessage.from(event));
        return Outcome.PUBLISHED;
    }

    private void requireTable(String table) {
        String resolved = jdbc.queryForObject("SELECT to_regclass(?)", String.class, table);
        if (resolved == null) {
            throw missingPrerequisite(null);
        }
    }

    private InvariantStreamRecordException future(StatusStreamEvent event) {
        return new InvariantStreamRecordException(
                "FUTURE_STATUS_VERSION", "Status version is ahead of authoritative state", event.eventId());
    }

    private IllegalStateException missingPrerequisite(Throwable cause) {
        String message = "Realtime status consumer requires processed_events and V4 monitoring_states; "
                + "disable monitoring.realtime.enabled until migrations are complete";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private record StatusVersion(long configVersion, long stateVersion) {
    }

    public enum Outcome {
        PUBLISHED,
        DUPLICATE,
        SKIPPED
    }
}
