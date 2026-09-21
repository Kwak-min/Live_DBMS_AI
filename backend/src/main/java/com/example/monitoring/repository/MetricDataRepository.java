package com.example.monitoring.repository;

import com.example.monitoring.domain.MetricData;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetricDataRepository extends JpaRepository<MetricData, Long> {

    Optional<MetricData> findFirstByDatabaseConfigIdOrderByTimestampDesc(Long databaseConfigId);

    List<MetricData> findByDatabaseConfigIdAndTimestampBetweenOrderByTimestampAsc(
            Long databaseConfigId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT m FROM MetricData m WHERE m.databaseConfig.id = :dbId ORDER BY m.timestamp DESC")
    List<MetricData> findRecentMetrics(@Param("dbId") Long dbId, Pageable pageable);
}
