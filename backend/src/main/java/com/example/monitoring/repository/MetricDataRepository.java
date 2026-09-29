package com.example.monitoring.repository;

import com.example.monitoring.domain.MetricData;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetricDataRepository extends JpaRepository<MetricData, Long> {

    /** 현재 설정 버전의 최신 스냅샷 */
    Optional<MetricData> findFirstByDatabaseConfigIdAndConfigVersionOrderByTimestampDescIdDesc(
            Long databaseConfigId, Long configVersion);

    @Query("SELECT m FROM MetricData m WHERE m.databaseConfig.id = :dbId ORDER BY m.timestamp DESC, m.id DESC")
    List<MetricData> findRecentMetrics(@Param("dbId") Long dbId, Pageable pageable);

    /** 반개구간 [start, end) */
    @Query("""
            SELECT m FROM MetricData m
            WHERE m.databaseConfig.id = :dbId AND m.timestamp >= :start AND m.timestamp < :end
            ORDER BY m.timestamp ASC, m.id ASC
            """)
    List<MetricData> findHistory(@Param("dbId") Long dbId, @Param("start") Instant start, @Param("end") Instant end,
                                 Pageable pageable);

    @Query("""
            SELECT count(m) FROM MetricData m
            WHERE m.databaseConfig.id = :dbId AND m.timestamp >= :start AND m.timestamp < :end
            """)
    long countHistory(@Param("dbId") Long dbId, @Param("start") Instant start, @Param("end") Instant end);

    /** 같은 설정 버전의 가장 최근 스냅샷이 가진 lastSuccessAt (재시작 후에도 저장값에서 복원) */
    @Query("""
            SELECT m.lastSuccessAt FROM MetricData m
            WHERE m.databaseConfig.id = :dbId AND m.configVersion = :configVersion
            ORDER BY m.timestamp DESC, m.id DESC
            """)
    List<Instant> findLatestLastSuccessAt(@Param("dbId") Long dbId, @Param("configVersion") Long configVersion,
                                          Pageable pageable);

    /** 보관 기간 정리용 배치 ID 조회. 한 번에 작은 묶음만 지워 긴 잠금을 피한다. */
    @Query("SELECT m.id FROM MetricData m WHERE m.timestamp < :cutoff ORDER BY m.id ASC")
    List<Long> findIdsOlderThan(@Param("cutoff") Instant cutoff, Pageable pageable);
}
