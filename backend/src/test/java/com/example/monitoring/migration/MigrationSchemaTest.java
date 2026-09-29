package com.example.monitoring.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 모든 Flyway migration을 적용한 스키마가 현재 엔티티 전체의 ddl-auto=validate를 통과하는지 검증한다.
 * 파트별 migration(V2 B, V3 A, V4 C ...)이 추가되어도 같은 테스트로 스키마·엔티티 불일치를 잡는다.
 */
class MigrationSchemaTest {

    private static final String ENTITY_PACKAGE = "com.example.monitoring";

    /** V2(B)가 요구하는 migration 입력. 테스트 전용 값이며 실제 키가 아니다. */
    private static final Map<String, String> MIGRATION_PROPERTIES = Map.of(
            "DB_CONFIG_ACTIVE_KEY_VERSION", "1",
            "DB_CONFIG_ENCRYPTION_KEYS", "{\"1\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\"}",
            "LEGACY_TIME_ZONE", "Asia/Seoul");

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        MIGRATION_PROPERTIES.forEach(System::setProperty);
        postgres = EmbeddedPostgres.start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        MIGRATION_PROPERTIES.keySet().forEach(System::clearProperty);
        if (postgres != null) {
            postgres.close();
        }
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
                    VALUES ('SUCCESS', now(), 1, now())
                    """);
        }

        flyway(dataSource).migrate();

        assertThatCode(() -> validateEntities(dataSource)).doesNotThrowAnyException();
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
