package com.example.monitoring.service;

import com.example.monitoring.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 검수 시나리오 T26: 보관 경계 직전/직후 자료를 실제 PostgreSQL에서 확인한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JdbcTemplateAutoConfiguration.class)
@Import({EmbeddedPostgresSupport.Config.class, MetricRetentionService.class})
class MetricRetentionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-29T03:00:00.000Z");
    private static final Instant CUTOFF = NOW.minus(30, ChronoUnit.DAYS);

    @Autowired
    private MetricRetentionService retentionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(retentionService, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Only snapshots strictly before now - 30 days (UTC) are deleted; the boundary and newer rows remain")
    void deletesOnlyExpiredSnapshots() {
        long target = insertTarget(false);
        long justBefore = insertMetric(target, CUTOFF.minusMillis(1));
        long atCutoff = insertMetric(target, CUTOFF);
        long justAfter = insertMetric(target, CUTOFF.plusMillis(1));
        long recent = insertMetric(target, NOW.minusSeconds(5));

        retentionService.purgeExpiredMetrics();

        assertThat(remaining(List.of(justBefore, atCutoff, justAfter, recent)))
                .containsExactly(atCutoff, justAfter, recent);
    }

    @Test
    @DisplayName("Expired snapshots of deleted targets are purged across several batches")
    void purgesAcrossBatchesIncludingDeletedTargets() {
        long deletedTarget = insertTarget(true);
        int expiredCount = MetricRetentionService.BATCH_SIZE + 5;
        jdbcTemplate.update("""
                INSERT INTO metric_data (database_config_id, config_version, timestamp, collection_attempt_time,
                                         collection_status, unavailable_metrics, created_at)
                SELECT ?, 1, ts, ts, 'SUCCESS', '{}'::jsonb, now()
                FROM generate_series(?::timestamptz - (? - 1) * interval '1 second', ?::timestamptz,
                                     interval '1 second') AS ts
                """, deletedTarget, Timestamp.from(CUTOFF.minusSeconds(60)), expiredCount,
                Timestamp.from(CUTOFF.minusSeconds(60)));
        long kept = insertMetric(deletedTarget, CUTOFF.plusSeconds(60));

        int deleted = retentionService.purgeExpiredMetrics();

        assertThat(deleted).isGreaterThanOrEqualTo(expiredCount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM metric_data WHERE database_config_id = ?", Long.class, deletedTarget))
                .isEqualTo(1L);
        assertThat(remaining(List.of(kept))).containsExactly(kept);
    }

    private List<Long> remaining(List<Long> ids) {
        return jdbcTemplate.queryForList("SELECT id FROM metric_data WHERE id = ANY(?) ORDER BY timestamp",
                Long.class, (Object) ids.toArray(Long[]::new));
    }

    private long insertTarget(boolean deleted) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO database_configs
                    (collection_interval_seconds, created_at, enabled, host, name, port, status, config_version, deleted_at)
                VALUES (5, now(), true, '127.0.0.1', 'db', 13306, 'UNKNOWN', 1, CASE WHEN ? THEN now() ELSE NULL END)
                RETURNING id
                """, Long.class, deleted);
    }

    private long insertMetric(long targetId, Instant timestamp) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO metric_data (database_config_id, config_version, timestamp, collection_attempt_time,
                                         collection_status, unavailable_metrics, created_at)
                VALUES (?, 1, ?, ?, 'SUCCESS', '{}'::jsonb, now())
                RETURNING id
                """, Long.class, targetId, Timestamp.from(timestamp), Timestamp.from(timestamp));
    }
}
