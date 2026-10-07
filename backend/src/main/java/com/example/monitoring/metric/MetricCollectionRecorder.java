package com.example.monitoring.metric;

import com.example.monitoring.common.outbox.OutboxEventType;
import com.example.monitoring.common.outbox.OutboxWriter;
import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.repository.MetricDataRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 수집 결과 기록. 대상 row를 잠근 뒤 수집 시작 시의 configVersion·enabled·삭제 여부를 다시 확인하고,
 * 같으면 metric과 MetricCollectedEvent outbox를 한 트랜잭션으로 저장한다. 다르면 결과를 버리고 이벤트도 만들지 않는다.
 * PostgreSQL 저장에 실패하면 예외가 전파되어 이벤트도 남지 않는다(미저장 관측을 Redis에 먼저 보내지 않음).
 */
@Service
public class MetricCollectionRecorder {

    private final EntityManager entityManager;
    private final MetricDataRepository metricDataRepository;
    private final OutboxWriter outboxWriter;
    private final boolean riskEnabled;

    public MetricCollectionRecorder(EntityManager entityManager, MetricDataRepository metricDataRepository,
                                    OutboxWriter outboxWriter,
                                    @Value("${monitoring.risk.enabled:false}") boolean riskEnabled) {
        this.entityManager = entityManager;
        this.metricDataRepository = metricDataRepository;
        this.outboxWriter = outboxWriter;
        this.riskEnabled = riskEnabled;
    }

    /** @return 저장된 스냅샷. 설정이 바뀌었거나 삭제·비활성화되었으면 empty */
    @Transactional
    public Optional<MetricData> record(CollectorTarget target, MetricData metric) {
        return record(target.id(), target.configVersion(), metric);
    }

    /** Records a credential failure without ever constructing a target containing fake credentials. */
    @Transactional
    public Optional<MetricData> record(TargetMetadata target, MetricData metric) {
        return record(target.id(), target.configVersion(), metric);
    }

    private Optional<MetricData> record(long targetId, long configVersion, MetricData metric) {
        DatabaseConfig config = lockActiveTarget(targetId);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())
                || config.getConfigVersion() == null || config.getConfigVersion() != configVersion) {
            return Optional.empty();
        }

        metric.setDatabaseConfig(config);
        metric.setConfigVersion(configVersion);
        metric.setLastSuccessAt(metric.getCollectionStatus() == CollectionStatus.SUCCESS
                ? metric.getTimestamp()
                : previousLastSuccessAt(targetId, configVersion));
        MetricData saved = metricDataRepository.saveAndFlush(metric);

        outboxWriter.append(UUID.randomUUID(), OutboxEventType.METRIC_COLLECTED, "database:" + targetId,
                MetricCollectedPayload.from(saved, config.getId(), config.getName()));
        if (!riskEnabled) {
            updateDisplayStatus(targetId, saved);
        }
        return Optional.of(saved);
    }

    private DatabaseConfig lockActiveTarget(long id) {
        return entityManager.createQuery(
                        "SELECT d FROM DatabaseConfig d WHERE d.id = :id AND d.deletedAt IS NULL", DatabaseConfig.class)
                .setParameter("id", id)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private Instant previousLastSuccessAt(long databaseConfigId, long configVersion) {
        List<Instant> latest = metricDataRepository.findLatestLastSuccessAt(
                databaseConfigId, configVersion, PageRequest.of(0, 1));
        return latest.isEmpty() ? null : latest.get(0);
    }

    /**
     * DB 목록 응답(B)이 읽는 표시용 상태만 갱신한다. 설정 엔티티 전체를 저장하지 않으므로 B의 설정 변경을 덮어쓰지 않는다.
     * 위험도 기능(C)이 켜져 있으면 B 응답이 monitoring_states를 읽으므로 이 갱신을 하지 않는다
     * ({@link com.example.monitoring.database.service.DatabaseDisplayStatusReader}).
     */
    private void updateDisplayStatus(long databaseConfigId, MetricData metric) {
        boolean success = metric.getCollectionStatus() == CollectionStatus.SUCCESS;
        Instant checkedAt = metric.getTimestamp();
        entityManager.createQuery("""
                        UPDATE DatabaseConfig d
                        SET d.status = :status, d.lastCheckedAt = :checkedAt, d.lastErrorMessage = :error,
                            d.lastSuccessAt = CASE WHEN :success = true THEN :checkedAt ELSE d.lastSuccessAt END
                        WHERE d.id = :id
                        """)
                .setParameter("status", success ? TargetDbStatus.UP : metric.getCollectionStatus() == CollectionStatus.CONNECTION_FAILED
                        ? TargetDbStatus.DOWN : TargetDbStatus.UNKNOWN)
                .setParameter("checkedAt", checkedAt)
                .setParameter("error", success ? null : metric.getErrorMessage())
                .setParameter("success", success)
                .setParameter("id", databaseConfigId)
                .executeUpdate();
    }
}
