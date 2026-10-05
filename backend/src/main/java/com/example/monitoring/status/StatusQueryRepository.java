package com.example.monitoring.status;

import com.example.monitoring.risk.contract.ConnectionStatus;
import com.example.monitoring.risk.contract.DataFreshness;
import com.example.monitoring.risk.contract.RiskLevel;
import com.example.monitoring.risk.contract.StatusSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class StatusQueryRepository {

    private static final String CURRENT_STATUS_SQL = """
            SELECT state.database_config_id,
                   state.config_version,
                   state.enabled,
                   state.connection_status,
                   state.data_freshness,
                   state.risk_level,
                   state.last_attempt_at,
                   state.last_success_at,
                   state.latest_metric_id,
                   state.state_version,
                   state.updated_at,
                   ARRAY(
                       SELECT incident.incident_id
                       FROM incidents incident
                       WHERE incident.database_config_id = state.database_config_id
                         AND incident.status = 'OPEN'
                       ORDER BY incident.incident_id ASC
                   ) AS open_incident_ids
            FROM monitoring_states state
            JOIN database_configs target ON target.id = state.database_config_id
            WHERE state.database_config_id = ?
              AND state.deleted = FALSE
              AND target.deleted_at IS NULL
            """;

    private final JdbcTemplate jdbc;

    public StatusQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StatusSnapshot> findCurrent(long databaseConfigId) {
        return jdbc.query(CURRENT_STATUS_SQL, this::map, databaseConfigId).stream().findFirst();
    }

    private StatusSnapshot map(ResultSet result, int rowNumber) throws SQLException {
        String riskLevel = result.getString("risk_level");
        return new StatusSnapshot(
                result.getLong("database_config_id"),
                result.getLong("config_version"),
                false,
                result.getBoolean("enabled"),
                ConnectionStatus.valueOf(result.getString("connection_status")),
                DataFreshness.valueOf(result.getString("data_freshness")),
                riskLevel == null ? null : RiskLevel.valueOf(riskLevel),
                instant(result, "last_attempt_at"),
                instant(result, "last_success_at"),
                nullableLong(result, "latest_metric_id"),
                uuidList(result.getArray("open_incident_ids")),
                result.getLong("state_version"),
                instant(result, "updated_at"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static List<UUID> uuidList(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        try {
            Object[] values = (Object[]) sqlArray.getArray();
            List<UUID> ids = new ArrayList<>(values.length);
            for (Object value : values) {
                ids.add(value instanceof UUID uuid ? uuid : UUID.fromString(value.toString()));
            }
            return List.copyOf(ids);
        } finally {
            sqlArray.free();
        }
    }
}
