package com.example.monitoring.repository;

import com.example.monitoring.domain.MetricData;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetricDataRepository extends JpaRepository<MetricData, Long> {

    Optional<MetricData> findFirstByDatabaseConfigIdOrderByTimestampDescIdDesc(Long databaseConfigId);

    List<MetricData> findByDatabaseConfigIdAndTimestampBetweenOrderByTimestampAscIdAsc(
            Long databaseConfigId, Instant start, Instant end);

    @Query("SELECT m FROM MetricData m WHERE m.databaseConfig.id = :dbId ORDER BY m.timestamp DESC, m.id DESC")
    List<MetricData> findRecentMetrics(@Param("dbId") Long dbId, Pageable pageable);

    /** 같은 설정 버전의 가장 최근 스냅샷이 가진 lastSuccessAt (재시작 후에도 저장값에서 복원) */
    @Query("""
            SELECT m.lastSuccessAt FROM MetricData m
            WHERE m.databaseConfig.id = :dbId AND m.configVersion = :configVersion
            ORDER BY m.timestamp DESC, m.id DESC
            """)
    List<Instant> findLatestLastSuccessAt(@Param("dbId") Long dbId, @Param("configVersion") Long configVersion,
                                          Pageable pageable);

    @Modifying
    @Query("DELETE FROM MetricData m WHERE m.timestamp < :cutoff")
    int deleteByTimestampBefore(@Param("cutoff") Instant cutoff);
}
