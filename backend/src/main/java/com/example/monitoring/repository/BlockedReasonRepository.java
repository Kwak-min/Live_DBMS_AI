package com.example.monitoring.repository;

import com.example.monitoring.domain.BlockedReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlockedReasonRepository extends JpaRepository<BlockedReason, Long> {

    /** 특정 DB의 차단 이력 최신순 조회 */
    List<BlockedReason> findByDatabaseConfigIdOrderByBlockedAtDesc(Long databaseConfigId);

    /** 특정 DB의 현재 활성 차단 레코드 (unblockedAt == null) */
    Optional<BlockedReason> findByDatabaseConfigIdAndUnblockedAtIsNull(Long databaseConfigId);

    /** 특정 DB가 현재 차단 중인지 여부 */
    boolean existsByDatabaseConfigIdAndUnblockedAtIsNull(Long databaseConfigId);
}
