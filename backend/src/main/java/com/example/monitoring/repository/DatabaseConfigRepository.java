package com.example.monitoring.repository;

import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.TargetDbStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface DatabaseConfigRepository extends JpaRepository<DatabaseConfig, Long> {
    List<DatabaseConfig> findByEnabledTrue();
    List<DatabaseConfig> findByStatus(TargetDbStatus status);
    List<DatabaseConfig> findByEnabledTrueAndDeletedAtIsNull();
    Page<DatabaseConfig> findByDeletedAtIsNullOrderByIdAsc(Pageable pageable);
    Page<DatabaseConfig> findByDeletedAtIsNullAndEnabledOrderByIdAsc(Boolean enabled, Pageable pageable);
    Optional<DatabaseConfig> findByIdAndDeletedAtIsNull(Long id);
    long countByDeletedAtIsNull();
    List<DatabaseConfig> findAllByOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DatabaseConfig d where d.id = :id and d.deletedAt is null")
    Optional<DatabaseConfig> findActiveByIdForUpdate(@Param("id") Long id);
}

