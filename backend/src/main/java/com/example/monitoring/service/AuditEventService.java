package com.example.monitoring.service;

import com.example.monitoring.domain.*;
import com.example.monitoring.repository.AuditEventRepository;
import com.example.monitoring.common.web.AuditRequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditEventService {
    private final AuditEventRepository auditEventRepository;
    private final AuditRequestContext auditRequestContext;

    public void successCurrent(AuditAction action, AuditTargetType targetType, String targetId,
                               Long databaseConfigId, String summary) {
        AuditRequestContext.Details context = auditRequestContext.current();
        success(context.actorId(), action, targetType, targetId, databaseConfigId, context.clientIp(), context.requestId(), summary);
    }

    public void failureCurrent(AuditAction action, AuditTargetType targetType, String targetId,
                               Long databaseConfigId, String summary) {
        AuditRequestContext.Details context = auditRequestContext.current();
        failure(context.actorId(), action, targetType, targetId, databaseConfigId, context.clientIp(), context.requestId(), summary);
    }

    @Transactional
    public void success(Long actorId, AuditAction action, AuditTargetType targetType, String targetId,
                        Long databaseConfigId, String clientIp, UUID requestId, String summary) {
        save(actorId, action, targetType, targetId, databaseConfigId, AuditResult.SUCCESS, clientIp, requestId, summary);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(Long actorId, AuditAction action, AuditTargetType targetType, String targetId,
                        Long databaseConfigId, String clientIp, UUID requestId, String summary) {
        save(actorId, action, targetType, targetId, databaseConfigId, AuditResult.FAILURE, clientIp, requestId, summary);
    }

    private void save(Long actorId, AuditAction action, AuditTargetType targetType, String targetId,
                      Long databaseConfigId, AuditResult result, String clientIp, UUID requestId, String summary) {
        auditEventRepository.save(AuditEvent.builder().actorId(actorId).action(action).targetType(targetType)
                .targetId(targetId).databaseConfigId(databaseConfigId).result(result).occurredAt(Instant.now())
                .clientIp(clientIp).requestId(requestId).summary(summary).build());
    }
}
