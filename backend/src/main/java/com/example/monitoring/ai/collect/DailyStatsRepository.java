package com.example.monitoring.ai.collect;

import com.example.monitoring.ai.model.DailyStats;
import com.example.monitoring.ai.model.HourlyStat;
import com.example.monitoring.ai.model.IncidentSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 일일 보고서 근거가 되는 metric_data·incidents 집계. 모든 구간은 [start, end) 반개구간이다. */
@Repository
public class DailyStatsRepository {

    static final int MAX_INCIDENTS = 50;

    private static final String SUMMARY_SQL = """
            SELECT COUNT(*) AS samples,
                   COUNT(*) FILTER (WHERE collection_status = 'SUCCESS') AS success,
                   COUNT(*) FILTER (WHERE collection_status = 'PARTIAL_FAILURE') AS partial,
                   COUNT(*) FILTER (WHERE collection_status = 'CONNECTION_FAILED') AS failed,
                   AVG(active_connections) AS avg_conn,
                   MAX(active_connections) AS max_conn,
                   MAX(max_connections) AS max_conn_limit,
                   AVG(100.0 * active_connections / NULLIF(max_connections, 0)) AS avg_usage,
                   MAX(100.0 * active_connections / NULLIF(max_connections, 0)) AS max_usage,
                   AVG(qps) AS avg_qps,
                   MAX(qps) AS max_qps,
                   SUM(slow_queries_delta) AS slow_total,
                   MAX(slow_queries_per_second) AS max_slow_ps,
                   AVG(threads_running) AS avg_running,
                   MAX(threads_running) AS max_running,
                   AVG(response_time_ms) AS avg_rt,
                   PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY response_time_ms) AS p95_rt
            FROM metric_data
            WHERE database_config_id = ? AND timestamp >= ? AND timestamp < ?
            """;

    private static final String STORAGE_SQL = """
            SELECT (SELECT storage_bytes FROM metric_data
                    WHERE database_config_id = ? AND timestamp >= ? AND timestamp < ? AND storage_bytes IS NOT NULL
                    ORDER BY timestamp ASC, id ASC LIMIT 1) AS first_storage,
                   (SELECT storage_bytes FROM metric_data
                    WHERE database_config_id = ? AND timestamp >= ? AND timestamp < ? AND storage_bytes IS NOT NULL
                    ORDER BY timestamp DESC, id DESC LIMIT 1) AS last_storage
            """;

    private static final String ERROR_SQL = """
            SELECT error_code, COUNT(*) AS cnt
            FROM metric_data
            WHERE database_config_id = ? AND timestamp >= ? AND timestamp < ? AND error_code IS NOT NULL
            GROUP BY error_code
            ORDER BY cnt DESC, error_code
            """;

    private static final String HOURLY_SQL = """
            SELECT date_bin('1 hour', timestamp, CAST(? AS timestamptz)) AS hour_start,
                   COUNT(*) AS samples,
                   COUNT(*) FILTER (WHERE collection_status = 'CONNECTION_FAILED') AS failed,
                   AVG(100.0 * active_connections / NULLIF(max_connections, 0)) AS avg_usage,
                   AVG(qps) AS avg_qps,
                   SUM(slow_queries_delta) AS slow_total,
                   AVG(response_time_ms) AS avg_rt
            FROM metric_data
            WHERE database_config_id = ? AND timestamp >= ? AND timestamp < ?
            GROUP BY hour_start
            ORDER BY hour_start
            """;

    private static final String INCIDENT_COUNT_SQL = """
            SELECT COUNT(*) FROM incidents
            WHERE database_config_id = ? AND opened_at < ? AND (resolved_at IS NULL OR resolved_at >= ?)
            """;

    private static final String INCIDENT_SQL = """
            SELECT incident_id, rule_id, severity, status, opened_at, resolved_at,
                   metric_name, metric_value, threshold_value, message
            FROM incidents
            WHERE database_config_id = ? AND opened_at < ? AND (resolved_at IS NULL OR resolved_at >= ?)
            ORDER BY opened_at, incident_id
            LIMIT %d
            """.formatted(MAX_INCIDENTS);

    private final JdbcTemplate jdbc;

    public DailyStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 구간에 수집 표본이 하나라도 있는지. 없으면 보고서를 만들지 않는다. */
    public boolean hasSamples(long databaseConfigId, Instant start, Instant end) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM metric_data
                               WHERE database_config_id = ? AND timestamp >= ? AND timestamp < ?)
                """, Boolean.class, databaseConfigId, start.atOffset(ZoneOffset.UTC), end.atOffset(ZoneOffset.UTC));
        return Boolean.TRUE.equals(exists);
    }

    @Transactional(readOnly = true)
    public DailyStats load(long databaseConfigId, Instant start, Instant end, boolean withDetails) {
        OffsetDateTime from = start.atOffset(ZoneOffset.UTC);
        OffsetDateTime to = end.atOffset(ZoneOffset.UTC);

        Map<String, Object> summary = jdbc.queryForMap(SUMMARY_SQL, databaseConfigId, from, to);
        long samples = longValue(summary.get("samples"));
        long success = longValue(summary.get("success"));
        long partial = longValue(summary.get("partial"));
        long failed = longValue(summary.get("failed"));

        Map<String, Object> storage = jdbc.queryForMap(STORAGE_SQL,
                databaseConfigId, from, to, databaseConfigId, from, to);

        Map<String, Long> errors = new LinkedHashMap<>();
        jdbc.query(ERROR_SQL, rs -> {
            errors.put(rs.getString("error_code"), rs.getLong("cnt"));
        }, databaseConfigId, from, to);

        Long incidentCount = jdbc.queryForObject(INCIDENT_COUNT_SQL, Long.class, databaseConfigId, to, from);
        List<IncidentSummary> incidents = withDetails
                ? jdbc.query(INCIDENT_SQL, DailyStatsRepository::incident, databaseConfigId, to, from)
                : List.of();
        List<HourlyStat> hourly = withDetails
                ? jdbc.query(HOURLY_SQL, DailyStatsRepository::hourly, from, databaseConfigId, from, to)
                : List.of();

        return new DailyStats(
                start, end, samples, success, partial, failed,
                samples == 0 ? null : round(100.0 * (success + partial) / samples),
                roundOrNull(summary.get("avg_conn")),
                longOrNull(summary.get("max_conn")),
                longOrNull(summary.get("max_conn_limit")),
                roundOrNull(summary.get("avg_usage")),
                roundOrNull(summary.get("max_usage")),
                roundOrNull(summary.get("avg_qps")),
                roundOrNull(summary.get("max_qps")),
                longOrNull(summary.get("slow_total")),
                roundOrNull(summary.get("max_slow_ps")),
                roundOrNull(summary.get("avg_running")),
                longOrNull(summary.get("max_running")),
                roundOrNull(summary.get("avg_rt")),
                roundOrNull(summary.get("p95_rt")),
                longOrNull(storage.get("first_storage")),
                longOrNull(storage.get("last_storage")),
                errors,
                incidentCount == null ? 0 : incidentCount,
                incidents,
                hourly);
    }

    private static HourlyStat hourly(ResultSet rs, int row) throws SQLException {
        return new HourlyStat(
                rs.getTimestamp("hour_start").toInstant(),
                rs.getLong("samples"),
                rs.getLong("failed"),
                roundOrNull(rs.getObject("avg_usage")),
                roundOrNull(rs.getObject("avg_qps")),
                longOrNull(rs.getObject("slow_total")),
                roundOrNull(rs.getObject("avg_rt")));
    }

    private static IncidentSummary incident(ResultSet rs, int row) throws SQLException {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new IncidentSummary(
                rs.getString("incident_id"),
                rs.getString("rule_id"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getTimestamp("opened_at").toInstant(),
                resolved == null ? null : resolved.toInstant(),
                rs.getString("metric_name"),
                roundOrNull(rs.getObject("metric_value")),
                roundOrNull(rs.getObject("threshold_value")),
                rs.getString("message"));
    }

    private static long longValue(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static Long longOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Double roundOrNull(Object value) {
        if (value == null) return null;
        double number = value instanceof BigDecimal decimal ? decimal.doubleValue() : ((Number) value).doubleValue();
        return round(number);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
