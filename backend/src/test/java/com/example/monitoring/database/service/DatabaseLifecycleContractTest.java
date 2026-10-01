package com.example.monitoring.database.service;

import com.example.monitoring.common.persistence.PartBTransactionLocks;
import com.example.monitoring.common.web.AuditRequestContext;
import com.example.monitoring.database.dto.DatabaseCreateRequest;
import com.example.monitoring.database.dto.DatabaseUpdateRequest;
import com.example.monitoring.database.security.DatabaseCredentialCrypto;
import com.example.monitoring.database.security.EncryptedValue;
import com.example.monitoring.database.security.TargetAddressPolicy;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.lifecycle.port.MonitoringLifecyclePort;
import com.example.monitoring.lifecycle.port.TargetChange;
import com.example.monitoring.lifecycle.port.TargetChangeType;
import com.example.monitoring.repository.DatabaseConfigRepository;
import com.example.monitoring.service.AuditEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseLifecycleContractTest {
    @Mock private DatabaseConfigRepository repository;
    @Mock private DatabaseCredentialCrypto crypto;
    @Mock private AuditEventService audit;
    @Mock private TargetAddressPolicy addressPolicy;
    @Mock private PartBTransactionLocks locks;
    @Mock private AuditRequestContext context;
    @Mock private MonitoringLifecyclePort port;

    private DatabaseConfigService service;
    private UUID requestId;

    @BeforeEach
    void setUp() {
        service = new DatabaseConfigService(repository, crypto, audit, addressPolicy, locks, context, port);
        requestId = UUID.randomUUID();
        lenient().when(context.current()).thenReturn(new AuditRequestContext.Details(7L, "127.0.0.1", requestId));
    }

    @Test
    void createEmitsCreatedEvenWhenInitiallyDisabled() {
        when(repository.countByDeletedAtIsNull()).thenReturn(0L);
        when(crypto.encrypt(anyLong(), anyString(), anyString()))
                .thenReturn(new EncryptedValue(1, new byte[12], new byte[16]));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            DatabaseConfig config = invocation.getArgument(0);
            config.setId(11L);
            return config;
        });

        service.create(new DatabaseCreateRequest(" Test DB ", "db.example.test", 3306,
                "monitoring", "user", "secret", false));

        TargetChange change = capturedChange();
        assertThat(change.databaseConfigId()).isEqualTo(11L);
        assertThat(change.configVersion()).isEqualTo(1L);
        assertThat(change.changeType()).isEqualTo(TargetChangeType.CREATED);
        assertThat(change.enabled()).isFalse();
        assertThat(change.name()).isEqualTo("Test DB");
        assertContext(change);
    }

    @Test
    void updateMapsOrdinaryChangeAndEnabledTransitions() {
        assertUpdate(true, true, TargetChangeType.UPDATED);
        assertUpdate(true, false, TargetChangeType.PAUSED);
        assertUpdate(false, true, TargetChangeType.RESUMED);
    }

    @Test
    void deleteEmitsTombstoneWithIncrementedVersion() {
        DatabaseConfig config = config(true);
        when(repository.findActiveByIdForUpdate(11L)).thenReturn(Optional.of(config));
        when(repository.saveAndFlush(config)).thenReturn(config);

        service.delete(11L);

        TargetChange change = capturedChange();
        assertThat(change.changeType()).isEqualTo(TargetChangeType.DELETED);
        assertThat(change.enabled()).isFalse();
        assertThat(change.configVersion()).isEqualTo(3L);
        assertThat(config.getDeletedAt()).isNotNull();
        assertContext(change);
    }

    @Test
    void lifecycleFailurePropagatesToTransactionCaller() {
        DatabaseConfig config = config(true);
        when(repository.findActiveByIdForUpdate(11L)).thenReturn(Optional.of(config));
        when(repository.saveAndFlush(config)).thenReturn(config);
        doThrow(new IllegalStateException("C failed")).when(port).applyChange(any());
        DatabaseUpdateRequest request = new DatabaseUpdateRequest();
        request.setConfigVersion(2L);
        request.setName("New name");

        assertThatThrownBy(() -> service.update(11L, request))
                .isInstanceOf(IllegalStateException.class).hasMessage("C failed");
        verify(port).applyChange(any());
    }

    private void assertUpdate(boolean before, boolean after, TargetChangeType type) {
        DatabaseConfig config = config(before);
        when(repository.findActiveByIdForUpdate(11L)).thenReturn(Optional.of(config));
        when(repository.saveAndFlush(config)).thenReturn(config);
        DatabaseUpdateRequest request = new DatabaseUpdateRequest();
        request.setConfigVersion(2L);
        request.setEnabled(after);

        service.update(11L, request);

        TargetChange change = capturedChange();
        assertThat(change.changeType()).isEqualTo(type);
        assertThat(change.enabled()).isEqualTo(after);
        assertThat(change.configVersion()).isEqualTo(3L);
        assertContext(change);
        clearInvocations(port, repository, audit);
    }

    private TargetChange capturedChange() {
        ArgumentCaptor<TargetChange> captor = ArgumentCaptor.forClass(TargetChange.class);
        InOrder ordered = inOrder(audit, port);
        ordered.verify(audit).success(any(), any(), any(), anyString(), any(), anyString(), any(), anyString());
        ordered.verify(port).applyChange(captor.capture());
        verifyNoMoreInteractions(port);
        return captor.getValue();
    }

    private void assertContext(TargetChange change) {
        assertThat(change.actorId()).isEqualTo(7L);
        assertThat(change.requestId()).isEqualTo(requestId);
        assertThat(change.occurredAt()).isNotNull();
    }

    private DatabaseConfig config(boolean enabled) {
        return DatabaseConfig.builder().id(11L).name("Test DB").host("db.example.test")
                .port(3306).enabled(enabled).configVersion(2L).build();
    }
}
