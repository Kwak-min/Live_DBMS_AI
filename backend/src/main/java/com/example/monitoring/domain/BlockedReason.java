package com.example.monitoring.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * DB 프로젝트 차단 이력 엔티티.
 * 자동(FATAL 인시던트) 또는 수동 조작으로 발생한 차단 사유와 해제 이력을 보존합니다.
 */
@Entity
@Table(name = "blocked_reasons",
        indexes = {
                @Index(name = "idx_blocked_db_config_id", columnList = "database_config_id"),
                @Index(name = "idx_blocked_at", columnList = "blocked_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockedReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 차단 대상 DatabaseConfig ID (FK 대신 ID만 보관) */
    @Column(name = "database_config_id", nullable = false)
    private Long databaseConfigId;

    /** 차단 트리거가 된 인시던트 ID (자동 차단인 경우). 수동 차단이면 null */
    @Column(name = "incident_id", length = 64)
    private String incidentId;

    /** 차단을 유발한 심각도 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RiskSeverity severity;

    /** 차단 유형: AUTO (자동) | MANUAL (수동) */
    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false, length = 10)
    @Builder.Default
    private BlockType blockType = BlockType.AUTO;

    /** 차단 사유 메시지 */
    @Column(nullable = false, length = 500)
    private String reason;

    /** 차단한 주체 (자동 차단이면 "SYSTEM", 수동이면 담당자명) */
    @Column(name = "blocked_by", nullable = false, length = 100)
    @Builder.Default
    private String blockedBy = "SYSTEM";

    /** 차단 시각 */
    @Column(name = "blocked_at", nullable = false, updatable = false)
    private LocalDateTime blockedAt;

    /** 해제 시각 (아직 차단 중이면 null) */
    @Column(name = "unblocked_at")
    private LocalDateTime unblockedAt;

    /** 해제 승인자 */
    @Column(name = "unblocked_by", length = 100)
    private String unblockedBy;

    @PrePersist
    protected void onCreate() {
        if (blockedAt == null) {
            blockedAt = LocalDateTime.now();
        }
    }

    /** 현재 차단 중(해제되지 않음) 여부 */
    public boolean isActive() {
        return unblockedAt == null;
    }

    public enum BlockType {
        AUTO,
        MANUAL
    }
}
