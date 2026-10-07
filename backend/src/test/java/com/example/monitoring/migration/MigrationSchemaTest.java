package com.example.monitoring.migration;

import com.example.monitoring.support.EmbeddedPostgresSupport;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 모든 Flyway migration을 적용한 스키마가 현재 엔티티 전체의 ddl-auto=validate를 통과하는지 검증한다.
 * 파트별 migration(V2 B, V3 A, V4 C ...)이 추가되어도 같은 테스트로 스키마·엔티티 불일치를 잡는다.
 */
class MigrationSchemaTest {

    private static final String ENTITY_PACKAGE = "com.example.monitoring";

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() {
        postgres = EmbeddedPostgresSupport.postgres();
    }

    @Test
    @DisplayName("All entities pass ddl-auto=validate on a fresh database migrated to the latest version")
    void entitiesValidateAgainstLatestMigration() throws Exception {
        DataSource dataSource = createDatabase("fresh_latest");

        Flyway flyway = flyway(dataSource);
        flyway.migrate();

        assertThat(flyway.info().pending()).isEmpty();
        assertThatCode(() -> validateEntities(dataSource)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Baseline V1 data survives migration to the latest version and entities still validate")
    void legacyV1DataMigratesToLatest() throws Exception {
        DataSource dataSource = createDatabase("legacy_v1");
        Flyway.configure().dataSource(dataSource).target("1").load().migrate();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO database_configs
                        (collection_interval_seconds, created_at, enabled, host, name, password, port, status, username)
                    VALUES (5, now(), true, '127.0.0.1', 'legacy', 'secret', 13306, 'UP', 'monitor')
                    """);
            statement.execute("""
                    INSERT INTO metric_data (collection_status, created_at, database_config_id, timestamp)
                    VALUES ('SUCCESS', '2026-09-28 12:00:00', 1, '2026-09-28 12:00:00')
                    """);
        }

        flyway(dataSource).migrate();

        assertThatCode(() -> validateEntities(dataSource)).doesNotThrowAnyException();
        // V3: LEGACY_TIME_ZONE(Asia/Seoul) 기준 로컬 시각을 UTC로 변환하고 v1 필드를 채운다.
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT to_char(timestamp AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS utc_time,
                            config_version, collection_attempt_time = timestamp AS attempt_matches,
                            last_success_at = timestamp AS success_matches, unavailable_metrics::text AS unavailable
                     FROM metric_data
                     """)) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("utc_time")).isEqualTo("2026-09-28 03:00:00");
            assertThat(row.getLong("config_version")).isEqualTo(1L);
            assertThat(row.getBoolean("attempt_matches")).isTrue();
            assertThat(row.getBoolean("success_matches")).isTrue();
            assertThat(row.getString("unavailable")).isEqualTo("{}");
        }
    }

    @Test
    @DisplayName("V7 converts legacy database_configs/blocked_reasons local times with LEGACY_TIME_ZONE and leaves no naive timestamp")
    void v7ConvertsLegacyLocalTimestamps() throws Exception {
        DataSource dataSource = createDatabase("legacy_v7");
        Flyway.configure().dataSource(dataSource).target("1").load().migrate();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO database_configs
                        (collection_interval_seconds, created_at, updated_at, last_checked_at, enabled, host, name,
                         password, port, status, username)
                    VALUES (5, '2026-09-28 12:00:00', '2026-09-28 12:30:00', '2026-09-28 13:00:00', true,
                            '127.0.0.1', 'legacy', 'secret', 13306, 'UP', 'monitor')
                    """);
            statement.execute("""
                    INSERT INTO blocked_reasons (block_type, blocked_at, blocked_by, database_config_id, reason, severity)
                    VALUES ('MANUAL', '2026-09-28 09:00:00', 'admin', 1, 'legacy block', 'WARNING')
                    """);
        }

        flyway(dataSource).migrate();

        assertThatCode(() -> validateEntities(dataSource)).doesNotThrowAnyException();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // LEGACY_TIME_ZONE=Asia/Seoul(+09:00) 로컬 시각을 같은 순간의 UTC로 바꾼다.
            try (ResultSet row = statement.executeQuery("""
                    SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_utc,
                           to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS updated_utc,
                           to_char(last_checked_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS checked_utc
                    FROM database_configs
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("created_utc")).isEqualTo("2026-09-28 03:00:00");
                assertThat(row.getString("updated_utc")).isEqualTo("2026-09-28 03:30:00");
                assertThat(row.getString("checked_utc")).isEqualTo("2026-09-28 04:00:00");
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT to_char(blocked_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') FROM blocked_reasons")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("2026-09-28 00:00:00");
            }
            try (ResultSet row = statement.executeQuery("""
                    SELECT string_agg(table_name || '.' || column_name, ',') FROM information_schema.columns
                    WHERE table_schema = 'public' AND data_type = 'timestamp without time zone'
                      AND table_name <> 'flyway_schema_history'
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).as("naive timestamp columns left after V7").isNull();
            }
        }
    }

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).load();
    }

    private static DataSource createDatabase(String name) throws Exception {
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return postgres.getDatabase("postgres", name);
    }

    private static void validateEntities(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(ENTITY_PACKAGE);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy.class.getName(),
                "hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy.class.getName()));
        factory.afterPropertiesSet();
        factory.getObject().close();
    }
}
