package com.example.monitoring.lifecycle.schema;

import com.example.monitoring.lifecycle.adapter.LifecyclePolicyContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class PartCMigrationSchemaTest {

    private static final Path REPOSITORY = locateRepository();
    private static final Path SCHEMA_ROOT = REPOSITORY.resolve("backend/schema/part-c");
    private static final String DB_KEYS = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String PREVIOUS_ACTIVE_KEY_VERSION = System.getProperty("DB_CONFIG_ACTIVE_KEY_VERSION");
    private static final String PREVIOUS_ENCRYPTION_KEYS = System.getProperty("DB_CONFIG_ENCRYPTION_KEYS");
    private static final String PREVIOUS_LEGACY_TIME_ZONE = System.getProperty("LEGACY_TIME_ZONE");
    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startPostgres() throws Exception {
        System.setProperty("DB_CONFIG_ACTIVE_KEY_VERSION", "1");
        System.setProperty("DB_CONFIG_ENCRYPTION_KEYS", "{\"1\":\"" + DB_KEYS + "\"}");
        System.setProperty("LEGACY_TIME_ZONE", "UTC");
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
        restoreProperty("DB_CONFIG_ACTIVE_KEY_VERSION", PREVIOUS_ACTIVE_KEY_VERSION);
        restoreProperty("DB_CONFIG_ENCRYPTION_KEYS", PREVIOUS_ENCRYPTION_KEYS);
        restoreProperty("LEGACY_TIME_ZONE", PREVIOUS_LEGACY_TIME_ZONE);
    }

    @Test
    void freshV4HasRequiredKeysAndPreservesConstraintProbes() throws Exception {
        ActualDatabase database = prepareActualDatabase();
        try {
            seedActualPrerequisites(database.dataSource());
            assertActualAContract(database.dataSource());
            assertRequiredParentKeys(database.dataSource());
            assertMonitoringStateActivationColumn(database.dataSource(), "public");
            assertPartCTableInventory(database.dataSource(), "public");
            assertOutboxShape(database.dataSource(), "public");
            ProbeResult result = runConstraintProbe(database.dataSource(), "public");

            assertThat(result.passedScenarios()).isEqualTo(22);
            assertThat(result.scenarios()).contains(
                    "activation_at_nullable_timestamptz",
                    "event_outbox_actual_eleven_columns",
                    "cross_target_metric_rejected",
                    "subscription_session_owner_enforced",
                    "disabled_activation_coherent",
                    "deleted_state_activation_coherent",
                    "enabled_activation_required",
                    "disabled_activation_must_be_null");
        } finally {
            dropActualDatabase(database);
        }
    }

    @Test
    void activeMigrationInventoryIsExactlyV1ThroughV4() throws IOException {
        List<String> names = new ArrayList<>();
        collectMigrationNames(names, REPOSITORY.resolve("backend/src/main/resources/db/migration"));
        collectMigrationNames(names, REPOSITORY.resolve("backend/src/main/java/db/migration"));
        names.sort(String::compareTo);

        assertThat(names).containsExactly(
                "V1__baseline_existing_schema.sql",
                "V2__part_b_auth_and_encrypt_database_credentials.java",
                "V3__part_a_metrics_and_outbox.java",
                "V4__part_c_monitoring.sql");
        assertThat(Files.exists(SCHEMA_ROOT.resolve("V4__part_c_monitoring.sql"))).isFalse();
    }

    @Test
    void activeV4BackfillsRetainedTargetsAtCurrentVersions() throws Exception {
        ActualDatabase database = prepareActualDatabaseAt("3");
        try {
            seedRetainedTargets(database.dataSource());
            long metricCount = countRows(database.dataSource(), "metric_data");
            long outboxCount = countRows(database.dataSource(), "event_outbox");

            var migration = Flyway.configure().dataSource(database.dataSource()).load().migrate();

            assertThat(migration.migrationsExecuted).isEqualTo(1);
            assertThat(Flyway.configure().dataSource(database.dataSource()).load().info()
                    .current().getVersion().getVersion()).isEqualTo("4");
            List<String> states = withConnection(database.dataSource(), connection -> {
                List<String> result = new ArrayList<>();
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("""
                             SELECT database_config_id, config_version, state_version, enabled, deleted,
                                    connection_status, data_freshness, activation_at IS NOT NULL AS active,
                                    risk_level IS NULL AND last_attempt_at IS NULL AND last_success_at IS NULL
                                        AND latest_metric_id IS NULL AS observation_empty
                             FROM monitoring_states
                             ORDER BY database_config_id
                             """)) {
                    while (rows.next()) {
                        result.add("%d:%d:%d:%s:%s:%s:%s:%s:%s".formatted(
                                rows.getLong("database_config_id"), rows.getLong("config_version"),
                                rows.getLong("state_version"), rows.getBoolean("enabled"),
                                rows.getBoolean("deleted"), rows.getString("connection_status"),
                                rows.getString("data_freshness"), rows.getBoolean("active"),
                                rows.getBoolean("observation_empty")));
                    }
                }
                return result;
            });

            assertThat(states).containsExactly(
                    "1001:1:1:true:false:UNKNOWN:NO_DATA:true:true",
                    "1002:7:1:false:false:UNKNOWN:PAUSED:false:true",
                    "1003:9:1:true:false:UNKNOWN:NO_DATA:true:true",
                    "1004:11:1:false:true:UNKNOWN:PAUSED:false:true");
            assertThat(singleLong(database.dataSource(), """
                    SELECT count(DISTINCT observed_at)
                    FROM (
                        SELECT updated_at AS observed_at FROM monitoring_states
                        UNION ALL
                        SELECT activation_at FROM monitoring_states WHERE activation_at IS NOT NULL
                        UNION ALL
                        SELECT created_at FROM risk_policies
                        UNION ALL
                        SELECT updated_at FROM risk_policies
                    ) migration_times
                    """)).isEqualTo(1L);
            assertThat(singleLong(database.dataSource(), """
                    SELECT count(*) FROM monitoring_states
                    WHERE EXTRACT(MICROSECONDS FROM updated_at)::BIGINT % 1000 <> 0
                       OR (activation_at IS NOT NULL
                           AND EXTRACT(MICROSECONDS FROM activation_at)::BIGINT % 1000 <> 0)
                    """)).isZero();
            assertThat(singleLong(database.dataSource(), """
                    SELECT count(*) FROM risk_policies
                    WHERE version = 1 AND stale_after_seconds = 30
                      AND notification_cooldown_seconds = 300
                    """)).isEqualTo(4L);
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode expectedPolicy = LifecyclePolicyContract.defaultPolicy(objectMapper);
            List<JsonNode> policies = withConnection(database.dataSource(), connection -> {
                List<JsonNode> result = new ArrayList<>();
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                             "SELECT rules::text FROM risk_policies ORDER BY database_config_id")) {
                    while (rows.next()) {
                        result.add(objectMapper.readTree(rows.getString(1)));
                    }
                }
                return result;
            });
            assertThat(policies).hasSize(4).allMatch(expectedPolicy::equals);
            assertThat(countRows(database.dataSource(), "metric_data")).isEqualTo(metricCount);
            assertThat(countRows(database.dataSource(), "event_outbox")).isEqualTo(outboxCount);
            assertThat(Flyway.configure().dataSource(database.dataSource()).load()
                    .migrate().migrationsExecuted).isZero();
        } finally {
            dropActualDatabase(database);
        }
    }

    @Test
    void invalidRetainedTargetRollsBackCompositeKeysTablesAndBackfill() throws Exception {
        assertInvalidRetainedTargetRollsBack(2001L, 0L, false, false,
                "id=2001, config_version=0, enabled=f, deleted=f");
        assertInvalidRetainedTargetRollsBack(2002L, 2L, true, true,
                "id=2002, config_version=2, enabled=t, deleted=t");
        assertInvalidRetainedTargetRollsBack(9_007_199_254_740_992L, 3L, false, false,
                "id=9007199254740992, config_version=3, enabled=f, deleted=f");
    }

    private ActualDatabase prepareActualDatabase() throws Exception {
        String name = "part_c_actual_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + quoteIdentifier(name));
        }

        DataSource actual = postgres.getDatabase("postgres", name);
        Flyway.configure().dataSource(actual).load().migrate();
        return new ActualDatabase(name, actual);
    }

    private ActualDatabase prepareActualDatabaseAt(String target) throws Exception {
        String name = "part_c_retained_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + quoteIdentifier(name));
        }

        DataSource actual = postgres.getDatabase("postgres", name);
        Flyway.configure().dataSource(actual).target(target).load().migrate();
        return new ActualDatabase(name, actual);
    }

    private void seedRetainedTargets(DataSource source) throws Exception {
        withConnection(source, connection -> {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO database_configs (
                            id, collection_interval_seconds, created_at, enabled, host,
                            name, port, status, updated_at, config_version, deleted_at
                        ) VALUES
                            (1001, 60, '2026-09-29T00:00:00Z', TRUE, '127.0.0.1',
                             'Enabled v1', 5432, 'UP', '2026-09-29T00:00:00Z', 1, NULL),
                            (1002, 60, '2026-09-29T00:00:00Z', FALSE, '127.0.0.1',
                             'Paused v7', 5433, 'UNKNOWN', '2026-09-29T00:00:00Z', 7, NULL),
                            (1003, 60, '2026-09-29T00:00:00Z', TRUE, '127.0.0.1',
                             'Enabled v9', 5434, 'DOWN', '2026-09-29T00:00:00Z', 9, NULL),
                            (1004, 60, '2026-09-29T00:00:00Z', FALSE, '127.0.0.1',
                             'Deleted v11', 5435, 'UNKNOWN', '2026-09-29T00:00:00Z', 11,
                             '2026-09-29T01:00:00Z')
                        """);
                statement.execute("""
                        INSERT INTO metric_data (
                            id, collection_status, created_at, database_config_id, timestamp,
                            config_version, collection_attempt_time, last_success_at,
                            unavailable_metrics
                        ) VALUES (
                            9001, 'SUCCESS', '2026-09-29T00:00:00Z', 1003,
                            '2026-09-29T00:00:00Z', 8, '2026-09-29T00:00:00Z',
                            '2026-09-29T00:00:00Z', '{}'::jsonb
                        )
                        """);
            }
            connection.commit();
            return null;
        });
    }

    private long countRows(DataSource source, String table) throws Exception {
        return singleLong(source, "SELECT count(*) FROM " + table);
    }

    private long singleLong(DataSource source, String sql) throws Exception {
        return withConnection(source, connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        });
    }

    private void assertInvalidRetainedTargetRollsBack(
            long id,
            long version,
            boolean enabled,
            boolean deleted,
            String expectedDiagnostic
    ) throws Exception {
        ActualDatabase database = prepareActualDatabaseAt("3");
        try {
            withConnection(database.dataSource(), connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            INSERT INTO database_configs (
                                id, collection_interval_seconds, created_at, enabled, host,
                                name, port, status, updated_at, config_version, deleted_at
                            ) VALUES (
                                %d, 60, '2026-09-29T00:00:00Z', %s, '127.0.0.1',
                                'Invalid retained target', 5432, 'UNKNOWN',
                                '2026-09-29T00:00:00Z', %d, %s
                            )
                            """.formatted(id, enabled, version,
                            deleted ? "'2026-09-29T01:00:00Z'" : "NULL"));
                }
                return null;
            });
            Flyway flyway = Flyway.configure().dataSource(database.dataSource()).load();

            Throwable failure = catchThrowable(flyway::migrate);

            assertThat(failure).isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining(expectedDiagnostic);
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
            assertThat(flyway.info().pending()).singleElement()
                    .satisfies(info -> assertThat(info.getVersion().getVersion()).isEqualTo("4"));
            assertThat(singleLong(database.dataSource(), """
                    SELECT count(*) FROM pg_constraint
                    WHERE conname IN (
                        'auth_sessions_sid_user_unique',
                        'metric_data_id_target_unique'
                    )
                    """)).isZero();
            assertThat(singleLong(database.dataSource(), """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name IN (
                          'monitoring_states', 'risk_policies', 'incidents',
                          'risk_rule_states', 'push_subscriptions',
                          'notification_webhooks', 'notification_deliveries'
                      )
                    """)).isZero();
        } finally {
            dropActualDatabase(database);
        }
    }

    private void seedActualPrerequisites(DataSource source) throws Exception {
        withConnection(source, connection -> {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO users (
                            id, email, display_name, password_hash, role, enabled,
                            auth_version, created_at, updated_at
                        ) VALUES
                            (201, 'one@example.test', 'One', 'hash-one', 'USER', TRUE, 1,
                             '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
                            (202, 'two@example.test', 'Two', 'hash-two', 'USER', TRUE, 1,
                             '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z')
                        """);
                statement.execute("""
                        INSERT INTO auth_sessions (
                            sid, user_id, current_refresh_hash, created_at, expires_at, auth_version
                        ) VALUES
                            ('00000000-0000-0000-0000-000000000201', 201, 'refresh-one',
                             '2026-09-29T00:00:00Z', '2026-10-06T00:00:00Z', 1),
                            ('00000000-0000-0000-0000-000000000202', 202, 'refresh-two',
                             '2026-09-29T00:00:00Z', '2026-10-06T00:00:00Z', 1)
                        """);
                statement.execute("""
                        INSERT INTO database_configs (
                            id, collection_interval_seconds, created_at, enabled, host,
                            name, port, status, updated_at, config_version
                        ) VALUES
                            (101, 60, '2026-09-29T00:00:00Z', TRUE, '127.0.0.1',
                             'Primary database', 5432, 'UP', '2026-09-29T00:00:00Z', 1),
                            (102, 60, '2026-09-29T00:00:00Z', TRUE, '127.0.0.1',
                             'Replica database', 5433, 'UP', '2026-09-29T00:00:00Z', 1)
                        """);
                statement.execute("""
                        INSERT INTO metric_data (
                            id, collection_status, created_at, database_config_id, timestamp,
                            config_version, collection_attempt_time, unavailable_metrics
                        ) VALUES
                            (301, 'SUCCESS', '2026-09-29T00:00:00Z', 101,
                             '2026-09-29T00:00:00Z', 1, '2026-09-29T00:00:00Z', '{}'::jsonb),
                            (302, 'SUCCESS', '2026-09-29T00:00:00Z', 102,
                             '2026-09-29T00:00:00Z', 1, '2026-09-29T00:00:00Z', '{}'::jsonb)
                        """);
            }
            connection.commit();
            return null;
        });
    }

    private void assertActualAContract(DataSource source) throws Exception {
        withConnection(source, connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT
                             (SELECT count(*) FROM information_schema.columns
                              WHERE table_schema = 'public' AND table_name = 'event_outbox') AS outbox_columns,
                             (SELECT count(*) FROM information_schema.columns
                              WHERE table_schema = 'public' AND table_name = 'processed_events') AS processed_columns
                         """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("outbox_columns")).isEqualTo(11);
                assertThat(rows.getInt("processed_columns")).isEqualTo(4);
                return null;
            }
        });
    }

    private void assertRequiredParentKeys(DataSource source) throws Exception {
        assertThat(singleLong(source, """
                SELECT count(*)
                FROM pg_constraint constraint_row
                JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
                WHERE schema_row.nspname = 'public'
                  AND (table_row.relname, constraint_row.conname) IN (
                      ('auth_sessions', 'auth_sessions_sid_user_unique'),
                      ('metric_data', 'metric_data_id_target_unique')
                  )
                """)).isEqualTo(2L);
    }

    private void dropActualDatabase(ActualDatabase database) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + quoteIdentifier(database.name()));
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private ProbeResult runConstraintProbe(DataSource source, String schema) throws Exception {
        return withConnection(source, connection -> {
            connection.setAutoCommit(false);
            executeScript(connection, SCHEMA_ROOT.resolve("probes/constraints.sql"), schema);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT count(*), string_agg(scenario, ',' ORDER BY scenario) "
                                 + "FROM part_c_probe_results")) {
                rows.next();
                ProbeResult result = new ProbeResult(rows.getInt(1), rows.getString(2));
                connection.rollback();
                return result;
            }
        });
    }

    private void assertMonitoringStateActivationColumn(DataSource source, String schema) throws Exception {
        Column column = withConnection(source, connection -> {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT column_name, data_type, udt_name, is_nullable
                         FROM information_schema.columns
                         WHERE table_schema = '%s'
                           AND table_name = 'monitoring_states'
                           AND column_name = 'activation_at'
                         """.formatted(schema))) {
                assertThat(rows.next()).isTrue();
                Column result = new Column(rows.getString("column_name"),
                        rows.getString("data_type"), rows.getString("udt_name"),
                        rows.getString("is_nullable"));
                connection.rollback();
                return result;
            }
        });

        assertThat(column.name()).isEqualTo("activation_at");
        assertThat(column.dataType()).isEqualTo("timestamp with time zone");
        assertThat(column.udtName()).isEqualTo("timestamptz");
        assertThat(column.nullable()).isEqualTo("YES");
    }

    private void assertPartCTableInventory(DataSource source, String schema) throws Exception {
        List<String> tables = withConnection(source, connection -> {
            connection.setAutoCommit(false);
            List<String> names = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT table_name
                         FROM information_schema.tables
                         WHERE table_schema = '%s'
                           AND table_type = 'BASE TABLE'
                           AND table_name NOT IN (
                               'users', 'auth_sessions', 'used_refresh_tokens',
                               'database_configs', 'metric_data', 'blocked_reasons',
                               'audit_logs', 'access_logs', 'event_outbox', 'processed_events',
                               'flyway_schema_history'
                           )
                         ORDER BY table_name
                         """.formatted(schema))) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
                connection.rollback();
                return names;
            }
        });

        assertThat(tables).containsExactly(
                "incidents", "monitoring_states", "notification_deliveries",
                "notification_webhooks", "push_subscriptions", "risk_policies",
                "risk_rule_states");
    }

    private void assertOutboxShape(DataSource source, String schema) throws Exception {
        List<Column> columns = withConnection(source, connection -> {
            connection.setAutoCommit(false);
            List<Column> result = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT column_name, data_type, udt_name, is_nullable
                         FROM information_schema.columns
                         WHERE table_schema = '%s'
                           AND table_name = 'event_outbox'
                         ORDER BY ordinal_position
                         """.formatted(schema))) {
                while (rows.next()) {
                    result.add(new Column(rows.getString("column_name"),
                            rows.getString("data_type"), rows.getString("udt_name"),
                            rows.getString("is_nullable")));
                }
                connection.rollback();
                return result;
            }
        });

        assertThat(columns).extracting(Column::name).containsExactly(
                "event_id", "seq", "event_type", "stream_key", "ordering_key", "payload",
                "created_at", "published_at", "attempts", "next_attempt_at", "last_error");
        assertThat(columns.get(5).udtName()).isEqualTo("jsonb");
        assertThat(columns.get(6).udtName()).isEqualTo("timestamptz");
        assertThat(columns.get(7).udtName()).isEqualTo("timestamptz");
        assertThat(columns.get(9).udtName()).isEqualTo("timestamptz");

        List<Column> processed = withConnection(source, connection -> {
            connection.setAutoCommit(false);
            List<Column> result = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT column_name, data_type, udt_name, is_nullable
                         FROM information_schema.columns
                         WHERE table_schema = '%s'
                           AND table_name = 'processed_events'
                         ORDER BY ordinal_position
                         """.formatted(schema))) {
                while (rows.next()) {
                    result.add(new Column(rows.getString("column_name"),
                            rows.getString("data_type"), rows.getString("udt_name"),
                            rows.getString("is_nullable")));
                }
                connection.rollback();
                return result;
            }
        });
        assertThat(processed).extracting(Column::name).containsExactly(
                "stream", "consumer_group", "event_id", "processed_at");
        assertThat(processed.get(3).udtName()).isEqualTo("timestamptz");
    }

    private static void collectMigrationNames(List<String> names, Path migrations) throws IOException {
        try (var paths = Files.list(migrations)) {
            paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .forEach(names::add);
        }
    }

    private static void executeScript(Connection connection, Path path, String schema) throws Exception {
        String script = Files.readString(path, StandardCharsets.UTF_8);
        if (!schema.equals("part_c_probe")) {
            script = script.replace("part_c_probe, pg_catalog", schema + ", pg_catalog")
                    .replace("'part_c_probe'", "'" + schema + "'");
        }
        for (String statement : splitSql(script)) {
            try (Statement sql = connection.createStatement()) {
                sql.execute(statement);
            }
        }
    }

    private static List<String> splitSql(String script) {
        List<String> statements = new ArrayList<>();
        int start = 0;
        char quote = 0;
        String dollarQuote = null;
        boolean lineComment = false;
        for (int index = 0; index < script.length(); index++) {
            char current = script.charAt(index);
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (dollarQuote != null) {
                if (script.startsWith(dollarQuote, index)) {
                    index += dollarQuote.length() - 1;
                    dollarQuote = null;
                }
                continue;
            }
            if (quote != 0) {
                if (current == quote) {
                    if (index + 1 < script.length() && script.charAt(index + 1) == quote) {
                        index++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (current == '-' && index + 1 < script.length() && script.charAt(index + 1) == '-') {
                lineComment = true;
                index++;
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '$') {
                int end = script.indexOf('$', index + 1);
                if (end >= 0 && script.substring(index + 1, end)
                        .matches("[A-Za-z_][A-Za-z0-9_]*|")) {
                    dollarQuote = script.substring(index, end + 1);
                    index = end;
                }
            } else if (current == ';') {
                addStatement(statements, script.substring(start, index));
                start = index + 1;
            }
        }
        addStatement(statements, script.substring(start));
        return statements;
    }

    private static void addStatement(List<String> statements, String statement) {
        String trimmed = statement.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
            statements.add(trimmed);
        }
    }

    private static Path locateRepository() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("backend/schema/part-c"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root");
    }

    private <T> T withConnection(DataSource source, SqlWork<T> work) throws Exception {
        try (Connection connection = source.getConnection()) {
            return work.apply(connection);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws Exception;
    }

    private record Column(String name, String dataType, String udtName, String nullable) { }

    private record ProbeResult(int passedScenarios, String scenarios) { }

    private record ActualDatabase(String name, DataSource dataSource) { }
}
