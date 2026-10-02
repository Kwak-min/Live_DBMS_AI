package com.example.monitoring;

import com.example.monitoring.database.dto.DatabaseCreateRequest;
import com.example.monitoring.database.dto.DatabaseUpdateRequest;
import com.example.monitoring.database.security.TargetAddressPolicy;
import com.example.monitoring.database.service.DatabaseConfigService;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.lifecycle.port.MonitoringLifecyclePort;
import com.example.monitoring.repository.AuditEventRepository;
import com.example.monitoring.repository.DatabaseConfigRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.collector.enabled=false",
        "app.metrics.retention-cleanup-enabled=false",
        "app.part-b.retention-cleanup-enabled=false",
        "app.outbox.publisher-enabled=false",
        "app.database-security.verify-on-startup=false"
})
@ActiveProfiles("local")
class DatabaseLifecycleRollbackTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String DB_KEYS = "{\"1\":\"" + KEY + "\"}";
    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            System.setProperty("DB_CONFIG_ACTIVE_KEY_VERSION", "1");
            System.setProperty("DB_CONFIG_ENCRYPTION_KEYS", DB_KEYS);
            System.setProperty("LEGACY_TIME_ZONE", "Asia/Seoul");
            POSTGRES = EmbeddedPostgres.start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("app.auth.jwt-signing-keys", () -> "{\"test\":\"" + KEY + "\"}");
        registry.add("app.auth.jwt-active-kid", () -> "test");
        registry.add("app.database-security.encryption-keys", () -> DB_KEYS);
        registry.add("app.database-security.active-key-version", () -> "1");
    }

    @AfterAll
    static void closePostgres() throws Exception {
        POSTGRES.close();
        System.clearProperty("DB_CONFIG_ACTIVE_KEY_VERSION");
        System.clearProperty("DB_CONFIG_ENCRYPTION_KEYS");
        System.clearProperty("LEGACY_TIME_ZONE");
    }

    @Autowired private DatabaseConfigService service;
    @Autowired private DatabaseConfigRepository repository;
    @Autowired private AuditEventRepository auditRepository;
    @Autowired private EntityManager entityManager;
    @MockBean private MonitoringLifecyclePort lifecyclePort;
    @MockBean private TargetAddressPolicy targetAddressPolicy;

    @Test
    void lifecycleFailureRollsBackDatabaseCreateAndAudit() {
        long targetCount = repository.count();
        long auditCount = auditRepository.count();
        doThrow(new IllegalStateException("C failed")).when(lifecyclePort).applyChange(any());

        assertThatThrownBy(() -> service.create(new DatabaseCreateRequest(
                "Created", "db.example.test", 3306, "monitoring", "user", "secret", true)))
                .isInstanceOf(IllegalStateException.class).hasMessage("C failed");
        entityManager.clear();

        assertThat(repository.count()).isEqualTo(targetCount);
        assertThat(auditRepository.count()).isEqualTo(auditCount);
        verify(lifecyclePort).applyChange(any());
    }

    @Test
    void lifecycleFailureRollsBackDatabaseUpdateAndAudit() {
        long id = persistTarget("Original");
        long auditCount = auditRepository.count();
        DatabaseUpdateRequest request = new DatabaseUpdateRequest();
        request.setConfigVersion(2L);
        request.setName("Changed");
        doThrow(new IllegalStateException("C failed")).when(lifecyclePort).applyChange(any());

        assertThatThrownBy(() -> service.update(id, request))
                .isInstanceOf(IllegalStateException.class).hasMessage("C failed");
        entityManager.clear();

        DatabaseConfig after = repository.findById(id).orElseThrow();
        assertThat(after.getName()).isEqualTo("Original");
        assertThat(after.getConfigVersion()).isEqualTo(2L);
        assertThat(auditRepository.count()).isEqualTo(auditCount);
        verify(lifecyclePort).applyChange(any());
    }

    @Test
    void lifecycleFailureRollsBackDatabaseDeleteAndAudit() {
        long id = persistTarget("Delete me");
        long auditCount = auditRepository.count();
        doThrow(new IllegalStateException("C failed")).when(lifecyclePort).applyChange(any());

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(IllegalStateException.class).hasMessage("C failed");
        entityManager.clear();

        DatabaseConfig after = repository.findById(id).orElseThrow();
        assertThat(after.getEnabled()).isTrue();
        assertThat(after.getDeletedAt()).isNull();
        assertThat(after.getConfigVersion()).isEqualTo(2L);
        assertThat(auditRepository.count()).isEqualTo(auditCount);
        verify(lifecyclePort).applyChange(any());
    }

    private long persistTarget(String name) {
        DatabaseConfig config = DatabaseConfig.builder()
                .name(name).host("db.example.test").port(3306)
                .databaseName("monitoring").enabled(true).configVersion(2L).build();
        config.storeEncryptedUsername(1, new byte[12], new byte[16]);
        config.storeEncryptedPassword(1, new byte[12], new byte[16]);
        return repository.saveAndFlush(config).getId();
    }
}
