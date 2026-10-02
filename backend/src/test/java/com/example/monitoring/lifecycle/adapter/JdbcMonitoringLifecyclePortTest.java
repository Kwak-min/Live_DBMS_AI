package com.example.monitoring.lifecycle.adapter;

import com.example.monitoring.common.outbox.OutboxEventRepository;
import com.example.monitoring.common.outbox.OutboxEventType;
import com.example.monitoring.common.outbox.OutboxWriter;
import com.example.monitoring.lifecycle.port.TargetChange;
import com.example.monitoring.lifecycle.port.TargetChangeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdbcMonitoringLifecyclePortTest {

    private static final long TARGET_ID = 12L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-29T06:07:08.123456789Z");
    private static final Instant NORMALIZED_AT = Instant.parse("2026-09-29T06:07:08.123Z");

    private LifecycleJdbcStore store;
    private LifecycleEventCodec events;
    private LifecycleOutboxWriter outbox;
    private ImmediateTransactionOperations transaction;
    private JdbcMonitoringLifecyclePort port;

    @BeforeEach
    void setUp() {
        store = mock(LifecycleJdbcStore.class);
        events = mock(LifecycleEventCodec.class);
        outbox = mock(LifecycleOutboxWriter.class);
        transaction = new ImmediateTransactionOperations();
        port = new JdbcMonitoringLifecyclePort(store, events, outbox, transaction);
    }

    @AfterEach
    void clearTransactionContext() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void constructorPerformsNoJdbcOrTransactionManagerWork() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        OutboxWriter commonWriter = mock(OutboxWriter.class);
        OutboxEventRepository outboxEvents = mock(OutboxEventRepository.class);

        new JdbcMonitoringLifecyclePort(
                jdbc, new ObjectMapper(), transactionManager, commonWriter, outboxEvents);

        verifyNoInteractions(jdbc, transactionManager, commonWriter, outboxEvents);
    }

    @Test
    void rejectsCallWithoutWritableTransactionBeforeJdbc() {
        assertThatThrownBy(() -> port.applyChange(change(TargetChangeType.CREATED, 1L, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Monitoring lifecycle requires an active writable transaction");

        assertThat(transaction.calls).isZero();
        verifyNoInteractions(store, events, outbox);
    }

    @Test
    void rejectsReadOnlyTransactionInsideMandatoryBoundaryBeforeJdbc() {
        bindTransaction(true);

        assertThatThrownBy(() -> port.applyChange(change(TargetChangeType.CREATED, 1L, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Monitoring lifecycle requires an active writable transaction");

        assertThat(transaction.calls).isOne();
        verifyNoInteractions(store, events, outbox);
    }

    @Test
    void mandatoryTemplateJoinsOnlyAnExistingTransactionAndRollsBackFailure() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        TransactionTemplate template = JdbcMonitoringLifecyclePort.mandatoryTransaction(manager);

        assertThat(template.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_MANDATORY);
        assertThatThrownBy(() -> template.executeWithoutResult(ignored -> {
            throw new IllegalStateException("outbox failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("outbox failed");
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @ParameterizedTest
    @MethodSource("createStates")
    void createWritesExactInitialStateAndDefaultPolicy(boolean enabled, String freshness, Instant activationAt) {
        bindTransaction(false);
        TargetChange change = change(TargetChangeType.CREATED, 1L, enabled);
        PreparedLifecycleEvent statusEvent = event(OutboxEventType.MONITORING_STATUS_CHANGED, 1);
        when(store.lockTarget(TARGET_ID)).thenReturn(Optional.of(target(change)));
        when(store.lockState(TARGET_ID)).thenReturn(Optional.empty());
        when(events.defaultPolicyJson()).thenReturn("[{\"ruleId\":\"CONNECTION_RATIO\"}]");
        when(events.statusChanged(any(MonitoringStateWrite.class))).thenReturn(statusEvent);

        port.applyChange(change);

        var stateCaptor = org.mockito.ArgumentCaptor.forClass(MonitoringStateWrite.class);
        verify(store).insertState(stateCaptor.capture());
        MonitoringStateWrite state = stateCaptor.getValue();
        assertThat(state.databaseConfigId()).isEqualTo(TARGET_ID);
        assertThat(state.configVersion()).isOne();
        assertThat(state.stateVersion()).isOne();
        assertThat(state.enabled()).isEqualTo(enabled);
        assertThat(state.deleted()).isFalse();
        assertThat(state.dataFreshness()).isEqualTo(freshness);
        assertThat(state.activationAt()).isEqualTo(activationAt);
        assertThat(state.updatedAt()).isEqualTo(NORMALIZED_AT);

        var ordered = inOrder(store, events, outbox);
        ordered.verify(store).lockTarget(TARGET_ID);
        ordered.verify(store).lockState(TARGET_ID);
        ordered.verify(events).defaultPolicyJson();
        ordered.verify(events).statusChanged(state);
        ordered.verify(store).insertState(state);
        ordered.verify(store).insertDefaultPolicy(TARGET_ID, "[{\"ruleId\":\"CONNECTION_RATIO\"}]", NORMALIZED_AT);
        ordered.verify(outbox).append(statusEvent);
        verify(store, never()).lockOpenIncidents(any(Long.class));
    }

    @ParameterizedTest
    @MethodSource("existingTransitions")
    void existingTransitionsResetStateResolveIncidentAndEmitResolutionBeforeStatus(TransitionCase testCase) {
        bindTransaction(false);
        TargetChange change = change(testCase.type(), 3L, testCase.resultEnabled());
        LockedMonitoringState current = new LockedMonitoringState(2L, 7L, testCase.currentEnabled(), false);
        LockedIncident incident = incident("CONNECTION_FAILURE", 4L);
        PreparedLifecycleEvent incidentEvent = event(OutboxEventType.INCIDENT_RESOLVED, 2);
        PreparedLifecycleEvent statusEvent = event(OutboxEventType.MONITORING_STATUS_CHANGED, 3);
        when(store.lockTarget(TARGET_ID)).thenReturn(Optional.of(target(change)));
        when(store.lockState(TARGET_ID)).thenReturn(Optional.of(current));
        when(store.lockOpenIncidents(TARGET_ID)).thenReturn(List.of(incident));
        when(events.incidentResolved(any(IncidentResolution.class))).thenReturn(incidentEvent);
        when(events.statusChanged(any(MonitoringStateWrite.class))).thenReturn(statusEvent);

        port.applyChange(change);

        var stateCaptor = org.mockito.ArgumentCaptor.forClass(MonitoringStateWrite.class);
        var resolutionCaptor = org.mockito.ArgumentCaptor.forClass(IncidentResolution.class);
        verify(store).updateState(stateCaptor.capture(), org.mockito.ArgumentMatchers.same(current));
        verify(store).resolveIncident(resolutionCaptor.capture());
        MonitoringStateWrite state = stateCaptor.getValue();
        IncidentResolution resolution = resolutionCaptor.getValue();
        assertThat(state.configVersion()).isEqualTo(3L);
        assertThat(state.stateVersion()).isEqualTo(8L);
        assertThat(state.enabled()).isEqualTo(testCase.resultEnabled());
        assertThat(state.deleted()).isEqualTo(testCase.deleted());
        assertThat(state.dataFreshness()).isEqualTo(testCase.freshness());
        assertThat(state.activationAt()).isEqualTo(testCase.activation() ? NORMALIZED_AT : null);
        assertThat(state.updatedAt()).isEqualTo(NORMALIZED_AT);
        assertThat(resolution.reason()).isEqualTo(testCase.reason());
        assertThat(resolution.nextIncidentVersion()).isEqualTo(5L);
        assertThat(resolution.resolvedAt()).isEqualTo(NORMALIZED_AT);

        var ordered = inOrder(store, events, outbox);
        ordered.verify(store).lockTarget(TARGET_ID);
        ordered.verify(store).lockState(TARGET_ID);
        ordered.verify(store).lockOpenIncidents(TARGET_ID);
        ordered.verify(events).incidentResolved(resolution);
        ordered.verify(events).statusChanged(state);
        ordered.verify(store).updateState(state, current);
        ordered.verify(store).deleteRuleStates(TARGET_ID);
        ordered.verify(store).resolveIncident(resolution);
        ordered.verify(store).cancelPendingDeliveries(incident.incidentId());
        ordered.verify(outbox).append(incidentEvent);
        ordered.verify(outbox).append(statusEvent);
        verify(store, never()).insertDefaultPolicy(any(Long.class), any(), any());
    }

    @Test
    void closesEveryOpenMetricAndSystemIncidentWithoutCreatingDeliveries() {
        bindTransaction(false);
        TargetChange change = change(TargetChangeType.PAUSED, 3L, false);
        LockedMonitoringState current = new LockedMonitoringState(2L, 7L, true, false);
        List<LockedIncident> incidents = List.of(
                incident("CONNECTION_RATIO", 1L),
                incident("SLOW_QUERY_RATE", 2L),
                incident("CONNECTION_FAILURE", 3L),
                incident("COLLECTION_STALE", 4L));
        when(store.lockTarget(TARGET_ID)).thenReturn(Optional.of(target(change)));
        when(store.lockState(TARGET_ID)).thenReturn(Optional.of(current));
        when(store.lockOpenIncidents(TARGET_ID)).thenReturn(incidents);
        when(events.incidentResolved(any(IncidentResolution.class)))
                .thenAnswer(invocation -> event(OutboxEventType.INCIDENT_RESOLVED,
                        ((IncidentResolution) invocation.getArgument(0)).nextIncidentVersion()));
        when(events.statusChanged(any(MonitoringStateWrite.class)))
                .thenReturn(event(OutboxEventType.MONITORING_STATUS_CHANGED, 99));

        port.applyChange(change);

        var resolutions = org.mockito.ArgumentCaptor.forClass(IncidentResolution.class);
        verify(store, org.mockito.Mockito.times(4)).resolveIncident(resolutions.capture());
        assertThat(resolutions.getAllValues())
                .extracting(IncidentResolution::reason)
                .containsOnly("MONITORING_PAUSED");
        assertThat(resolutions.getAllValues())
                .extracting(IncidentResolution::nextIncidentVersion)
                .containsExactly(2L, 3L, 4L, 5L);
        for (LockedIncident incident : incidents) {
            verify(store).cancelPendingDeliveries(incident.incidentId());
        }
    }

    @Test
    void rejectsGapBeforeAnyMutationOrOutboxWrite() {
        bindTransaction(false);
        TargetChange change = change(TargetChangeType.UPDATED, 5L, true);
        when(store.lockTarget(TARGET_ID)).thenReturn(Optional.of(target(change)));
        when(store.lockState(TARGET_ID)).thenReturn(Optional.of(
                new LockedMonitoringState(3L, 3L, true, false)));

        assertThatThrownBy(() -> port.applyChange(change))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("advance by exactly one");

        verify(store, never()).updateState(any(), any());
        verify(store, never()).deleteRuleStates(any(Long.class));
        verifyNoInteractions(events, outbox);
    }

    @Test
    void preflightsStateOverflowBeforeAnyMutationOrPayload() {
        bindTransaction(false);
        TargetChange change = change(TargetChangeType.UPDATED, 3L, true);
        when(store.lockTarget(TARGET_ID)).thenReturn(Optional.of(target(change)));
        when(store.lockState(TARGET_ID)).thenReturn(Optional.of(
                new LockedMonitoringState(2L, JdbcMonitoringLifecyclePort.MAX_SAFE_INTEGER, true, false)));
        when(store.lockOpenIncidents(TARGET_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> port.applyChange(change))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stateVersion cannot be incremented safely");

        verify(store, never()).updateState(any(), any());
        verifyNoInteractions(events, outbox);
    }

    @Test
    void oversizedIncidentPayloadFailsBeforeEveryMutation() {
        bindTransaction(false);
        TargetChange change = change(TargetChangeType.UPDATED, 3L, true);
        LockedIncident incident = incident("CONNECTION_RATIO", 1L);
        when(store.lockTarget(TARGET_ID)).thenReturn(Optional.of(target(change)));
        when(store.lockState(TARGET_ID)).thenReturn(Optional.of(
                new LockedMonitoringState(2L, 2L, true, false)));
        when(store.lockOpenIncidents(TARGET_ID)).thenReturn(List.of(incident));
        when(events.incidentResolved(any())).thenThrow(
                new IllegalArgumentException("Event payload exceeds 64KiB: IncidentResolvedEvent"));

        assertThatThrownBy(() -> port.applyChange(change))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event payload exceeds 64KiB: IncidentResolvedEvent");

        verify(store, never()).updateState(any(), any());
        verify(store, never()).resolveIncident(any());
        verify(store, never()).cancelPendingDeliveries(any());
        verifyNoInteractions(outbox);
    }

    private void bindTransaction(boolean readOnly) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
    }

    private TargetChange change(TargetChangeType type, long configVersion, boolean enabled) {
        return new TargetChange(
                TARGET_ID,
                configVersion,
                type,
                enabled,
                "target",
                OCCURRED_AT,
                7L,
                UUID.fromString("00000000-0000-0000-0000-000000000777"));
    }

    private LockedTarget target(TargetChange change) {
        return new LockedTarget(
                change.configVersion(),
                change.enabled(),
                change.name(),
                change.changeType() == TargetChangeType.DELETED);
    }

    private LockedIncident incident(String ruleId, long incidentVersion) {
        return new LockedIncident(
                UUID.nameUUIDFromBytes(ruleId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                TARGET_ID,
                "original target name",
                ruleId,
                ruleId,
                "CRITICAL",
                Instant.parse("2026-09-29T05:00:00Z"),
                Instant.parse("2026-09-29T06:00:00Z"),
                "metric",
                new BigDecimal("1.5"),
                new BigDecimal("1.0"),
                501L,
                "retained evidence",
                incidentVersion);
    }

    private PreparedLifecycleEvent event(OutboxEventType type, long suffix) {
        return new PreparedLifecycleEvent(
                new UUID(0L, suffix), type, TARGET_ID, Map.of("databaseConfigId", TARGET_ID));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> createStates() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(true, "NO_DATA", NORMALIZED_AT),
                org.junit.jupiter.params.provider.Arguments.of(false, "PAUSED", null));
    }

    private static Stream<TransitionCase> existingTransitions() {
        return Stream.of(
                new TransitionCase(TargetChangeType.UPDATED, true, true, false, "NO_DATA", true, "CONFIG_CHANGED"),
                new TransitionCase(TargetChangeType.UPDATED, false, false, false, "PAUSED", false, "CONFIG_CHANGED"),
                new TransitionCase(TargetChangeType.PAUSED, true, false, false, "PAUSED", false, "MONITORING_PAUSED"),
                new TransitionCase(TargetChangeType.RESUMED, false, true, false, "NO_DATA", true, "CONFIG_CHANGED"),
                new TransitionCase(TargetChangeType.DELETED, true, false, true, "PAUSED", false, "TARGET_DELETED"));
    }

    private record TransitionCase(
            TargetChangeType type,
            boolean currentEnabled,
            boolean resultEnabled,
            boolean deleted,
            String freshness,
            boolean activation,
            String reason
    ) {
    }

    private static final class ImmediateTransactionOperations implements TransactionOperations {
        private int calls;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            calls++;
            return action.doInTransaction(mock(TransactionStatus.class));
        }
    }
}
