package com.example.monitoring.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManagerFactory;
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
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * V1은 기존 엔티티에 ddl-auto를 적용해 만들어지던 스키마와 같아야 기존 DB를 baseline 1로 등록할 수 있다.
 * 새 DB에서는 V1 적용 후 ddl-auto=validate가 통과해야 한다.
 */
class MigrationV1SchemaTest {

    private static final String DOMAIN_PACKAGE = "com.example.monitoring.domain";

    private static final String COLUMNS_SQL = """
            SELECT table_name, column_name, data_type, character_maximum_length,
                   numeric_precision, datetime_precision, is_nullable, is_identity, identity_generation
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
            ORDER BY table_name, column_name
            """;

    private static final String CONSTRAINTS_SQL = """
            SELECT t.relname AS table_name, c.conname, c.contype, pg_get_constraintdef(c.oid) AS definition
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = 'public' AND t.relname <> 'flyway_schema_history'
            ORDER BY table_name, c.conname
            """;

    private static final String INDEXES_SQL = """
            SELECT tablename, indexname, indexdef
            FROM pg_indexes
            WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
            ORDER BY tablename, indexname
            """;

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    @DisplayName("V1 schema is identical to the schema Hibernate generated from the baseline entities")
    void v1MatchesHibernateGeneratedSchema() throws Exception {
        DataSource migrated = createDatabase("v1_migrated");
        DataSource generated = createDatabase("hibernate_generated");

        migrate(migrated);
        entityManagerFactory(generated, "create").close();

        assertThat(snapshot(migrated, COLUMNS_SQL)).isEqualTo(snapshot(generated, COLUMNS_SQL));
        assertThat(snapshot(migrated, CONSTRAINTS_SQL)).isEqualTo(snapshot(generated, CONSTRAINTS_SQL));
        assertThat(snapshot(migrated, INDEXES_SQL)).isEqualTo(snapshot(generated, INDEXES_SQL));
    }

    @Test
    @DisplayName("Entities pass ddl-auto=validate on a fresh database migrated to V1")
    void entitiesValidateAgainstV1() throws Exception {
        DataSource migrated = createDatabase("v1_validate");

        migrate(migrated);

        assertThatCode(() -> entityManagerFactory(migrated, "validate").close()).doesNotThrowAnyException();
    }

    private static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .target("1")
                .load()
                .migrate();
    }

    private static DataSource createDatabase(String name) throws Exception {
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return postgres.getDatabase("postgres", name);
    }

    private static EntityManagerFactory entityManagerFactory(DataSource dataSource, String ddlAuto) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(DOMAIN_PACKAGE);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", ddlAuto,
                "hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy.class.getName(),
                "hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy.class.getName()));
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    private static List<String> snapshot(DataSource dataSource, String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            ResultSetMetaData meta = resultSet.getMetaData();
            while (resultSet.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.append(meta.getColumnLabel(i)).append('=').append(resultSet.getString(i)).append(' ');
                }
                rows.add(row.toString().trim());
            }
        }
        return rows;
    }
}
