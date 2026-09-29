package com.example.monitoring.scheduler;

import com.example.monitoring.repository.BlockedReasonRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlockedReasonRetentionSchedulerTest {

    private final BlockedReasonRepository repository = mock(BlockedReasonRepository.class);
    private final BlockedReasonRetentionScheduler scheduler = new BlockedReasonRetentionScheduler(repository);

    @Test
    @DisplayName("Deletes legacy blocked reasons older than 180 days in server local time")
    void deletesOlderThan180Days() {
        ReflectionTestUtils.setField(scheduler, "clock",
                Clock.fixed(Instant.parse("2026-09-29T03:10:00Z"), ZoneId.of("Asia/Seoul")));

        scheduler.purgeExpired();

        verify(repository).deleteBlockedBefore(LocalDateTime.parse("2026-04-02T12:10:00"));
    }

    @Test
    @DisplayName("A database failure is logged, not thrown")
    void failureIsContained() {
        when(repository.deleteBlockedBefore(any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(scheduler::purgeExpired).doesNotThrowAnyException();
    }
}
