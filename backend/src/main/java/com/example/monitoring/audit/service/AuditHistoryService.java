package com.example.monitoring.audit.service;

import com.example.monitoring.audit.dto.AccessLogResponse;
import com.example.monitoring.audit.dto.AuditEventResponse;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.common.api.FieldErrorResponse;
import com.example.monitoring.common.api.ApiId;
import com.example.monitoring.domain.*;
import com.example.monitoring.repository.AuditEventRepository;
import com.example.monitoring.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditHistoryService {
    private final AuditEventRepository auditEventRepository;
    private final AuditLogRepository accessLogRepository;

    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> audits(Long actorId, Long databaseConfigId, AuditAction action,
                                                   AuditResult result, Instant start, Instant end, int page, int size) {
        ApiId.validateOptional(actorId, "actorId");
        ApiId.validateOptional(databaseConfigId, "databaseConfigId");
        TimeRange range = range(start, end, page, size);
        Specification<AuditEvent> spec = Specification.where(null);
        if (actorId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("actorId"), actorId));
        if (databaseConfigId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("databaseConfigId"), databaseConfigId));
        if (action != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("action"), action));
        if (result != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("result"), result));
        spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), range.start()))
                .and((root, q, cb) -> cb.lessThan(root.get("occurredAt"), range.end()));
        return PageResponse.from(auditEventRepository.findAll(spec, pageable(page, size)), AuditEventResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessLogResponse> access(Long actorId, Instant start, Instant end, int page, int size) {
        ApiId.validateOptional(actorId, "actorId");
        TimeRange range = range(start, end, page, size);
        Specification<AuditLog> spec = Specification.<AuditLog>where((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), range.start()))
                .and((root, q, cb) -> cb.lessThan(root.get("occurredAt"), range.end()));
        if (actorId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("actorId"), actorId));
        return PageResponse.from(accessLogRepository.findAll(spec, pageable(page, size)), AccessLogResponse::from);
    }

    private PageRequest pageable(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
    }

    private TimeRange range(Instant start, Instant end, int page, int size) {
        if (page < 0 || page > 10_000 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값을 확인해 주세요.",
                    List.of(new FieldErrorResponse("page", "OUT_OF_RANGE", "page는 0~10000, size는 1~100 범위여야 합니다.")));
        }
        Instant actualEnd = end == null ? Instant.now() : end;
        Instant actualStart = start == null ? actualEnd.minus(Duration.ofHours(24)) : start;
        if (!actualStart.isBefore(actualEnd) || Duration.between(actualStart, actualEnd).compareTo(Duration.ofDays(30)) > 0) {
            throw invalid("조회 기간은 30일 이하이며 start < end여야 합니다.");
        }
        return new TimeRange(actualStart, actualEnd);
    }

    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", message); }
    private record TimeRange(Instant start, Instant end) { }
}
