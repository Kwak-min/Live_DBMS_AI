package com.example.monitoring.service;

import com.example.monitoring.domain.AuditLog;
import com.example.monitoring.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional
    public AuditLog logAccess(String clientIp, String httpMethod, String requestUri, String userAgent, Integer httpStatus, Long executionTimeMs) {
        return logAccess(null, clientIp, httpMethod, requestUri, httpStatus, executionTimeMs, UUID.randomUUID());
    }

    @Transactional
    public AuditLog logAccess(Long actorId, String clientIp, String method, String path,
                              Integer statusCode, Long durationMs, UUID requestId) {
        AuditLog auditLog = AuditLog.builder()
                .actorId(actorId)
                .clientIp(clientIp)
                .method(method)
                .path(path)
                .statusCode(statusCode)
                .durationMs(durationMs)
                .occurredAt(Instant.now())
                .requestId(requestId)
                .build();

        try {
            return auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to save client audit log for IP: {}", clientIp, e);
            return null;
        }
    }
}
