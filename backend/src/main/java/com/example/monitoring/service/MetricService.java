package com.example.monitoring.service;

import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.ApiId;
import com.example.monitoring.common.api.FieldErrorResponse;
import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.dto.MetricResponseDto;
import com.example.monitoring.repository.MetricDataRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * 메트릭 조회 REST (docs/api.md 4절). 집계·다운샘플링 없이 원본 스냅샷을 반환한다.
 * <ul>
 *   <li>latest: 미삭제 대상의 현재 configVersion 최신 스냅샷. 대상 없음 404, 스냅샷 없음 empty(204)</li>
 *   <li>recent/history: 삭제된 대상의 보관 자료도 조회. 대상 레코드 자체가 없으면 404, 데이터 없음은 []</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MetricService {

    static final int DEFAULT_RECENT_LIMIT = 50;
    static final int MAX_RECENT_LIMIT = 1000;
    static final Duration MAX_HISTORY_RANGE = Duration.ofHours(24);
    static final int MAX_HISTORY_RESULTS = 20_000;

    private final MetricDataRepository metricDataRepository;
    private final TargetProvider targetProvider;
    private final EntityManager entityManager;

    public Optional<MetricResponseDto> getLatestMetric(Long dbId) {
        long id = ApiId.require(dbId, "dbId");
        TargetMetadata target = targetProvider.getMetadata(id).orElseThrow(MetricService::databaseNotFound);
        return metricDataRepository
                .findFirstByDatabaseConfigIdAndConfigVersionOrderByTimestampDescIdDesc(id, target.configVersion())
                .map(MetricResponseDto::fromEntity);
    }

    public List<MetricResponseDto> getRecentMetrics(Long dbId, Integer limit) {
        long id = ApiId.require(dbId, "dbId");
        int size = limit == null ? DEFAULT_RECENT_LIMIT : limit;
        if (size < 1 || size > MAX_RECENT_LIMIT) {
            throw invalidField("limit", "OUT_OF_RANGE", "limit은(는) 1~1000 범위여야 합니다.");
        }
        requireTargetRecord(id);
        return metricDataRepository.findRecentMetrics(id, PageRequest.of(0, size)).stream()
                .map(MetricResponseDto::fromEntity)
                .toList();
    }

    /** @param start 포함, @param end 미포함. UTC ISO 문자열(끝이 Z) */
    public List<MetricResponseDto> getMetricHistory(Long dbId, String start, String end) {
        long id = ApiId.require(dbId, "dbId");
        Instant from = parseUtc("start", start);
        Instant to = parseUtc("end", end);
        if (!from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "start는 end보다 이전이어야 합니다.");
        }
        if (Duration.between(from, to).compareTo(MAX_HISTORY_RANGE) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "조회 기간은 최대 24시간입니다.");
        }
        requireTargetRecord(id);
        if (metricDataRepository.countHistory(id, from, to) > MAX_HISTORY_RESULTS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESULT_LIMIT_EXCEEDED",
                    "결과가 20,000건을 넘습니다. 조회 기간을 줄여 주세요.");
        }
        return metricDataRepository.findHistory(id, from, to, PageRequest.of(0, MAX_HISTORY_RESULTS)).stream()
                .map(MetricResponseDto::fromEntity)
                .toList();
    }

    /** 삭제(soft delete)된 대상도 레코드가 있으면 통과한다. */
    private void requireTargetRecord(long id) {
        Long count = entityManager.createQuery(
                        "SELECT count(d) FROM DatabaseConfig d WHERE d.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult();
        if (count == 0) {
            throw databaseNotFound();
        }
    }

    private static Instant parseUtc(String field, String value) {
        if (value == null || value.isBlank()) {
            throw invalidField(field, "REQUIRED", field + "은(는) 필수입니다.");
        }
        if (!value.endsWith("Z")) {
            throw invalidField(field, "INVALID_FORMAT", field + "은(는) UTC 시각(예: 2026-09-28T03:00:00.000Z)이어야 합니다.");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalidField(field, "INVALID_FORMAT", field + "은(는) UTC 시각(예: 2026-09-28T03:00:00.000Z)이어야 합니다.");
        }
    }

    private static ApiException invalidField(String field, String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값을 확인해 주세요.",
                List.of(new FieldErrorResponse(field, code, message)));
    }

    private static ApiException databaseNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND", "DB 설정을 찾을 수 없습니다.");
    }
}
