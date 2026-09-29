package com.example.monitoring.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 발행 대기/완료 이벤트. payload는 공통 필드가 포함된 최종 이벤트 JSON이며 재발행해도 바뀌지 않는다.
 */
@Entity
@Table(name = "event_outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    private UUID eventId;

    /** 생성 순서. DB sequence가 채우며 발행 순서 기준으로만 쓴다. */
    @Column(insertable = false, updatable = false)
    private Long seq;

    @Column(nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, length = 128)
    private String streamKey;

    /** 같은 키의 이벤트는 앞 이벤트가 발행될 때까지 뒤 이벤트를 발행하지 않는다. null이면 순서 제약 없음. */
    @Column(length = 128)
    private String orderingKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(length = 500)
    private String lastError;
}
