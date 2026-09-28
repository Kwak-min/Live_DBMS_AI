package com.example.monitoring.auth.repository;

import com.example.monitoring.auth.domain.AuthSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s join fetch s.user where s.currentRefreshHash = :hash")
    Optional<AuthSession> findByCurrentRefreshHashForUpdate(@Param("hash") String hash);

    @Query("select s from AuthSession s join fetch s.user where s.id = :sid")
    Optional<AuthSession> findWithUserById(@Param("sid") UUID sid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s join fetch s.user where s.id = :sid")
    Optional<AuthSession> findByIdForUpdate(@Param("sid") UUID sid);

    @Modifying
    @Query("update AuthSession s set s.revokedAt = :now where s.user.id = :userId and s.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from AuthSession s where s.expiresAt < :cutoff or (s.revokedAt is not null and s.revokedAt < :cutoff)")
    int deleteExpiredOrRevoked(@Param("cutoff") Instant cutoff);
}
