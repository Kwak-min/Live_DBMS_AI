package com.example.monitoring.service;

import com.example.monitoring.collector.MariaDbHealthChecker;
import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.dto.DbPingResponseDto;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.ApiId;
import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.domain.AuditTargetType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DatabaseHealthService {

    private final TargetProvider targetProvider;
    private final MariaDbHealthChecker mariaDbHealthChecker;
    private final StringRedisTemplate redisTemplate;
    private final AuditEventService auditEventService;

    @Transactional
    public Optional<DbPingResponseDto> pingDatabase(Long databaseConfigId) {
        ApiId.require(databaseConfigId, "id");
        Optional<CollectorTarget> target = targetProvider.getForDiagnostic(databaseConfigId);
        if (target.isEmpty()) return Optional.empty();
        enforceRateLimit(databaseConfigId);
        return target.map(value -> {
            DbPingResponseDto response = mariaDbHealthChecker.pingAndFetchVersion(value);
            auditEventService.successCurrent(AuditAction.DATABASE_PING, AuditTargetType.DATABASE,
                    databaseConfigId.toString(), databaseConfigId, "Database diagnostic ping executed");
            return response;
        });
    }

    private void enforceRateLimit(Long databaseConfigId) {
        try {
            Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                    "rate-limit:database-ping:" + databaseConfigId, "1", Duration.ofSeconds(10));
            if (!Boolean.TRUE.equals(accepted)) {
                Long ttl = redisTemplate.getExpire("rate-limit:database-ping:" + databaseConfigId);
                long retryAfter = ttl == null || ttl < 1 ? 1 : ttl;
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "대상별 Ping은 10초에 한 번만 실행할 수 있습니다.", List.of(),
                        Map.of("Retry-After", Long.toString(retryAfter)));
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "Ping 제한 저장소를 사용할 수 없습니다.");
        }
    }
}
