package com.example.monitoring.collector;

import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.security.TargetConnectionFactory;
import com.example.monitoring.database.security.TargetDatabaseErrorClassifier;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.dto.DbPingResponseDto;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class MariaDbHealthChecker {
    private final TargetConnectionFactory connectionFactory;

    public DbPingResponseDto pingAndFetchVersion(CollectorTarget config) {
        long startTime = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        boolean connected = false;

        try (Connection conn = connectionFactory.open(config)) {
            connected = true;
            // Lightweight ping query: SELECT 1
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(5);
                try (ResultSet rs = stmt.executeQuery("SELECT 1")) {
                if (!rs.next()) {
                    throw new SQLException("Ping query SELECT 1 returned no results.");
                }
                }
            }

            // Version check query: SELECT VERSION()
            String version = null;
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(5);
                try (ResultSet rs = stmt.executeQuery("SELECT VERSION()")) {
                    if (rs.next()) {
                        version = rs.getString(1);
                    }
                }
            }

            long latencyMs = System.currentTimeMillis() - startTime;

            return DbPingResponseDto.builder()
                    .databaseConfigId(config.id())
                    .status(TargetDbStatus.UP)
                    .version(version)
                    .responseTimeMs(latencyMs)
                    .timestamp(now)
                    .errorCode(null)
                    .errorMessage(null)
                    .build();

        } catch (SQLException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            TargetDatabaseErrorClassifier.SafeError safe = TargetDatabaseErrorClassifier.classify(e, connected);
            log.warn("Health check failed. databaseConfigId={}, errorCode={}, sqlState={}",
                    config.id(), safe.code(), e.getSQLState());

            return DbPingResponseDto.builder()
                    .databaseConfigId(config.id())
                    .status(TargetDbStatus.DOWN)
                    .version(null)
                    .responseTimeMs(durationMs)
                    .timestamp(now)
                    .errorCode(safe.code())
                    .errorMessage(safe.message())
                    .build();
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Unexpected database health check failure. databaseConfigId={}, exceptionType={}",
                    config.id(), e.getClass().getSimpleName());

            return DbPingResponseDto.builder()
                    .databaseConfigId(config.id())
                    .status(TargetDbStatus.DOWN)
                    .version(null)
                    .responseTimeMs(durationMs)
                    .timestamp(now)
                    .errorCode("INTERNAL_ERROR")
                    .errorMessage("대상 DB 진단 중 내부 오류가 발생했습니다.")
                    .build();
        }
    }
}
