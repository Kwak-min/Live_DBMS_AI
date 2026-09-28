package com.example.monitoring.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "access_logs", indexes = @Index(name = "idx_access_logs_occurred_id", columnList = "occurred_at,id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long actorId;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(nullable = false, length = 500)
    private String path;

    @Column(nullable = false)
    private Integer statusCode;

    @Column(nullable = false)
    private Long durationMs;

    @Column(nullable = false, length = 45)
    private String clientIp;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(nullable = false, updatable = false)
    private UUID requestId;

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
