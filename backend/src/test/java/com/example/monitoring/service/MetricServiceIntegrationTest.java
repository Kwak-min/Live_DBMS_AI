package com.example.monitoring.service;

import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.FieldErrorResponse;
import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.dto.MetricResponseDto;
import com.example.monitoring.metric.MetricQueryService;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.BDDMockito.given;

/** docs/api.md 4절, 검수 시나리오 T09(204/404/[]/400)·T10([0,10) 경계와 정렬). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JdbcTemplateAutoConfiguration.class)
@Import({EmbeddedPostgresSupport.Config.class, MetricService.class, MetricQueryService.class})
class MetricServiceIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-28T03:00:00.000Z");

    @Autowired
    private MetricService metricService;

    @Autowired
    private MetricQueryService metricQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TargetProvider targetProvider;

    @Test
    @DisplayName("latest returns the newest snapshot of the current configVersion, empty when none, 404 without target")
    void latestFollowsCurrentConfigVersion() {
        long id = insertTarget(false);
        insertMetric(id, 1, T0);
        long oldVersionNewer = insertMetric(id, 1, T0.plusSeconds(10));
        given(targetProvider.getMetadata(id)).willReturn(Optional.of(metadata(id, 2)));

        assertThat(metricService.getLatestMetric(id)).isEmpty();

        long current = insertMetric(id, 2, T0.plusSeconds(5));
        assertThat(metricService.getLatestMetric(id)).map(MetricResponseDto::getId).contains(current);
        assertThat(metricQueryService.latest(id, 1)).map(MetricResponseDto::getId).contains(oldVersionNewer);

        given(targetProvider.getMetadata(999_999L)).willReturn(Optional.empty());
        assertError(() -> metricService.getLatestMetric(999_999L), HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND");
    }

    @Test
    @DisplayName("history is half-open [start, end) ordered by timestamp then id; empty range returns []")
    void historyIsHalfOpenAndOrdered() {
        long id = insertTarget(false);
        long at0 = insertMetric(id, 1, T0);
        long at5a = insertMetric(id, 1, T0.plusSeconds(5));
        long at5b = insertMetric(id, 1, T0.plusSeconds(5));
        insertMetric(id, 1, T0.plusSeconds(10));

        List<Long> ids = metricService.getMetricHistory(id, "2026-09-28T03:00:00.000Z", "2026-09-28T03:00:10.000Z")
                .stream().map(MetricResponseDto::getId).toList();

        assertThat(ids).containsExactly(at0, at5a, at5b);
        assertThat(metricService.getMetricHistory(id, "2026-09-27T00:00:00Z", "2026-09-27T01:00:00Z")).isEmpty();
    }

    @Test
    @DisplayName("recent is newest first, keeps deleted targets' data, and validates limit 1~1000")
    void recentOrderingAndLimit() {
        long id = insertTarget(true);
        long first = insertMetric(id, 1, T0);
        long second = insertMetric(id, 1, T0.plusSeconds(5));

        assertThat(metricService.getRecentMetrics(id, null)).extracting(MetricResponseDto::getId)
                .containsExactly(second, first);
        assertThat(metricService.getRecentMetrics(id, 1)).extracting(MetricResponseDto::getId).containsExactly(second);

        assertFieldError(() -> metricService.getRecentMetrics(id, 0), "limit", "OUT_OF_RANGE");
        assertFieldError(() -> metricService.getRecentMetrics(id, 1001), "limit", "OUT_OF_RANGE");
        assertError(() -> metricService.getRecentMetrics(999_999L, null), HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND");
    }

    @Test
    @DisplayName("history rejects missing/non-UTC times, start >= end and ranges over 24 hours")
    void historyValidation() {
        long id = insertTarget(false);

        assertFieldError(() -> metricService.getMetricHistory(id, null, "2026-09-28T04:00:00Z"), "start", "REQUIRED");
        assertFieldError(() -> metricService.getMetricHistory(id, "2026-09-28T03:00:00", "2026-09-28T04:00:00Z"),
                "start", "INVALID_FORMAT");
        assertFieldError(() -> metricService.getMetricHistory(id, "2026-09-28T03:00:00Z", "tomorrow"),
                "end", "INVALID_FORMAT");
        assertError(() -> metricService.getMetricHistory(id, "2026-09-28T04:00:00Z", "2026-09-28T04:00:00Z"),
                HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE");
        assertError(() -> metricService.getMetricHistory(id, "2026-09-28T00:00:00Z", "2026-09-29T00:00:00.001Z"),
                HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE");
        assertThat(metricService.getMetricHistory(id, "2026-09-28T00:00:00Z", "2026-09-29T00:00:00Z")).isEmpty();
    }

    @Test
    @DisplayName("history over 20,000 results is rejected instead of silently truncated")
    void historyResultLimit() {
        long id = insertTarget(false);
        jdbcTemplate.update("""
                INSERT INTO metric_data (database_config_id, config_version, timestamp, collection_attempt_time,
                                         collection_status, unavailable_metrics, created_at)
                SELECT ?, 1, ts, ts, 'SUCCESS', '{}'::jsonb, now()
                FROM generate_series(?::timestamptz, ?::timestamptz, interval '1 second') AS ts
                """, id, Timestamp.from(T0), Timestamp.from(T0.plusSeconds(20_000)));

        assertError(() -> metricService.getMetricHistory(id, "2026-09-28T03:00:00Z", "2026-09-28T09:00:00Z"),
                HttpStatus.BAD_REQUEST, "RESULT_LIMIT_EXCEEDED");
        assertThat(metricService.getMetricHistory(id, "2026-09-28T03:00:00Z", "2026-09-28T03:01:00Z")).hasSize(60);
    }

    private long insertTarget(boolean deleted) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO database_configs
                    (collection_interval_seconds, created_at, enabled, host, name, port, status, config_version, deleted_at)
                VALUES (5, now(), true, '127.0.0.1', 'db', 13306, 'UNKNOWN', 1, CASE WHEN ? THEN now() ELSE NULL END)
                RETURNING id
                """, Long.class, deleted);
    }

    private long insertMetric(long targetId, long configVersion, Instant timestamp) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO metric_data (database_config_id, config_version, timestamp, collection_attempt_time,
                                         collection_status, unavailable_metrics, created_at)
                VALUES (?, ?, ?, ?, 'SUCCESS', '{}'::jsonb, now())
                RETURNING id
                """, Long.class, targetId, configVersion, Timestamp.from(timestamp), Timestamp.from(timestamp));
    }

    private static TargetMetadata metadata(long id, long configVersion) {
        return new TargetMetadata(id, configVersion, "db", "127.0.0.1", 13306, null, true);
    }

    private static void assertError(ThrowingCallable call, HttpStatus status, String code) {
        ApiException error = catchThrowableOfType(call, ApiException.class);
        assertThat(error).as("expected ApiException " + code).isNotNull();
        assertThat(error.getStatus()).isEqualTo(status);
        assertThat(error.getCode()).isEqualTo(code);
    }

    private static void assertFieldError(ThrowingCallable call, String field, String fieldCode) {
        ApiException error = catchThrowableOfType(call, ApiException.class);
        assertThat(error).as("expected validation error on " + field).isNotNull();
        assertThat(error.getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.getFieldErrors()).extracting(FieldErrorResponse::field, FieldErrorResponse::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(field, fieldCode));
    }
}
