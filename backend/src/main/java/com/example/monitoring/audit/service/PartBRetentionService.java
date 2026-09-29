package com.example.monitoring.audit.service;

import com.example.monitoring.auth.repository.AuthSessionRepository;
import com.example.monitoring.auth.repository.UsedRefreshTokenRepository;
import com.example.monitoring.repository.AuditEventRepository;
import com.example.monitoring.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class PartBRetentionService {
    private final AuditEventRepository auditEventRepository;
    private final AuditLogRepository accessLogRepository;
    private final UsedRefreshTokenRepository usedRefreshTokenRepository;
    private final AuthSessionRepository authSessionRepository;

    @Transactional
    public CleanupResult purge(Instant now) {
        long audits = auditEventRepository.deleteByOccurredAtBefore(now.minus(180, ChronoUnit.DAYS));
        long accesses = accessLogRepository.deleteByOccurredAtBefore(now.minus(30, ChronoUnit.DAYS));
        Instant sessionCutoff = now.minus(1, ChronoUnit.DAYS);
        int usedTokens = usedRefreshTokenRepository.deleteForExpiredOrRevokedSessions(sessionCutoff);
        int sessions = authSessionRepository.deleteExpiredOrRevoked(sessionCutoff);
        return new CleanupResult(audits, accesses, usedTokens, sessions);
    }

    public record CleanupResult(long audits, long accesses, int usedTokens, int sessions) { }
}
