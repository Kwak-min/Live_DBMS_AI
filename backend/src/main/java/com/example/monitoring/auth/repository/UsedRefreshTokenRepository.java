package com.example.monitoring.auth.repository;

import com.example.monitoring.auth.domain.UsedRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsedRefreshTokenRepository extends JpaRepository<UsedRefreshToken, String> {

    Optional<UsedRefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from UsedRefreshToken t where t.sessionId in "
            + "(select s.id from AuthSession s where s.expiresAt < :cutoff or (s.revokedAt is not null and s.revokedAt < :cutoff))")
    int deleteForExpiredOrRevokedSessions(@Param("cutoff") Instant cutoff);
}
