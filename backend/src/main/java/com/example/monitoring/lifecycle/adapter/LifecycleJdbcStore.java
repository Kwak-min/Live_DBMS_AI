package com.example.monitoring.lifecycle.adapter;

import org.springframework.jdbc.core.JdbcOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class LifecycleJdbcStore {

    private static final String LOCK_TARGET = """
            SELECT config_version, enabled, name, deleted_at IS NOT NULL AS deleted
            FROM database_configs
            WHERE id = ?
            FOR UPDATE
            """;

    private static final String LOCK_STATE = """
            SELECT config_version, state_version, enabled, deleted
            FROM monitoring_states
            WHERE database_config_id = ?
            FOR UPDATE
            """;

    private static final String LOCK_OPEN_INCIDENTS = """
            SELECT incident_id, database_config_id, database_name, rule_id, rule_type,
                   severity, opened_at, last_observed_at, metric_name, metric_value,
                   threshold_value, source_metric_id, message, incident_version
            FROM incidents
            WHERE database_config_id = ? AND status = 'OPEN'
            ORDER BY incident_id
            FOR UPDATE
            """;

    private final JdbcOperations jdbc;

    LifecycleJdbcStore(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    Optional<LockedTarget> lockTarget(long databaseConfigId) {
        return jdbc.query(LOCK_TARGET, resultSet -> resultSet.next()
                ? Optional.of(new LockedTarget(
                        resultSet.getLong("config_version"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getString("name"),
                        resultSet.getBoolean("deleted")))
                : Optional.empty(), databaseConfigId);
    }

    Optional<LockedMonitoringState> lockState(long databaseConfigId) {
        return jdbc.query(LOCK_STATE, resultSet -> resultSet.next()
                ? Optional.of(new LockedMonitoringState(
                        resultSet.getLong("config_version"),
                        resultSet.getLong("state_version"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getBoolean("deleted")))
                : Optional.empty(), databaseConfigId);
    }

    List<LockedIncident> lockOpenIncidents(long databaseConfigId) {
        return jdbc.query(LOCK_OPEN_INCIDENTS, (resultSet, ignored) -> mapIncident(resultSet), databaseConfigId);
    }

    void insertState(MonitoringStateWrite state) {
        requireOne(jdbc.update("""
                        INSERT INTO monitoring_states (
                            database_config_id, config_version, state_version, enabled, deleted,
                            connection_status, data_freshness, risk_level, last_attempt_at,
                            last_success_at, latest_metric_id, activation_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'UNKNOWN', ?, NULL, NULL, NULL, NULL, ?, ?)
                        """,
                state.databaseConfigId(), state.configVersion(), state.stateVersion(), state.enabled(), state.deleted(),
                state.dataFreshness(), timestamp(state.activationAt()), timestamp(state.updatedAt())), "insert state");
    }

    void insertDefaultPolicy(long databaseConfigId, String rulesJson, Instant occurredAt) {
        requireOne(jdbc.update("""
                        INSERT INTO risk_policies (
                            database_config_id, version, rules, stale_after_seconds,
                            notification_cooldown_seconds, created_at, updated_at
                        ) VALUES (?, 1, CAST(? AS jsonb), 30, 300, ?, ?)
                        """,
                databaseConfigId, rulesJson, timestamp(occurredAt), timestamp(occurredAt)), "insert policy");
    }

    void updateState(MonitoringStateWrite next, LockedMonitoringState previous) {
        requireOne(jdbc.update("""
                        UPDATE monitoring_states
                        SET config_version = ?, state_version = ?, enabled = ?, deleted = ?,
                            connection_status = 'UNKNOWN', data_freshness = ?, risk_level = NULL,
                            last_attempt_at = NULL, last_success_at = NULL, latest_metric_id = NULL,
                            activation_at = ?, updated_at = ?
                        WHERE database_config_id = ? AND config_version = ? AND state_version = ? AND deleted = FALSE
                        """,
                next.configVersion(), next.stateVersion(), next.enabled(), next.deleted(), next.dataFreshness(),
                timestamp(next.activationAt()), timestamp(next.updatedAt()), next.databaseConfigId(),
                previous.configVersion(), previous.stateVersion()), "update state");
    }

    void deleteRuleStates(long databaseConfigId) {
        jdbc.update("DELETE FROM risk_rule_states WHERE database_config_id = ?", databaseConfigId);
    }

    void resolveIncident(IncidentResolution resolution) {
        requireOne(jdbc.update("""
                        UPDATE incidents
                        SET status = 'RESOLVED', resolved_at = ?, resolution_reason = ?,
                            source_event_id = NULL, incident_version = ?
                        WHERE incident_id = ? AND status = 'OPEN' AND incident_version = ?
                        """,
                timestamp(resolution.resolvedAt()), resolution.reason(), resolution.nextIncidentVersion(),
                resolution.incident().incidentId(), resolution.incident().incidentVersion()), "resolve incident");
    }

    void cancelPendingDeliveries(UUID incidentId) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', next_attempt_at = NULL
                WHERE incident_id = ? AND status = 'PENDING'
                """, incidentId);
    }

    private static LockedIncident mapIncident(ResultSet resultSet) throws SQLException {
        return new LockedIncident(
                resultSet.getObject("incident_id", UUID.class),
                resultSet.getLong("database_config_id"),
                resultSet.getString("database_name"),
                resultSet.getString("rule_id"),
                resultSet.getString("rule_type"),
                resultSet.getString("severity"),
                resultSet.getTimestamp("opened_at").toInstant(),
                resultSet.getTimestamp("last_observed_at").toInstant(),
                resultSet.getString("metric_name"),
                resultSet.getBigDecimal("metric_value"),
                resultSet.getBigDecimal("threshold_value"),
                nullableLong(resultSet, "source_metric_id"),
                resultSet.getString("message"),
                resultSet.getLong("incident_version"));
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static void requireOne(int updatedRows, String action) {
        if (updatedRows != 1) {
            throw new IllegalStateException("Monitoring lifecycle failed to " + action);
        }
    }
}
