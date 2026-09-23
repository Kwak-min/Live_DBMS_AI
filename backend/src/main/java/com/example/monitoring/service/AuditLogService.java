package com.example.monitoring.service;

import com.example.monitoring.domain.AuditLog;
import com.example.monitoring.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional
    public AuditLog logAccess(String clientIp, String httpMethod, String requestUri, String userAgent, Integer httpStatus, Long executionTimeMs) {
        AuditLog auditLog = AuditLog.builder()
                .clientIp(clientIp)
                .httpMethod(httpMethod)
                .requestUri(requestUri)
                .userAgent(userAgent != null && userAgent.length() > 500 ? userAgent.substring(0, 500) : userAgent)
                .httpStatus(httpStatus)
                .executionTimeMs(executionTimeMs)
                .timestamp(LocalDateTime.now())
                .build();

        try {
            return auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to save client audit log for IP: {}", clientIp, e);
            return null;
        }
    }
}
