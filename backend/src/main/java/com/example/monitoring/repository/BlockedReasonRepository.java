package com.example.monitoring.repository;

import com.example.monitoring.domain.BlockedReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 기존 차단 이력(legacy). v1에서는 새로 쓰지 않고 보관 기간 정리만 한다. */
@Repository
public interface BlockedReasonRepository extends JpaRepository<BlockedReason, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM BlockedReason b WHERE b.blockedAt < :cutoff")
    int deleteBlockedBefore(@Param("cutoff") LocalDateTime cutoff);
}
