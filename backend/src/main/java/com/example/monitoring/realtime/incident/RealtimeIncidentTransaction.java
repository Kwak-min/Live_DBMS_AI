package com.example.monitoring.realtime.incident;

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
public final class RealtimeIncidentTransaction {

    static final String CONSUMER_GROUP = "cg:realtime";
    private static final String INSERT_PROCESSED = """
            INSERT INTO processed_events (stream, consumer_group, event_id, processed_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (stream, consumer_group, event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IncidentBroadcastPort broadcastPort;

    public RealtimeIncidentTransaction(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            IncidentBroadcastPort broadcastPort
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.broadcastPort = Objects.requireNonNull(broadcastPort, "broadcastPort");
    }

    public void verifyPrerequisite() {
        try {
            requireTable("processed_events");
            requireTable("incidents");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw missingPrerequisite(exception);
        }
    }

    public Outcome process(String sourceStream, IncidentStreamEvent event) {
        Objects.requireNonNull(sourceStream, "sourceStream");
        Objects.requireNonNull(event, "event");
        return Objects.requireNonNull(transaction.execute(status -> processInTransaction(sourceStream, event)));
    }

    private Outcome processInTransaction(String sourceStream, IncidentStreamEvent event) {
        if (jdbc.update(INSERT_PROCESSED, sourceStream, CONSUMER_GROUP, event.eventId()) == 0) {
            return Outcome.DUPLICATE;
        }
        List<IncidentVersion> current = jdbc.query("""
                        SELECT database_config_id, incident_version
                        FROM incidents
                        WHERE incident_id = ?
                        FOR SHARE
                        """,
                (result, ignored) -> new IncidentVersion(
                        result.getLong("database_config_id"), result.getLong("incident_version")),
                event.incident().incidentId());
        if (current.isEmpty()) {
            throw future(event);
        }
        IncidentVersion version = current.get(0);
        if (version.databaseConfigId() != event.incident().databaseConfigId()) {
            throw future(event);
        }
        int order = Long.compare(event.incident().incidentVersion(), version.incidentVersion());
        if (order < 0) {
            return Outcome.SKIPPED;
        }
        if (order > 0) {
            throw future(event);
        }
        broadcastPort.publish(RealtimeIncidentMessage.from(event));
        return Outcome.PUBLISHED;
    }

    private void requireTable(String table) {
        String resolved = jdbc.queryForObject("SELECT to_regclass(?)", String.class, table);
        if (resolved == null) {
            throw missingPrerequisite(null);
        }
    }

    private InvariantStreamRecordException future(IncidentStreamEvent event) {
        return new InvariantStreamRecordException(
                "FUTURE_INCIDENT_VERSION",
                "Incident version is ahead of authoritative state",
                event.eventId());
    }

    private IllegalStateException missingPrerequisite(Throwable cause) {
        String message = "Realtime incident consumer requires processed_events and V4 incidents; "
                + "disable monitoring.realtime.enabled until migrations are complete";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private record IncidentVersion(long databaseConfigId, long incidentVersion) {
    }

    public enum Outcome {
        PUBLISHED,
        DUPLICATE,
        SKIPPED
    }
}
