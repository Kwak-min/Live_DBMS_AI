package com.example.monitoring.incident.query;

import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.ResolutionReason;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IncidentQueryRepository {

    private static final String INCIDENT_COLUMNS = """
            incident_id, database_config_id, database_name, rule_id, rule_type, severity, status,
            opened_at, last_observed_at, resolved_at, resolution_reason, metric_name, metric_value,
            threshold_value, source_metric_id, message, incident_version
            """;

    private final JdbcTemplate jdbc;

    public IncidentQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<Incident> findAll(IncidentQueryCriteria criteria) {
        QueryWhere query = where(criteria);
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM incidents" + query.sql(),
                Long.class,
                query.arguments().toArray());

        List<Object> pageArguments = new ArrayList<>(query.arguments());
        pageArguments.add(criteria.page().size());
        pageArguments.add(criteria.page().offset());
        List<Incident> incidents = jdbc.query(
                "SELECT " + INCIDENT_COLUMNS + " FROM incidents" + query.sql()
                        + " ORDER BY opened_at DESC, incident_id ASC LIMIT ? OFFSET ?",
                this::map,
                pageArguments.toArray());
        int totalPages = total == 0 ? 0 : (int) Math.min(
                Integer.MAX_VALUE,
                1L + (total - 1L) / criteria.page().size());
        return new PageResponse<>(incidents, criteria.page().page(), criteria.page().size(), total, totalPages);
    }

    public Optional<Incident> findById(UUID incidentId) {
        return jdbc.query(
                "SELECT " + INCIDENT_COLUMNS + " FROM incidents WHERE incident_id = ?",
                this::map,
                incidentId).stream().findFirst();
    }

    private QueryWhere where(IncidentQueryCriteria criteria) {
        StringBuilder sql = new StringBuilder(" WHERE opened_at >= ? AND opened_at < ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(criteria.start()));
        arguments.add(Timestamp.from(criteria.end()));
        if (criteria.databaseConfigId() != null) {
            sql.append(" AND database_config_id = ?");
            arguments.add(criteria.databaseConfigId());
        }
        if (criteria.severity() != null) {
            sql.append(" AND severity = ?");
            arguments.add(criteria.severity().name());
        }
        if (criteria.status() != null) {
            sql.append(" AND status = ?");
            arguments.add(criteria.status().name());
        }
        return new QueryWhere(sql.toString(), List.copyOf(arguments));
    }

    private Incident map(ResultSet result, int rowNumber) throws SQLException {
        String reason = result.getString("resolution_reason");
        return new Incident(
                result.getObject("incident_id", UUID.class),
                result.getLong("database_config_id"),
                result.getString("database_name"),
                RuleId.valueOf(result.getString("rule_id")),
                RuleType.valueOf(result.getString("rule_type")),
                IncidentSeverity.valueOf(result.getString("severity")),
                IncidentStatus.valueOf(result.getString("status")),
                instant(result, "opened_at"),
                instant(result, "last_observed_at"),
                instant(result, "resolved_at"),
                reason == null ? null : ResolutionReason.valueOf(reason),
                result.getString("metric_name"),
                result.getBigDecimal("metric_value"),
                result.getBigDecimal("threshold_value"),
                nullableLong(result, "source_metric_id"),
                result.getString("message"),
                result.getLong("incident_version"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private record QueryWhere(String sql, List<Object> arguments) {
    }
}
