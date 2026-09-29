package com.example.monitoring.metric;

import com.example.monitoring.dto.MetricResponseDto;
import com.example.monitoring.repository.MetricDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 파트 간 내부 조회 포트 (A 제공, C 사용). C가 리플레이 복구·최신 상태 검증에 사용한다.
 * REST와 달리 인증·존재 검사를 하지 않으며 호출자의 트랜잭션이 있으면 그 안에서 읽는다.
 */
@Service
@RequiredArgsConstructor
public class MetricQueryService {

    private final MetricDataRepository metricDataRepository;

    /** @return 해당 설정 버전의 최신 Metric. 없으면 empty */
    @Transactional(readOnly = true)
    public Optional<MetricResponseDto> latest(long databaseConfigId, long configVersion) {
        return metricDataRepository
                .findFirstByDatabaseConfigIdAndConfigVersionOrderByTimestampDescIdDesc(databaseConfigId, configVersion)
                .map(MetricResponseDto::fromEntity);
    }
}
