package com.example.monitoring.service;

import com.example.monitoring.repository.MetricDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MetricRetentionServiceTest {

    @Mock
    private MetricDataRepository metricDataRepository;

    private MetricRetentionService retentionService;

    @BeforeEach
    void setUp() {
        retentionService = new MetricRetentionService(metricDataRepository);
        ReflectionTestUtils.setField(retentionService, "retentionDays", 30);
    }

    @Test
    @DisplayName("Deletes metrics older than the configured retention window and returns the deleted count")
    void purgeExpiredMetrics_deletesOlderThanCutoff() {
        given(metricDataRepository.deleteByTimestampBefore(any(LocalDateTime.class))).willReturn(5);

        int deleted = retentionService.purgeExpiredMetrics();

        assertEquals(5, deleted);

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(metricDataRepository).deleteByTimestampBefore(cutoffCaptor.capture());

        LocalDateTime expectedCutoff = LocalDateTime.now().minusDays(30);
        long secondsDiff = Math.abs(java.time.Duration.between(expectedCutoff, cutoffCaptor.getValue()).getSeconds());
        assertEquals(true, secondsDiff < 5, "Cutoff should be approximately 30 days before now");
    }

    @Test
    @DisplayName("Returns zero when no metrics are old enough to purge")
    void purgeExpiredMetrics_noExpiredData_returnsZero() {
        given(metricDataRepository.deleteByTimestampBefore(any(LocalDateTime.class))).willReturn(0);

        int deleted = retentionService.purgeExpiredMetrics();

        assertEquals(0, deleted);
    }
}
