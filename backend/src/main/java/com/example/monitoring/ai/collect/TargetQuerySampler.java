package com.example.monitoring.ai.collect;

import com.example.monitoring.ai.model.QuerySample;
import com.example.monitoring.ai.model.QuerySampleSource;
import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.security.TargetConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 대상 MariaDB에서 무거운 쿼리 표본을 읽는다. performance_schema가 켜져 있고 읽을 수 있으면 누적 digest 통계를,
 * 아니면 지금 실행 중인 PROCESSLIST 문장을 쓴다. 어느 쪽이든 문장은 {@link SqlLiteralRedactor}를 거친다.
 * 읽기 전용 조회만 실행하며 대상 DB 설정·통계를 바꾸지 않는다.
 */
@Slf4j
@Component
public class TargetQuerySampler {

    static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final double PICOS_PER_MILLI = 1_000_000_000.0;

    private static final String PS_ENABLED_SQL = "SELECT @@GLOBAL.performance_schema";

    private static final String DIGEST_SQL = """
            SELECT SCHEMA_NAME, DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT, AVG_TIMER_WAIT, MAX_TIMER_WAIT,
                   SUM_ROWS_EXAMINED, SUM_ROWS_SENT, SUM_ROWS_AFFECTED, SUM_NO_INDEX_USED,
                   SUM_NO_GOOD_INDEX_USED, SUM_CREATED_TMP_DISK_TABLES, SUM_SORT_MERGE_PASSES
            FROM performance_schema.events_statements_summary_by_digest
            WHERE DIGEST_TEXT IS NOT NULL
              AND (SCHEMA_NAME IS NULL OR SCHEMA_NAME NOT IN ('performance_schema', 'information_schema', 'mysql', 'sys'))
              AND DIGEST_TEXT NOT LIKE 'SHOW %'
              AND DIGEST_TEXT NOT LIKE 'SELECT @@%'
              AND DIGEST_TEXT NOT LIKE 'SET %'
              AND DIGEST_TEXT NOT LIKE 'COMMIT%'
              AND DIGEST_TEXT NOT LIKE 'ROLLBACK%'
            ORDER BY SUM_TIMER_WAIT DESC
            LIMIT ?
            """;

    private static final String PROCESSLIST_SQL = """
            SELECT DB, TIME, INFO
            FROM information_schema.PROCESSLIST
            WHERE COMMAND NOT IN ('Sleep', 'Daemon', 'Binlog Dump')
              AND INFO IS NOT NULL
              AND ID <> CONNECTION_ID()
            ORDER BY TIME DESC
            LIMIT ?
            """;

    private final TargetConnectionFactory connectionFactory;

    public TargetQuerySampler(TargetConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public Samples sample(CollectorTarget target, int limit) throws SQLException {
        try (Connection connection = connectionFactory.open(target)) {
            connection.setReadOnly(true);
            if (performanceSchemaEnabled(connection, target.id())) {
                try {
                    return new Samples(QuerySampleSource.PERFORMANCE_SCHEMA, digests(connection, limit));
                } catch (SQLException e) {
                    log.info("performance_schema digest read failed; falling back to processlist. "
                            + "databaseConfigId={}, sqlState={}", target.id(), e.getSQLState());
                }
            }
            return new Samples(QuerySampleSource.PROCESSLIST, processlist(connection, limit));
        }
    }

    private boolean performanceSchemaEnabled(Connection connection, long targetId) {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery(PS_ENABLED_SQL)) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            log.debug("performance_schema check failed. databaseConfigId={}, sqlState={}", targetId, e.getSQLState());
            return false;
        }
    }

    private List<QuerySample> digests(Connection connection, int limit) throws SQLException {
        List<QuerySample> samples = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(DIGEST_SQL)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    samples.add(new QuerySample(
                            "Q" + (samples.size() + 1),
                            rs.getString("SCHEMA_NAME"),
                            SqlLiteralRedactor.redact(rs.getString("DIGEST_TEXT")),
                            longOrNull(rs, "COUNT_STAR"),
                            millis(rs, "SUM_TIMER_WAIT"),
                            millis(rs, "AVG_TIMER_WAIT"),
                            millis(rs, "MAX_TIMER_WAIT"),
                            longOrNull(rs, "SUM_ROWS_EXAMINED"),
                            longOrNull(rs, "SUM_ROWS_SENT"),
                            longOrNull(rs, "SUM_ROWS_AFFECTED"),
                            longOrNull(rs, "SUM_NO_INDEX_USED"),
                            longOrNull(rs, "SUM_NO_GOOD_INDEX_USED"),
                            longOrNull(rs, "SUM_CREATED_TMP_DISK_TABLES"),
                            longOrNull(rs, "SUM_SORT_MERGE_PASSES"),
                            null));
                }
            }
        }
        return samples;
    }

    private List<QuerySample> processlist(Connection connection, int limit) throws SQLException {
        List<QuerySample> samples = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(PROCESSLIST_SQL)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    samples.add(new QuerySample(
                            "Q" + (samples.size() + 1),
                            rs.getString("DB"),
                            SqlLiteralRedactor.redact(rs.getString("INFO")),
                            null, null, null, null, null, null, null, null, null, null, null,
                            longOrNull(rs, "TIME")));
                }
            }
        }
        return samples;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** performance_schema 타이머(피코초)를 ms로 바꾼다. 소수 둘째 자리까지. */
    private static Double millis(ResultSet rs, String column) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        if (value == null) return null;
        return Math.round(value.doubleValue() / PICOS_PER_MILLI * 100.0) / 100.0;
    }

    public record Samples(QuerySampleSource source, List<QuerySample> samples) {
    }
}
