package com.example.monitoring.risk.persistence;

import com.example.monitoring.risk.contract.ConnectionStatus;
import com.example.monitoring.risk.contract.DataFreshness;
import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.ResolutionReason;
import com.example.monitoring.risk.contract.RiskLevel;
import com.example.monitoring.risk.contract.RiskPolicy;
import com.example.monitoring.risk.contract.RiskRule;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleType;
import com.example.monitoring.risk.engine.RiskState;
import com.example.monitoring.risk.engine.RuleClock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RiskJdbcStore {

    private static final TypeReference<List<RiskRule>> RISK_RULES = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RiskJdbcStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RiskMutationLock> lockForMutation(long databaseConfigId) {
        Optional<LockedTarget> target = lockTarget(databaseConfigId);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        RiskState state = lockState(databaseConfigId)
                .orElseThrow(() -> invariant("monitoring state is missing"));
        RiskPolicy policy = lockPolicy(databaseConfigId)
                .orElseThrow(() -> invariant("risk policy is missing"));
        List<Incident> incidents = lockOpenIncidents(databaseConfigId);
        List<PendingDelivery> deliveries = lockPendingDeliveries(databaseConfigId);
        EnumMap<RuleId, RuleClock> clocks = lockRuleClocks(databaseConfigId);
        return Optional.of(new RiskMutationLock(
                target.get(), state, policy, clocks, incidents, deliveries));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedTarget> lockTarget(long databaseConfigId) {
        return one(jdbc.query("""
                SELECT id, config_version, enabled, deleted_at IS NOT NULL AS deleted, name
                FROM database_configs
                WHERE id = ?
                FOR UPDATE
                """, (rs, ignored) -> new LockedTarget(
                rs.getLong("id"),
                rs.getLong("config_version"),
                rs.getBoolean("enabled"),
                rs.getBoolean("deleted"),
                rs.getString("name")), databaseConfigId), "target");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RiskState> lockState(long databaseConfigId) {
        return one(jdbc.query("""
                SELECT database_config_id, config_version, state_version, enabled, deleted,
                       connection_status, data_freshness, risk_level, activation_at,
                       last_attempt_at, last_success_at, latest_metric_id, updated_at
                FROM monitoring_states
                WHERE database_config_id = ?
                FOR UPDATE
                """, stateMapper(), databaseConfigId), "monitoring state");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RiskPolicy> lockPolicy(long databaseConfigId) {
        return one(jdbc.query("""
                SELECT database_config_id, version, stale_after_seconds,
                       notification_cooldown_seconds, rules::text, updated_at
                FROM risk_policies
                WHERE database_config_id = ?
                FOR UPDATE
                """, policyMapper(), databaseConfigId), "risk policy");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EnumMap<RuleId, RuleClock> lockRuleClocks(long databaseConfigId) {
        EnumMap<RuleId, RuleClock> result = new EnumMap<>(RuleId.class);
        jdbc.query("""
                SELECT rule_id, warning_candidate_since, critical_candidate_since,
                       fatal_candidate_since, recovery_since, last_observed_at, last_metric_id
                FROM risk_rule_states
                WHERE database_config_id = ?
                ORDER BY rule_id
                FOR UPDATE
                """, rs -> {
            RuleClock clock = ruleClock(rs);
            result.put(clock.ruleId(), clock);
        }, databaseConfigId);
        return result;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Incident> lockOpenIncidents(long databaseConfigId) {
        return jdbc.query("""
                SELECT incident_id, database_config_id, database_name, rule_id, rule_type,
                       severity, status, opened_at, last_observed_at, resolved_at,
                       resolution_reason, metric_name, metric_value, threshold_value,
                       source_metric_id, message, incident_version
                FROM incidents
                WHERE database_config_id = ? AND status = 'OPEN'
                ORDER BY incident_id
                FOR UPDATE
                """, incidentMapper(), databaseConfigId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<PendingDelivery> lockPendingDeliveries(long databaseConfigId) {
        return jdbc.query("""
                SELECT d.id, d.incident_id
                FROM notification_deliveries d
                JOIN incidents i ON i.incident_id = d.incident_id
                WHERE i.database_config_id = ? AND d.status = 'PENDING'
                ORDER BY d.id
                FOR UPDATE OF d
                """, (rs, ignored) -> new PendingDelivery(
                rs.getLong("id"), rs.getObject("incident_id", UUID.class)), databaseConfigId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveRuleClock(long databaseConfigId, RuleClock clock, Instant updatedAt) {
        int count = jdbc.update("""
                INSERT INTO risk_rule_states (
                    database_config_id, rule_id, warning_candidate_since,
                    critical_candidate_since, fatal_candidate_since, recovery_since,
                    last_observed_at, last_metric_id, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (database_config_id, rule_id) DO UPDATE SET
                    warning_candidate_since = EXCLUDED.warning_candidate_since,
                    critical_candidate_since = EXCLUDED.critical_candidate_since,
                    fatal_candidate_since = EXCLUDED.fatal_candidate_since,
                    recovery_since = EXCLUDED.recovery_since,
                    last_observed_at = EXCLUDED.last_observed_at,
                    last_metric_id = EXCLUDED.last_metric_id,
                    updated_at = EXCLUDED.updated_at
                """,
                databaseConfigId,
                clock.ruleId().name(),
                timestamp(clock.warningCandidateSince()),
                timestamp(clock.criticalCandidateSince()),
                timestamp(clock.fatalCandidateSince()),
                timestamp(clock.recoverySince()),
                timestamp(clock.lastObservedAt()),
                clock.lastMetricId(),
                timestamp(requiredMillis(updatedAt, "updatedAt")));
        requireCount(count, 1, "rule clock upsert");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertIncident(Incident incident, UUID sourceEventId) {
        int count = jdbc.update("""
                INSERT INTO incidents (
                    incident_id, database_config_id, database_name, rule_id, rule_type,
                    severity, status, opened_at, last_observed_at, resolved_at,
                    resolution_reason, metric_name, metric_value, threshold_value,
                    source_metric_id, source_event_id, message, incident_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                incident.incidentId(),
                incident.databaseConfigId(),
                incident.databaseName(),
                incident.ruleId().name(),
                incident.ruleType().name(),
                incident.severity().name(),
                incident.status().name(),
                timestamp(incident.openedAt()),
                timestamp(incident.lastObservedAt()),
                timestamp(incident.resolvedAt()),
                name(incident.resolutionReason()),
                incident.metricName(),
                incident.metricValue(),
                incident.thresholdValue(),
                incident.sourceMetricId(),
                sourceEventId,
                incident.message(),
                incident.incidentVersion());
        requireCount(count, 1, "incident insert");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updateIncident(Incident incident, UUID sourceEventId) {
        if (incident.incidentVersion() <= 1L) {
            throw new IllegalArgumentException("Updated incident version must be greater than one");
        }
        int count = jdbc.update("""
                UPDATE incidents
                SET severity = ?, status = ?, last_observed_at = ?, resolved_at = ?,
                    resolution_reason = ?, metric_name = ?, metric_value = ?,
                    threshold_value = ?, source_metric_id = ?, source_event_id = ?,
                    message = ?, incident_version = ?
                WHERE incident_id = ? AND status = 'OPEN' AND incident_version = ?
                """,
                incident.severity().name(),
                incident.status().name(),
                timestamp(incident.lastObservedAt()),
                timestamp(incident.resolvedAt()),
                name(incident.resolutionReason()),
                incident.metricName(),
                incident.metricValue(),
                incident.thresholdValue(),
                incident.sourceMetricId(),
                sourceEventId,
                incident.message(),
                incident.incidentVersion(),
                incident.incidentId(),
                incident.incidentVersion() - 1L);
        requireCount(count, 1, "incident update");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int touchOpenIncidents(long databaseConfigId, Instant observedAt) {
        Instant at = requiredMillis(observedAt, "observedAt");
        return jdbc.update("""
                UPDATE incidents
                SET last_observed_at = GREATEST(last_observed_at, ?)
                WHERE database_config_id = ? AND status = 'OPEN'
                """, timestamp(at), databaseConfigId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updateState(RiskState state) {
        if (state.stateVersion() <= 1L) {
            throw new IllegalArgumentException("Updated state version must be greater than one");
        }
        int count = jdbc.update("""
                UPDATE monitoring_states
                SET state_version = ?, enabled = ?, deleted = ?, connection_status = ?,
                    data_freshness = ?, risk_level = ?, activation_at = ?,
                    last_attempt_at = ?, last_success_at = ?, latest_metric_id = ?, updated_at = ?
                WHERE database_config_id = ? AND config_version = ? AND state_version = ?
                """,
                state.stateVersion(),
                state.enabled(),
                state.deleted(),
                state.connectionStatus().name(),
                state.dataFreshness().name(),
                name(state.riskLevel()),
                timestamp(state.activationAt()),
                timestamp(state.lastAttemptAt()),
                timestamp(state.lastSuccessAt()),
                state.latestMetricId(),
                timestamp(state.updatedAt()),
                state.databaseConfigId(),
                state.configVersion(),
                state.stateVersion() - 1L);
        requireCount(count, 1, "monitoring state update");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updatePolicy(RiskPolicy policy, long expectedVersion) {
        if (policy.version() != expectedVersion + 1L) {
            throw new IllegalArgumentException("Risk policy version must advance by exactly one");
        }
        int count = jdbc.update("""
                UPDATE risk_policies
                SET version = ?, rules = CAST(? AS jsonb), stale_after_seconds = ?,
                    notification_cooldown_seconds = ?, updated_at = ?
                WHERE database_config_id = ? AND version = ?
                """,
                policy.version(),
                writeRules(policy.rules()),
                policy.staleAfterSeconds(),
                policy.notificationCooldownSeconds(),
                timestamp(policy.updatedAt()),
                policy.databaseConfigId(),
                expectedVersion);
        requireCount(count, 1, "risk policy update");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int clearConfigurableRuleClocks(long databaseConfigId) {
        return jdbc.update("""
                DELETE FROM risk_rule_states
                WHERE database_config_id = ?
                  AND rule_id IN ('CONNECTION_RATIO', 'SLOW_QUERY_RATE')
                """, databaseConfigId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int clearTransientRuleClocks() {
        return jdbc.update("""
                UPDATE risk_rule_states
                SET warning_candidate_since = NULL,
                    critical_candidate_since = NULL,
                    fatal_candidate_since = NULL,
                    recovery_since = NULL
                WHERE warning_candidate_since IS NOT NULL
                   OR critical_candidate_since IS NOT NULL
                   OR fatal_candidate_since IS NOT NULL
                   OR recovery_since IS NOT NULL
                """);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelPendingDeliveries(UUID incidentId) {
        return jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', next_attempt_at = NULL
                WHERE incident_id = ? AND status = 'PENDING'
                """, incidentId);
    }

    public List<StaleCandidate> findStaleCandidates(Instant scannedAt, int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be 1..1000");
        }
        Instant at = requiredMillis(scannedAt, "scannedAt");
        return jdbc.query("""
                SELECT s.database_config_id, deadline.due_at
                FROM monitoring_states s
                JOIN risk_policies p ON p.database_config_id = s.database_config_id
                LEFT JOIN LATERAL (
                    SELECT m.collection_attempt_time AS last_attempt_at
                    FROM metric_data m
                    WHERE m.database_config_id = s.database_config_id
                      AND m.config_version = s.config_version
                      AND m.collection_attempt_time >= s.activation_at
                      AND m.collection_attempt_time <= ?
                      AND m.timestamp >= s.activation_at AND m.timestamp <= ?
                    ORDER BY m.timestamp DESC, m.id DESC
                    LIMIT 1
                ) durable ON true
                CROSS JOIN LATERAL (
                    SELECT date_trunc('milliseconds',
                        GREATEST(COALESCE(s.last_attempt_at, s.activation_at),
                                 durable.last_attempt_at)
                        + p.stale_after_seconds * INTERVAL '1 second') AS due_at
                ) deadline
                WHERE s.enabled = true
                  AND s.deleted = false
                  AND s.data_freshness <> 'STALE'
                  AND ? >= COALESCE(s.last_attempt_at, s.activation_at)
                           + p.stale_after_seconds * INTERVAL '1 second'
                  AND ? >= deadline.due_at
                ORDER BY due_at, s.database_config_id
                LIMIT ?
                """, (rs, ignored) -> new StaleCandidate(
                rs.getLong("database_config_id"), instant(rs, "due_at")),
                timestamp(at), timestamp(at), timestamp(at), timestamp(at), limit);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Instant> latestDurableAttempt(RiskState state, Instant scannedAt) {
        List<Instant> latest = jdbc.query("""
                SELECT collection_attempt_time
                FROM metric_data
                WHERE database_config_id = ? AND config_version = ?
                  AND collection_attempt_time >= ? AND collection_attempt_time <= ?
                  AND timestamp >= ? AND timestamp <= ?
                ORDER BY timestamp DESC, id DESC
                LIMIT 1
                """, (rs, ignored) -> instant(rs, "collection_attempt_time"),
                state.databaseConfigId(), state.configVersion(),
                timestamp(state.activationAt()), timestamp(requiredMillis(scannedAt, "scannedAt")),
                timestamp(state.activationAt()), timestamp(requiredMillis(scannedAt, "scannedAt")));
        return latest.stream().findFirst();
    }

    private RowMapper<RiskState> stateMapper() {
        return (rs, ignored) -> new RiskState(
                rs.getLong("database_config_id"),
                rs.getLong("config_version"),
                rs.getLong("state_version"),
                rs.getBoolean("enabled"),
                rs.getBoolean("deleted"),
                ConnectionStatus.valueOf(rs.getString("connection_status")),
                DataFreshness.valueOf(rs.getString("data_freshness")),
                enumOrNull(RiskLevel.class, rs.getString("risk_level")),
                instantOrNull(rs, "activation_at"),
                instantOrNull(rs, "last_attempt_at"),
                instantOrNull(rs, "last_success_at"),
                nullableLong(rs, "latest_metric_id"),
                instant(rs, "updated_at"));
    }

    private RowMapper<RiskPolicy> policyMapper() {
        return (rs, ignored) -> new RiskPolicy(
                rs.getLong("database_config_id"),
                rs.getLong("version"),
                rs.getInt("stale_after_seconds"),
                rs.getInt("notification_cooldown_seconds"),
                readRules(rs.getString("rules")),
                instant(rs, "updated_at"));
    }

    private RowMapper<Incident> incidentMapper() {
        return (rs, ignored) -> new Incident(
                rs.getObject("incident_id", UUID.class),
                rs.getLong("database_config_id"),
                rs.getString("database_name"),
                RuleId.valueOf(rs.getString("rule_id")),
                RuleType.valueOf(rs.getString("rule_type")),
                IncidentSeverity.valueOf(rs.getString("severity")),
                IncidentStatus.valueOf(rs.getString("status")),
                instant(rs, "opened_at"),
                instant(rs, "last_observed_at"),
                instantOrNull(rs, "resolved_at"),
                enumOrNull(ResolutionReason.class, rs.getString("resolution_reason")),
                rs.getString("metric_name"),
                rs.getBigDecimal("metric_value"),
                rs.getBigDecimal("threshold_value"),
                nullableLong(rs, "source_metric_id"),
                rs.getString("message"),
                rs.getLong("incident_version"));
    }

    private RuleClock ruleClock(ResultSet rs) throws SQLException {
        return new RuleClock(
                RuleId.valueOf(rs.getString("rule_id")),
                instantOrNull(rs, "warning_candidate_since"),
                instantOrNull(rs, "critical_candidate_since"),
                instantOrNull(rs, "fatal_candidate_since"),
                instantOrNull(rs, "recovery_since"),
                instantOrNull(rs, "last_observed_at"),
                nullableLong(rs, "last_metric_id"));
    }

    private List<RiskRule> readRules(String json) {
        try {
            return objectMapper.readValue(json, RISK_RULES);
        } catch (JsonProcessingException exception) {
            throw invariant("stored risk policy JSON is invalid", exception);
        }
    }

    private String writeRules(List<RiskRule> rules) {
        try {
            return objectMapper.writeValueAsString(rules);
        } catch (JsonProcessingException exception) {
            throw invariant("risk policy JSON serialization failed", exception);
        }
    }

    private <T> Optional<T> one(List<T> rows, String description) {
        if (rows.size() > 1) {
            throw invariant("more than one " + description + " row was locked");
        }
        return rows.stream().findFirst();
    }

    private void requireCount(int actual, int expected, String operation) {
        if (actual != expected) {
            throw invariant(operation + " affected " + actual + " rows instead of " + expected);
        }
    }

    private Instant requiredMillis(Instant value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.truncatedTo(ChronoUnit.MILLIS);
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value.truncatedTo(ChronoUnit.MILLIS));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private RiskPersistenceInvariantException invariant(String message) {
        return new RiskPersistenceInvariantException("Risk persistence invariant failed: " + message);
    }

    private RiskPersistenceInvariantException invariant(String message, Throwable cause) {
        return new RiskPersistenceInvariantException(
                "Risk persistence invariant failed: " + message, cause);
    }
}
