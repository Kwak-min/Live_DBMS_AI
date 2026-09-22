package com.example.monitoring.repository;

import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.TargetDbStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DatabaseConfigRepository extends JpaRepository<DatabaseConfig, Long> {
    List<DatabaseConfig> findByEnabledTrue();
    List<DatabaseConfig> findByStatus(TargetDbStatus status);
}

