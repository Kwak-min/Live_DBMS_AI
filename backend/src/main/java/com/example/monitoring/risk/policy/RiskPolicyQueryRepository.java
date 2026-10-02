package com.example.monitoring.risk.policy;

import com.example.monitoring.risk.contract.RiskPolicy;
import com.example.monitoring.risk.contract.RiskRule;
import com.example.monitoring.risk.persistence.RiskPersistenceInvariantException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class RiskPolicyQueryRepository {

    private static final TypeReference<List<RiskRule>> RULES = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RiskPolicyQueryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<RiskPolicy> findCurrent(long databaseConfigId) {
        return jdbc.query("""
                SELECT policy.database_config_id, policy.version, policy.stale_after_seconds,
                       policy.notification_cooldown_seconds, policy.rules::text, policy.updated_at
                FROM risk_policies policy
                JOIN database_configs target ON target.id = policy.database_config_id
                WHERE policy.database_config_id = ? AND target.deleted_at IS NULL
                """, this::map, databaseConfigId).stream().findFirst();
    }

    private RiskPolicy map(ResultSet result, int rowNumber) throws SQLException {
        return new RiskPolicy(
                result.getLong("database_config_id"),
                result.getLong("version"),
                result.getInt("stale_after_seconds"),
                result.getInt("notification_cooldown_seconds"),
                rules(result.getString("rules")),
                instant(result, "updated_at"));
    }

    private List<RiskRule> rules(String json) {
        try {
            return objectMapper.readValue(json, RULES);
        } catch (JsonProcessingException exception) {
            throw new RiskPersistenceInvariantException("Stored risk policy rules are invalid", exception);
        }
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        if (value == null) {
            throw new RiskPersistenceInvariantException("Stored risk policy timestamp is missing");
        }
        return value.toInstant();
    }
}
