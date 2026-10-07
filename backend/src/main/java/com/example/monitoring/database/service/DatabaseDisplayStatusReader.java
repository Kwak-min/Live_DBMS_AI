package com.example.monitoring.database.service;

import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.TargetDbStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DB 목록·상세 응답의 연결 상태(connectionStatus·lastAttemptAt·lastSuccessAt)를 어디서 읽을지 정한다.
 * <ul>
 *   <li>{@code monitoring.risk.enabled=true}: C의 monitoring_states. /status·STOMP 상태 이벤트와 같은 값이다.
 *       이때 A는 database_configs 표시 컬럼을 갱신하지 않는다.</li>
 *   <li>false(기본): monitoring_states가 갱신되지 않으므로 A가 갱신하는 database_configs 표시 컬럼을 읽는다.</li>
 * </ul>
 */
@Component
public class DatabaseDisplayStatusReader {

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean riskEnabled;

    public DatabaseDisplayStatusReader(NamedParameterJdbcTemplate jdbc,
                                       @Value("${monitoring.risk.enabled:false}") boolean riskEnabled) {
        this.jdbc = jdbc;
        this.riskEnabled = riskEnabled;
    }

    public DisplayStatus read(DatabaseConfig config) {
        return readAll(List.of(config)).get(config.getId());
    }

    public Map<Long, DisplayStatus> readAll(Collection<DatabaseConfig> configs) {
        Map<Long, DisplayStatus> result = new HashMap<>();
        if (!riskEnabled) {
            configs.forEach(config -> result.put(config.getId(), new DisplayStatus(
                    config.getStatus(), config.getLastCheckedAt(), config.getLastSuccessAt())));
            return result;
        }
        if (configs.isEmpty()) return result;
        List<Long> ids = configs.stream().map(DatabaseConfig::getId).toList();
        jdbc.query("""
                SELECT database_config_id, connection_status, last_attempt_at, last_success_at
                FROM monitoring_states
                WHERE database_config_id IN (:ids)
                """, new MapSqlParameterSource("ids", ids), rs -> {
            result.put(rs.getLong("database_config_id"), new DisplayStatus(
                    TargetDbStatus.valueOf(rs.getString("connection_status")),
                    instant(rs.getTimestamp("last_attempt_at")),
                    instant(rs.getTimestamp("last_success_at"))));
        });
        // lifecycle이 대상마다 행을 만들지만, 없으면 아직 관측 전으로 본다.
        ids.forEach(id -> result.putIfAbsent(id, new DisplayStatus(TargetDbStatus.UNKNOWN, null, null)));
        return result;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record DisplayStatus(TargetDbStatus connectionStatus, Instant lastAttemptAt, Instant lastSuccessAt) {
    }
}
