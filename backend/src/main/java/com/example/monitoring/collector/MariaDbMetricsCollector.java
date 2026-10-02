package com.example.monitoring.collector;

import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.security.TargetConnectionFactory;
import com.example.monitoring.database.security.TargetDatabaseErrorClassifier;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.MetricErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * MariaDB 원본 지표 수집기. 한 번의 수집은 연결을 포함해 전체 15초 안에 끝나도록
 * 남은 시간으로 각 쿼리 timeout을 설정하고, 시간이 없으면 남은 조회를 실패로 처리한다.
 */
@Slf4j
@Component
public class MariaDbMetricsCollector implements DbMetricsCollector {

    static final Duration COLLECTION_LIMIT = Duration.ofSeconds(15);

    private static final String STATUS_SQL = "SHOW GLOBAL STATUS WHERE Variable_name IN "
            + "('Threads_connected', 'Threads_running', 'Queries', 'Slow_queries', 'Uptime')";
    private static final String VARIABLES_SQL = "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('max_connections')";
    private static final String STORAGE_SQL =
            "SELECT COALESCE(SUM(data_length + index_length), 0) FROM information_schema.TABLES";

    private final TargetConnectionFactory connectionFactory;
    private final MetricSnapshotCalculator calculator;

    @Autowired
    public MariaDbMetricsCollector(TargetConnectionFactory connectionFactory) {
        this(connectionFactory, new MetricSnapshotCalculator());
    }

    MariaDbMetricsCollector(TargetConnectionFactory connectionFactory, MetricSnapshotCalculator calculator) {
        this.connectionFactory = connectionFactory;
        this.calculator = calculator;
    }

    @Override
    public MetricData collectMetrics(CollectorTarget target) {
        Instant startedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + COLLECTION_LIMIT.toNanos();

        Connection connection;
        try {
            connection = connectionFactory.open(target);
        } catch (SQLException e) {
            TargetDatabaseErrorClassifier.SafeError error = TargetDatabaseErrorClassifier.classify(e, false);
            log.warn("Metric collection connect failed. databaseConfigId={}, errorCode={}, sqlState={}",
                    target.id(), error.code(), e.getSQLState());
            return calculator.connectionFailed(target.id(), startedAt, elapsedMs(startNanos),
                    MetricErrorCode.valueOf(error.code()), error.message());
        } catch (RuntimeException e) {
            log.warn("Metric collection connect rejected. databaseConfigId={}, exceptionType={}",
                    target.id(), e.getClass().getSimpleName());
            return calculator.connectionFailed(target.id(), startedAt, elapsedMs(startNanos),
                    MetricErrorCode.UNKNOWN, "대상 DB에 연결하지 못했습니다.");
        }
        long responseTimeMs = elapsedMs(startNanos);

        try (connection) {
            Map<String, String> status = queryNameValue(connection, STATUS_SQL, deadlineNanos, target.id());
            Map<String, String> variables = queryNameValue(connection, VARIABLES_SQL, deadlineNanos, target.id());
            Long storageBytes = queryStorageBytes(connection, deadlineNanos, target.id());
            return calculator.connected(target.id(), target.configVersion(), startedAt, responseTimeMs,
                    status, variables, storageBytes, System.nanoTime());
        } catch (SQLException | RuntimeException e) {
            log.error("Unexpected metric collection failure. databaseConfigId={}, exceptionType={}",
                    target.id(), e.getClass().getSimpleName());
            return calculator.internalError(target.id(), startedAt, responseTimeMs, true);
        }
    }

    @Override
    public MetricData credentialsUnavailable(long databaseConfigId) {
        return calculator.credentialsUnavailable(databaseConfigId, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /** 필수 원본 조회. 실패하면 빈 map을 반환해 해당 지표를 QUERY_FAILED로 만든다. */
    private Map<String, String> queryNameValue(Connection connection, String sql, long deadlineNanos, long targetId) {
        Map<String, String> values = new HashMap<>();
        Integer timeout = remainingSeconds(deadlineNanos);
        if (timeout == null) {
            return values;
        }
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    values.put(rs.getString("Variable_name"), rs.getString("Value"));
                }
            }
        } catch (SQLException e) {
            log.warn("Required metric query failed. databaseConfigId={}, sqlState={}", targetId, e.getSQLState());
            values.clear();
        }
        return values;
    }

    /** 선택 지표. 실패해도 SUCCESS를 바꾸지 않는다. */
    private Long queryStorageBytes(Connection connection, long deadlineNanos, long targetId) {
        Integer timeout = remainingSeconds(deadlineNanos);
        if (timeout == null) {
            return null;
        }
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rs = statement.executeQuery(STORAGE_SQL)) {
                if (rs.next()) {
                    long value = rs.getLong(1);
                    return rs.wasNull() || value < 0 ? null : value;
                }
            }
        } catch (SQLException e) {
            log.debug("Storage bytes query failed. databaseConfigId={}, sqlState={}", targetId, e.getSQLState());
        }
        return null;
    }

    /** 남은 시간(초, 올림). 이미 제한을 넘었으면 null. */
    private static Integer remainingSeconds(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return null;
        }
        return (int) Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
