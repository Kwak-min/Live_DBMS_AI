package com.example.monitoring.common.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 소비자 중복 처리 기록 (stream, consumer_group, eventId). 업무 저장과 같은 트랜잭션에서 호출하고
 * 커밋된 뒤에 XACK한다. 이미 처리한 이벤트면 false를 반환하므로 업무 처리를 건너뛰고 ACK만 한다.
 */
@Repository
@RequiredArgsConstructor
public class ProcessedEventStore {

    private final JdbcTemplate jdbcTemplate;

    private Clock clock = Clock.systemUTC();

    /** @return 처음 처리하는 이벤트면 true, 이미 기록된 이벤트면 false */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String stream, String consumerGroup, UUID eventId) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO processed_events (stream, consumer_group, event_id, processed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, stream, consumerGroup, eventId, Timestamp.from(clock.instant()));
        return inserted == 1;
    }

    @Transactional
    public int deleteProcessedBefore(Instant cutoff) {
        return jdbcTemplate.update("DELETE FROM processed_events WHERE processed_at < ?", Timestamp.from(cutoff));
    }
}
