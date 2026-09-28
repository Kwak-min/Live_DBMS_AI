package com.example.monitoring.service;

import com.example.monitoring.dto.MetricResponseDto;
import com.example.monitoring.repository.MetricDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MetricService {

    private final MetricDataRepository metricDataRepository;

    public Optional<MetricResponseDto> getLatestMetric(Long databaseConfigId) {
        return metricDataRepository.findFirstByDatabaseConfigIdOrderByTimestampDesc(databaseConfigId)
                .map(MetricResponseDto::fromEntity);
    }

    public List<MetricResponseDto> getMetricHistory(Long databaseConfigId, LocalDateTime start, LocalDateTime end) {
        return metricDataRepository.findByDatabaseConfigIdAndTimestampBetweenOrderByTimestampAsc(databaseConfigId, start, end)
                .stream()
                .map(MetricResponseDto::fromEntity)
                .toList();
    }

    public List<MetricResponseDto> getRecentMetrics(Long databaseConfigId, int limit) {
        return metricDataRepository.findRecentMetrics(databaseConfigId, PageRequest.of(0, limit))
                .stream()
                .map(MetricResponseDto::fromEntity)
                .toList();
    }
}
