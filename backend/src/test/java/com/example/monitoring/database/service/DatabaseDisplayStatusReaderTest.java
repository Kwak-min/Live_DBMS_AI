package com.example.monitoring.database.service;

import com.example.monitoring.database.service.DatabaseDisplayStatusReader.DisplayStatus;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 목록의 연결 상태를 위험도 기능 켜짐/꺼짐에 따라 올바른 출처에서 읽는지 실제 스키마로 확인한다. */
class DatabaseDisplayStatusReaderTest {

    private static final Instant ATTEMPT = Instant.parse("2026-10-07T03:00:05Z");
    private static final Instant SUCCESS = Instant.parse("2026-10-07T03:00:00Z");
    private static final Instant LEGACY_CHECKED = Instant.parse("2026-10-06T00:00:00Z");

    private static DataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        EmbeddedPostgres postgres = EmbeddedPostgresSupport.postgres();
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE display_status");
        }
        dataSource = postgres.getDatabase("postgres", "display_status");
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO database_configs (id, collection_interval_seconds, created_at, updated_at, enabled, host,
                    name, port, status, last_checked_at, config_version)
                VALUES (1, 5, now(), now(), true, '127.0.0.1', 'with-state', 3306, 'UP', ?, 1),
                       (2, 5, now(), now(), true, '127.0.0.1', 'without-state', 3306, 'UP', ?, 1)
                """, java.sql.Timestamp.from(LEGACY_CHECKED), java.sql.Timestamp.from(LEGACY_CHECKED));
        jdbc.update("""
                INSERT INTO monitoring_states (database_config_id, config_version, state_version, enabled,
                    connection_status, data_freshness, activation_at, last_attempt_at, last_success_at)
                VALUES (1, 1, 3, true, 'DOWN', 'FRESH', ?, ?, ?)
                """, java.sql.Timestamp.from(SUCCESS.minusSeconds(60)), java.sql.Timestamp.from(ATTEMPT),
                java.sql.Timestamp.from(SUCCESS));
    }

    @Test
    void riskEnabledReadsMonitoringStatesAndDefaultsMissingRowsToUnknown() {
        DatabaseDisplayStatusReader reader = new DatabaseDisplayStatusReader(
                new NamedParameterJdbcTemplate(dataSource), true);

        Map<Long, DisplayStatus> statuses = reader.readAll(List.of(config(1L), config(2L)));

        assertThat(statuses.get(1L)).isEqualTo(new DisplayStatus(TargetDbStatus.DOWN, ATTEMPT, SUCCESS));
        assertThat(statuses.get(2L)).isEqualTo(new DisplayStatus(TargetDbStatus.UNKNOWN, null, null));
        assertThat(reader.readAll(List.of())).isEmpty();
    }

    @Test
    void riskDisabledKeepsDatabaseConfigDisplayColumns() {
        DatabaseDisplayStatusReader reader = new DatabaseDisplayStatusReader(
                new NamedParameterJdbcTemplate(dataSource), false);

        assertThat(reader.read(config(1L)))
                .isEqualTo(new DisplayStatus(TargetDbStatus.UP, LEGACY_CHECKED, null));
    }

    private static DatabaseConfig config(long id) {
        return DatabaseConfig.builder().id(id).name("db" + id).host("127.0.0.1").port(3306).enabled(true)
                .configVersion(1L).status(TargetDbStatus.UP).lastCheckedAt(LEGACY_CHECKED).build();
    }
}
