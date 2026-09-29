package com.example.monitoring.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs", indexes = @Index(name = "idx_audit_logs_occurred_id", columnList = "occurred_at,id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long actorId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AuditAction action;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AuditTargetType targetType;
    @Column(length = 255) private String targetId;
    private Long databaseConfigId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AuditResult result;
    @Column(nullable = false, updatable = false) private Instant occurredAt;
    @Column(nullable = false, length = 45) private String clientIp;
    @Column(nullable = false, updatable = false) private UUID requestId;
    @Column(nullable = false, length = 500) private String summary;

    @PrePersist void onCreate() { if (occurredAt == null) occurredAt = Instant.now(); }
}
