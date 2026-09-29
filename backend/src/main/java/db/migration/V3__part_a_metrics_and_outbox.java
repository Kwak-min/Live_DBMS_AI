package db.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Part A-owned V3: 공통 outbox·소비자 중복 처리 기록과 metric_data v1 전환.
 * <ul>
 *   <li>event_outbox / processed_events 생성 (CollectorHeartbeatEvent는 outbox 대상이 아님)</li>
 *   <li>metric_data 시간을 timestamptz로 변환. 기존 행이 있으면 LEGACY_TIME_ZONE이 필수이며 추측하지 않는다.</li>
 *   <li>configVersion·수집 시각·마지막 성공·파생 지표·errorCode·unavailableMetrics 추가</li>
 * </ul>
 * 기존 스냅샷의 qps 등은 의미가 달라 무손실 변환하지 않고 그대로 보존한다.
 */
public class V3__part_a_metrics_and_outbox extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        createOutboxTables(connection);
        migrateMetricData(connection);
    }

    private void createOutboxTables(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE event_outbox (
                    event_id        UUID         NOT NULL,
                    seq             BIGSERIAL    NOT NULL,
                    event_type      VARCHAR(64)  NOT NULL CHECK (event_type IN (
                                        'MetricCollectedEvent',
                                        'MonitoringStatusChangedEvent',
                                        'IncidentCreatedEvent',
                                        'IncidentUpdatedEvent',
                                        'IncidentResolvedEvent')),
                    stream_key      VARCHAR(128) NOT NULL,
                    ordering_key    VARCHAR(128),
                    payload         JSONB        NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
                    created_at      TIMESTAMPTZ  NOT NULL,
                    published_at    TIMESTAMPTZ,
                    attempts        INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
                    next_attempt_at TIMESTAMPTZ  NOT NULL,
                    last_error      VARCHAR(500),
                    PRIMARY KEY (event_id),
                    UNIQUE (seq)
                )
                """);
        execute(connection, "CREATE INDEX idx_event_outbox_unpublished ON event_outbox (seq) WHERE published_at IS NULL");
        execute(connection, "CREATE INDEX idx_event_outbox_published_at ON event_outbox (published_at) "
                + "WHERE published_at IS NOT NULL");
        execute(connection, """
                CREATE TABLE processed_events (
                    stream         VARCHAR(128) NOT NULL,
                    consumer_group VARCHAR(128) NOT NULL,
                    event_id       UUID         NOT NULL,
                    processed_at   TIMESTAMPTZ  NOT NULL,
                    PRIMARY KEY (stream, consumer_group, event_id)
                )
                """);
        execute(connection, "CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at)");
    }

    private void migrateMetricData(Connection connection) throws SQLException {
        String legacyTimeZone = hasRows(connection, "metric_data") ? legacyTimeZone() : "UTC";
        execute(connection, "ALTER TABLE metric_data ALTER COLUMN timestamp TYPE TIMESTAMPTZ "
                + "USING timestamp AT TIME ZONE '" + legacyTimeZone + "'");
        execute(connection, "ALTER TABLE metric_data ALTER COLUMN created_at TYPE TIMESTAMPTZ "
                + "USING created_at AT TIME ZONE '" + legacyTimeZone + "'");

        // 기존 스냅샷은 모두 설정 버전 1(V2 기본값)에서 수집되었다.
        execute(connection, "ALTER TABLE metric_data ADD COLUMN config_version BIGINT");
        execute(connection, "UPDATE metric_data SET config_version = 1");
        execute(connection, "ALTER TABLE metric_data ALTER COLUMN config_version SET NOT NULL");

        execute(connection, "ALTER TABLE metric_data ADD COLUMN collection_attempt_time TIMESTAMPTZ");
        execute(connection, "UPDATE metric_data SET collection_attempt_time = timestamp");
        execute(connection, "ALTER TABLE metric_data ALTER COLUMN collection_attempt_time SET NOT NULL");

        execute(connection, "ALTER TABLE metric_data ADD COLUMN last_success_at TIMESTAMPTZ");
        execute(connection, "UPDATE metric_data SET last_success_at = timestamp WHERE collection_status = 'SUCCESS'");

        execute(connection, "ALTER TABLE metric_data ADD COLUMN slow_queries_delta BIGINT");
        execute(connection, "ALTER TABLE metric_data ADD COLUMN slow_queries_per_second FLOAT(53)");
        execute(connection, "ALTER TABLE metric_data ADD COLUMN metric_window_seconds FLOAT(53)");
        execute(connection, """
                ALTER TABLE metric_data ADD COLUMN error_code VARCHAR(32) CHECK (error_code IN (
                    'AUTH_FAILED', 'CONNECT_TIMEOUT', 'CONNECTION_REFUSED', 'QUERY_FAILED', 'INTERNAL_ERROR', 'UNKNOWN'))
                """);
        execute(connection, "ALTER TABLE metric_data ADD COLUMN unavailable_metrics JSONB NOT NULL DEFAULT '{}'::jsonb "
                + "CHECK (jsonb_typeof(unavailable_metrics) = 'object')");

        execute(connection, "DROP INDEX IF EXISTS idx_metric_db_time");
        execute(connection, "CREATE INDEX idx_metric_data_target_time "
                + "ON metric_data (database_config_id, timestamp DESC, id DESC)");
    }

    private String legacyTimeZone() {
        String value = System.getenv("LEGACY_TIME_ZONE");
        if (value == null || value.isBlank()) value = System.getProperty("LEGACY_TIME_ZONE");
        if (value == null || value.isBlank()) {
            throw new FlywayException("LEGACY_TIME_ZONE is required for V3 because metric_data has legacy rows.");
        }
        try {
            return ZoneId.of(value).getId().replace("'", "''");
        } catch (DateTimeException exception) {
            throw new FlywayException("LEGACY_TIME_ZONE is not a valid time zone.", exception);
        }
    }

    private boolean hasRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM " + table + ")")) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
