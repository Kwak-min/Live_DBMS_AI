package com.example.monitoring.service;

import com.example.monitoring.repository.MetricDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MetricRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T03:00:00Z");

    @Mock
    private MetricDataRepository metricDataRepository;

    private MetricRetentionService retentionService;

    @BeforeEach
    void setUp() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));
        retentionService = new MetricRetentionService(metricDataRepository, transactionTemplate);
        ReflectionTestUtils.setField(retentionService, "retentionDays", 30);
        ReflectionTestUtils.setField(retentionService, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Deletes snapshots older than 30 days (UTC) in batches until a short batch is returned")
    void purgeDeletesInBatches() {
        List<Long> fullBatch = LongStream.rangeClosed(1, MetricRetentionService.BATCH_SIZE).boxed().toList();
        List<Long> lastBatch = List.of(2001L, 2002L);
        given(metricDataRepository.findIdsOlderThan(any(Instant.class), any(Pageable.class)))
                .willReturn(fullBatch, lastBatch);

        int deleted = retentionService.purgeExpiredMetrics();

        assertThat(deleted).isEqualTo(MetricRetentionService.BATCH_SIZE + 2);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(metricDataRepository, times(2)).findIdsOlderThan(cutoff.capture(), any(Pageable.class));
        assertThat(cutoff.getAllValues()).containsOnly(Instant.parse("2026-08-30T03:00:00Z"));
        verify(metricDataRepository).deleteAllByIdInBatch(fullBatch);
        verify(metricDataRepository).deleteAllByIdInBatch(lastBatch);
    }

    @Test
    @DisplayName("Returns zero and deletes nothing when no snapshot is old enough")
    void purgeWithNothingExpired() {
        given(metricDataRepository.findIdsOlderThan(any(Instant.class), any(Pageable.class))).willReturn(List.of());

        assertThat(retentionService.purgeExpiredMetrics()).isZero();
        verify(metricDataRepository, never()).deleteAllByIdInBatch(eq(List.of()));
    }

    @Test
    @DisplayName("Retention days outside 1~365 fail at startup")
    void retentionDaysRange() {
        ReflectionTestUtils.setField(retentionService, "retentionDays", 0);
        assertThatThrownBy(retentionService::validateRetentionDays).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(retentionService, "retentionDays", 366);
        assertThatThrownBy(retentionService::validateRetentionDays).isInstanceOf(IllegalStateException.class);
    }
}
