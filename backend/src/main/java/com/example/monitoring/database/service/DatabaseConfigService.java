package com.example.monitoring.database.service;

import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.FieldErrorResponse;
import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.common.api.ApiId;
import com.example.monitoring.common.web.AuditRequestContext;
import com.example.monitoring.database.dto.DatabaseCreateRequest;
import com.example.monitoring.database.dto.DatabaseResponse;
import com.example.monitoring.database.dto.DatabaseUpdateRequest;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.repository.DatabaseConfigRepository;
import com.example.monitoring.database.security.DatabaseCredentialCrypto;
import com.example.monitoring.database.security.EncryptedValue;
import com.example.monitoring.database.security.TargetAddressPolicy;
import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.domain.AuditTargetType;
import com.example.monitoring.service.AuditEventService;
import com.example.monitoring.common.persistence.PartBTransactionLocks;
import com.example.monitoring.lifecycle.port.MonitoringLifecyclePort;
import com.example.monitoring.lifecycle.port.TargetChange;
import com.example.monitoring.lifecycle.port.TargetChangeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DatabaseConfigService {

    private static final long MAX_ACTIVE_DATABASES = 20L;
    private final DatabaseConfigRepository databaseConfigRepository;
    private final DatabaseCredentialCrypto credentialCrypto;
    private final AuditEventService auditEventService;
    private final TargetAddressPolicy targetAddressPolicy;
    private final PartBTransactionLocks transactionLocks;
    private final AuditRequestContext auditRequestContext;
    private final MonitoringLifecyclePort lifecyclePort;

    @Transactional
    public DatabaseResponse create(DatabaseCreateRequest request) {
        transactionLocks.lockDatabaseQuota();
        if (databaseConfigRepository.countByDeletedAtIsNull() >= MAX_ACTIVE_DATABASES) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_LIMIT_EXCEEDED", "등록 가능한 DB는 최대 20개입니다.");
        }
        String validatedUsername = username(request.username());
        String validatedPassword = password(request.password());
        String validatedHost = host(request.host());
        Integer validatedPort = port(request.port());
        targetAddressPolicy.resolveAndValidate(validatedHost, validatedPort);
        DatabaseConfig config = DatabaseConfig.builder()
                .name(name(request.name()))
                .host(validatedHost)
                .port(validatedPort)
                .databaseName(databaseName(request.databaseName()))
                .enabled(request.enabled() == null || request.enabled())
                .configVersion(1L)
                .status(TargetDbStatus.UNKNOWN)
                .collectionIntervalSeconds(5)
                .build();
        config = databaseConfigRepository.saveAndFlush(config);
        storeUsername(config, validatedUsername);
        storePassword(config, validatedPassword);
        DatabaseConfig saved = databaseConfigRepository.saveAndFlush(config);
        recordChange(saved, TargetChangeType.CREATED, AuditAction.DATABASE_CREATED,
                "Database configuration created");
        return DatabaseResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<DatabaseResponse> list(int page, int size, Boolean enabled) {
        validatePage(page, size);
        Page<DatabaseConfig> result = enabled == null
                ? databaseConfigRepository.findByDeletedAtIsNullOrderByIdAsc(PageRequest.of(page, size))
                : databaseConfigRepository.findByDeletedAtIsNullAndEnabledOrderByIdAsc(enabled, PageRequest.of(page, size));
        return PageResponse.from(result, DatabaseResponse::from);
    }

    @Transactional(readOnly = true)
    public DatabaseResponse get(Long id) {
        ApiId.require(id, "id");
        return DatabaseResponse.from(findActive(id));
    }

    @Transactional
    public DatabaseResponse update(Long id, DatabaseUpdateRequest request) {
        ApiId.require(id, "id");
        ApiId.require(request.getConfigVersion(), "configVersion");
        if (!request.hasChanges()) {
            throw validation("request", "INVALID_VALUE", "변경할 설정을 하나 이상 지정해야 합니다.");
        }
        DatabaseConfig config = findActiveForUpdate(id);
        if (!request.getConfigVersion().equals(config.getConfigVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIG_VERSION_CONFLICT", "DB 설정이 이미 변경되었습니다.");
        }
        boolean wasEnabled = Boolean.TRUE.equals(config.getEnabled());
        if (request.isNameSpecified()) config.setName(name(request.getName()));
        String nextHost = request.isHostSpecified() ? host(request.getHost()) : config.getHost();
        Integer nextPort = request.isPortSpecified() ? port(request.getPort()) : config.getPort();
        if (request.isHostSpecified() || request.isPortSpecified()) {
            targetAddressPolicy.resolveAndValidate(nextHost, nextPort);
            config.setHost(nextHost);
            config.setPort(nextPort);
        }
        if (request.isDatabaseNameSpecified()) config.setDatabaseName(databaseName(request.getDatabaseName()));
        if (request.isUsernameSpecified()) storeUsername(config, username(request.getUsername()));
        if (request.isPasswordSpecified()) storePassword(config, password(request.getPassword()));
        if (request.isEnabledSpecified()) {
            if (request.getEnabled() == null) throw validation("enabled", "REQUIRED", "enabled는 boolean이어야 합니다.");
            config.setEnabled(request.getEnabled());
        }
        config.setConfigVersion(config.getConfigVersion() + 1);
        config.setLastCheckedAt(null);
        config.setLastSuccessAt(null);
        config.setLastErrorMessage(null);
        config.setStatus(TargetDbStatus.UNKNOWN);
        DatabaseConfig saved = databaseConfigRepository.saveAndFlush(config);
        TargetChangeType changeType = wasEnabled == Boolean.TRUE.equals(saved.getEnabled())
                ? TargetChangeType.UPDATED
                : (Boolean.TRUE.equals(saved.getEnabled()) ? TargetChangeType.RESUMED : TargetChangeType.PAUSED);
        recordChange(saved, changeType, AuditAction.DATABASE_UPDATED,
                "Database configuration updated");
        return DatabaseResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        ApiId.require(id, "id");
        databaseConfigRepository.findActiveByIdForUpdate(id).ifPresent(config -> {
            config.setEnabled(false);
            config.setDeletedAt(Instant.now());
            config.setConfigVersion(config.getConfigVersion() + 1);
            DatabaseConfig saved = databaseConfigRepository.saveAndFlush(config);
            recordChange(saved, TargetChangeType.DELETED, AuditAction.DATABASE_DELETED,
                    "Database configuration deleted");
        });
    }

    private void recordChange(DatabaseConfig config, TargetChangeType changeType,
                              AuditAction action, String summary) {
        AuditRequestContext.Details context = auditRequestContext.current();
        auditEventService.success(context.actorId(), action, AuditTargetType.DATABASE,
                config.getId().toString(), config.getId(), context.clientIp(), context.requestId(), summary);
        lifecyclePort.applyChange(new TargetChange(ApiId.require(config.getId(), "id"),
                ApiId.require(config.getConfigVersion(), "configVersion"), changeType,
                Boolean.TRUE.equals(config.getEnabled()), config.getName(), Instant.now(),
                context.actorId(), context.requestId()));
    }

    private DatabaseConfig findActive(Long id) {
        return databaseConfigRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND", "DB 설정을 찾을 수 없습니다."));
    }

    private DatabaseConfig findActiveForUpdate(Long id) {
        return databaseConfigRepository.findActiveByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND", "DB 설정을 찾을 수 없습니다."));
    }

    private void validatePage(int page, int size) {
        if (page < 0 || page > 10_000 || size < 1 || size > 100) {
            throw validation("page", "OUT_OF_RANGE", "page는 0~10000, size는 1~100 범위여야 합니다.");
        }
    }

    private String name(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().codePointCount(0, value.trim().length()) > 100) {
            throw validation("name", "INVALID_VALUE", "name은 1~100자여야 합니다.");
        }
        return value.trim();
    }

    private String host(String value) {
        if (value == null || value.isBlank() || value.length() > 253 || value.matches(".*\\s.*")
                || value.contains("://") || value.contains("/") || value.contains("?") || value.contains("#")) {
            throw validation("host", "INVALID_FORMAT", "host 형식이 올바르지 않습니다.");
        }
        return value;
    }

    private Integer port(Integer value) {
        if (value == null || value < 1 || value > 65535) {
            throw validation("port", "OUT_OF_RANGE", "port는 1~65535 범위여야 합니다.");
        }
        return value;
    }

    private String databaseName(String value) {
        if (value == null) return null;
        if (value.isEmpty() || value.length() > 100 || !value.matches("[A-Za-z0-9_$-]+")) {
            throw validation("databaseName", "INVALID_FORMAT", "databaseName 형식이 올바르지 않습니다.");
        }
        return value;
    }

    private String username(String value) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 100) {
            throw validation("username", "INVALID_VALUE", "username은 1~100자여야 합니다.");
        }
        return value;
    }

    private String password(String value) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw validation("password", "INVALID_VALUE", "password는 UTF-8 기준 1~4096바이트여야 합니다.");
        }
        return value;
    }

    private void storeUsername(DatabaseConfig config, String plaintext) {
        EncryptedValue encrypted = credentialCrypto.encrypt(config.getId(), "username", plaintext);
        config.storeEncryptedUsername(encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext());
    }

    private void storePassword(DatabaseConfig config, String plaintext) {
        EncryptedValue encrypted = credentialCrypto.encrypt(config.getId(), "password", plaintext);
        config.storeEncryptedPassword(encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext());
    }

    private ApiException validation(String field, String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값을 확인해 주세요.",
                List.of(new FieldErrorResponse(field, code, message)));
    }
}
