package com.example.monitoring.risk.policy;

import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.outbox.OutboxEventType;
import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.domain.AuditTargetType;
import com.example.monitoring.partc.api.PartCQueryValidator;
import com.example.monitoring.risk.contract.ConnectionStatus;
import com.example.monitoring.risk.contract.DataFreshness;
import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.IncidentEventPayload;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.MonitoringContracts;
import com.example.monitoring.risk.contract.ResolutionReason;
import com.example.monitoring.risk.contract.RiskLevel;
import com.example.monitoring.risk.contract.RiskPolicy;
import com.example.monitoring.risk.contract.RiskRule;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleOperator;
import com.example.monitoring.risk.contract.RuleType;
import com.example.monitoring.risk.contract.StatusSnapshot;
import com.example.monitoring.risk.engine.RiskState;
import com.example.monitoring.risk.engine.RuleClock;
import com.example.monitoring.risk.persistence.LockedTarget;
import com.example.monitoring.risk.persistence.PendingDelivery;
import com.example.monitoring.risk.persistence.RiskJdbcStore;
import com.example.monitoring.risk.persistence.RiskMutationLock;
import com.example.monitoring.risk.persistence.RiskOutboxAppender;
import com.example.monitoring.service.AuditEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RiskPolicyServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:34:56.789Z");
    private static final UUID CONFIG_A = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID CONFIG_B = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID SYSTEM_A = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID SYSTEM_B = UUID.fromString("00000000-0000-0000-0000-000000000040");

    private final RiskPolicyQueryRepository queryRepository = mock(RiskPolicyQueryRepository.class);
    private final RiskJdbcStore store = mock(RiskJdbcStore.class);
    private final RiskOutboxAppender outbox = mock(RiskOutboxAppender.class);
    private final AuditEventService auditEvents = mock(AuditEventService.class);
    private RiskPolicyService service;

    @BeforeEach
    void setUp() {
        service = new RiskPolicyService(
                queryRepository,
                store,
                outbox,
                auditEvents,
                new PartCQueryValidator(Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void closesOnlyConfigurableIncidentsAndPublishesIncidentEventsBeforeOneStatus() {
        RiskMutationLock lock = lock();
        when(store.lockForMutation(12L)).thenReturn(Optional.of(lock));

        RiskPolicy updated = service.update("12", write(5L));

        assertThat(updated.version()).isEqualTo(6L);
        assertThat(updated.updatedAt()).isEqualTo(NOW);
        assertThat(updated.rules()).extracting(RiskRule::ruleId)
                .containsExactly(RuleId.CONNECTION_RATIO, RuleId.SLOW_QUERY_RATE);

        ArgumentCaptor<Incident> incidents = ArgumentCaptor.forClass(Incident.class);
        ArgumentCaptor<IncidentEventPayload> incidentEvents = ArgumentCaptor.forClass(IncidentEventPayload.class);
        ArgumentCaptor<RiskState> state = ArgumentCaptor.forClass(RiskState.class);
        ArgumentCaptor<StatusSnapshot> status = ArgumentCaptor.forClass(StatusSnapshot.class);
        InOrder order = inOrder(store, outbox, auditEvents);
        order.verify(store).lockForMutation(12L);
        order.verify(store).updatePolicy(updated, 5L);
        order.verify(store).clearConfigurableRuleClocks(12L);
        order.verify(store).updateIncident(incidents.capture(), isNull());
        order.verify(store).cancelPendingDeliveries(CONFIG_A);
        order.verify(outbox).appendIncident(any(UUID.class), eq(OutboxEventType.INCIDENT_RESOLVED),
                incidentEvents.capture());
        order.verify(store).updateIncident(incidents.capture(), isNull());
        order.verify(store).cancelPendingDeliveries(CONFIG_B);
        order.verify(outbox).appendIncident(any(UUID.class), eq(OutboxEventType.INCIDENT_RESOLVED),
                incidentEvents.capture());
        order.verify(store).updateState(state.capture());
        order.verify(outbox).appendStatus(any(UUID.class), status.capture());
        order.verify(auditEvents).successCurrent(
                AuditAction.POLICY_UPDATED,
                AuditTargetType.POLICY,
                "12",
                12L,
                "Risk policy updated to version 6");

        assertThat(incidents.getAllValues()).allSatisfy(incident -> {
            assertThat(incident.status()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(incident.resolutionReason()).isEqualTo(ResolutionReason.POLICY_CHANGED);
            assertThat(incident.resolvedAt()).isEqualTo(NOW);
            assertThat(incident.message()).startsWith("evidence-");
        });
        assertThat(incidents.getAllValues()).extracting(Incident::incidentId)
                .containsExactly(CONFIG_A, CONFIG_B);
        assertThat(incidents.getAllValues()).extracting(Incident::incidentVersion)
                .containsExactly(4L, 8L);
        assertThat(incidentEvents.getAllValues()).extracting(IncidentEventPayload::incidentId)
                .containsExactly(CONFIG_A, CONFIG_B);
        assertThat(state.getValue().stateVersion()).isEqualTo(11L);
        assertThat(state.getValue().riskLevel()).isEqualTo(RiskLevel.FATAL);
        assertThat(status.getValue().stateVersion()).isEqualTo(11L);
        assertThat(status.getValue().riskLevel()).isEqualTo(RiskLevel.FATAL);
        assertThat(status.getValue().openIncidentIds()).containsExactly(SYSTEM_A, SYSTEM_B);
        verify(store, never()).cancelPendingDeliveries(SYSTEM_A);
        verify(store, never()).cancelPendingDeliveries(SYSTEM_B);
    }

    @Test
    void stampsThePolicyAfterAcquiringTheMutationLock() {
        Clock mutationClock = mock(Clock.class);
        when(mutationClock.instant()).thenReturn(NOW);
        RiskPolicyService timedService = new RiskPolicyService(
                queryRepository,
                store,
                outbox,
                auditEvents,
                new PartCQueryValidator(Clock.fixed(NOW, ZoneOffset.UTC)),
                mutationClock);
        when(store.lockForMutation(12L)).thenReturn(Optional.of(lock()));

        RiskPolicy updated = timedService.update("12", write(5L));

        InOrder chronology = inOrder(store, mutationClock);
        chronology.verify(store).lockForMutation(12L);
        chronology.verify(mutationClock).instant();
        assertThat(updated.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void clearsRiskWhenPolicyClosureLeavesNoOpenIncidentOrValidConfigurableInput() {
        RiskMutationLock base = lock();
        List<Incident> configurable = base.openIncidents().stream()
                .filter(incident -> incident.ruleId().configurable())
                .toList();
        RiskState state = new RiskState(
                12L, 3L, 10L, true, false,
                ConnectionStatus.UP, DataFreshness.FRESH, RiskLevel.CRITICAL,
                NOW.minusSeconds(600), NOW.minusSeconds(10), NOW.minusSeconds(10), 41L,
                NOW.minusSeconds(10));
        when(store.lockForMutation(12L)).thenReturn(Optional.of(new RiskMutationLock(
                base.target(), state, base.policy(), base.ruleClocks(), configurable, List.of())));

        service.update("12", write(5L));

        ArgumentCaptor<RiskState> next = ArgumentCaptor.forClass(RiskState.class);
        verify(store).updateState(next.capture());
        assertThat(next.getValue().riskLevel()).isNull();
        assertThat(next.getValue().stateVersion()).isEqualTo(11L);
    }

    @Test
    void staleVersionAndInvalidRuleSetHaveNoMutationSideEffects() {
        assertThatThrownBy(() -> service.update("12", new PolicyWrite(
                5L,
                29,
                300,
                ruleWrites())))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(failure.getCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(failure.getFieldErrors()).singleElement()
                            .satisfies(field -> {
                                assertThat(field.field()).isEqualTo("staleAfterSeconds");
                                assertThat(field.code()).isEqualTo("OUT_OF_RANGE");
                            });
                });
        verifyNoInteractions(store, outbox, auditEvents);

        reset(store, outbox, auditEvents);
        RiskMutationLock lock = lock();
        when(store.lockForMutation(12L)).thenReturn(Optional.of(lock));
        assertThatThrownBy(() -> service.update("12", write(4L)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getCode()).isEqualTo("POLICY_VERSION_CONFLICT");
                });
        verify(store).lockForMutation(12L);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(outbox, auditEvents);

        reset(store, outbox, auditEvents);
        List<PolicyRuleWrite> duplicate = List.of(
                PolicyRuleWrite.from(MonitoringContracts.defaultRules().get(0)),
                PolicyRuleWrite.from(MonitoringContracts.defaultRules().get(0)));
        assertThatThrownBy(() -> service.update("12", new PolicyWrite(5L, 30, 300, duplicate)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(failure.getFieldErrors()).singleElement()
                            .satisfies(field -> assertThat(field.field()).isEqualTo("rules"));
                });
        verifyNoInteractions(store, outbox, auditEvents);
    }

    @Test
    void acceptsInclusivePolicyRangeBoundaries() {
        when(store.lockForMutation(12L)).thenReturn(Optional.of(lock()));
        RiskPolicy minimum = service.update("12", new PolicyWrite(5L, 30, 60, ruleWrites()));
        assertThat(minimum.staleAfterSeconds()).isEqualTo(30);
        assertThat(minimum.notificationCooldownSeconds()).isEqualTo(60);

        reset(store, outbox, auditEvents);
        when(store.lockForMutation(12L)).thenReturn(Optional.of(lock()));
        RiskPolicy maximum = service.update("12", new PolicyWrite(5L, 300, 3600, ruleWrites()));
        assertThat(maximum.staleAfterSeconds()).isEqualTo(300);
        assertThat(maximum.notificationCooldownSeconds()).isEqualTo(3600);

        reset(store, outbox, auditEvents);
        when(store.lockForMutation(12L)).thenReturn(Optional.of(lock()));
        RiskPolicy durationBounds = service.update("12", new PolicyWrite(5L, 30, 60, List.of(
                connection("activeConnectionsRatio", "0.80", "0.90", "1.00", 5, 300, false),
                slow("slowQueriesPerSecond", "1.0", "5.0", null, 300, 5, false))));
        assertThat(durationBounds.rules()).extracting(RiskRule::enabled).containsExactly(false, false);
    }

    @Test
    void rejectsEveryPolicyAndRuleBoundaryBeforeLocking() {
        PolicyRuleWrite connection = ruleWrites().get(0);
        PolicyRuleWrite slow = ruleWrites().get(1);
        List<InvalidPolicy> cases = List.of(
                invalid(null, "body", "REQUIRED"),
                invalid(new PolicyWrite(null, 30, 60, ruleWrites()), "version", "REQUIRED"),
                invalid(new PolicyWrite(0L, 30, 60, ruleWrites()), "version", "OUT_OF_RANGE"),
                invalid(new PolicyWrite(9_007_199_254_740_991L, 30, 60, ruleWrites()),
                        "version", "OUT_OF_RANGE"),
                invalid(new PolicyWrite(5L, null, 60, ruleWrites()), "staleAfterSeconds", "REQUIRED"),
                invalid(new PolicyWrite(5L, 29, 60, ruleWrites()), "staleAfterSeconds", "OUT_OF_RANGE"),
                invalid(new PolicyWrite(5L, 301, 60, ruleWrites()), "staleAfterSeconds", "OUT_OF_RANGE"),
                invalid(new PolicyWrite(5L, 30, null, ruleWrites()),
                        "notificationCooldownSeconds", "REQUIRED"),
                invalid(new PolicyWrite(5L, 30, 59, ruleWrites()),
                        "notificationCooldownSeconds", "OUT_OF_RANGE"),
                invalid(new PolicyWrite(5L, 30, 3601, ruleWrites()),
                        "notificationCooldownSeconds", "OUT_OF_RANGE"),
                invalid(new PolicyWrite(5L, 30, 60, null), "rules", "REQUIRED"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(connection)), "rules", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60,
                        java.util.Arrays.asList((PolicyRuleWrite) null, slow)),
                        "rules[0]", "REQUIRED"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(connection, connection)),
                        "rules", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        new PolicyRuleWrite(null, connection.metricName(), connection.operator(),
                                connection.warningThreshold(), connection.criticalThreshold(),
                                connection.fatalThreshold(), connection.sustainSeconds(),
                                connection.recoverySeconds(), connection.enabled()), slow)),
                        "rules[0].ruleId", "REQUIRED"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        new PolicyRuleWrite(RuleId.CONNECTION_FAILURE, "connectionStatus",
                                connection.operator(), connection.warningThreshold(),
                                connection.criticalThreshold(), connection.fatalThreshold(),
                                connection.sustainSeconds(), connection.recoverySeconds(),
                                connection.enabled()), slow)),
                        "rules[0]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        new PolicyRuleWrite(connection.ruleId(), null, connection.operator(),
                                connection.warningThreshold(), connection.criticalThreshold(),
                                connection.fatalThreshold(), connection.sustainSeconds(),
                                connection.recoverySeconds(), connection.enabled()), slow)),
                        "rules[0].metricName", "REQUIRED"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        new PolicyRuleWrite(connection.ruleId(), connection.metricName(), null,
                                connection.warningThreshold(), connection.criticalThreshold(),
                                connection.fatalThreshold(), connection.sustainSeconds(),
                                connection.recoverySeconds(), connection.enabled()), slow)),
                        "rules[0].operator", "REQUIRED"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        new PolicyRuleWrite(connection.ruleId(), connection.metricName(), connection.operator(),
                                null, connection.criticalThreshold(), connection.fatalThreshold(),
                                connection.sustainSeconds(), connection.recoverySeconds(), connection.enabled()), slow)),
                        "rules[0].warningThreshold", "REQUIRED"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        new PolicyRuleWrite(connection.ruleId(), connection.metricName(), connection.operator(),
                                connection.warningThreshold(), null, connection.fatalThreshold(),
                                connection.sustainSeconds(), connection.recoverySeconds(), connection.enabled()), slow)),
                        "rules[0].criticalThreshold", "REQUIRED"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        connection("activeConnectionsRatio", "0.80", "0.90", null, 15, 15, true), slow)),
                        "rules[0]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        connection("activeConnectionsRatio", "0.00", "0.90", "0.95", 15, 15, true), slow)),
                        "rules[0]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        connection("activeConnectionsRatio", "0.90", "0.90", "0.95", 15, 15, true), slow)),
                        "rules[0]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        connection("activeConnectionsRatio", "0.80", "0.90", "0.90", 15, 15, true), slow)),
                        "rules[0]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        connection("activeConnectionsRatio", "0.80", "0.90", "1.01", 15, 15, true), slow)),
                        "rules[0]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        connection("wrongMetric", "0.80", "0.90", "0.95", 15, 15, true), slow)),
                        "rules[0]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        connection("activeConnectionsRatio", "0.80", "0.90", "0.95", null, 15, true), slow)),
                        "rules[0].sustainSeconds", "REQUIRED"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        connection("activeConnectionsRatio", "0.80", "0.90", "0.95", 6, 15, true), slow)),
                        "rules[0]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        connection("activeConnectionsRatio", "0.80", "0.90", "0.95", 15, null, true), slow)),
                        "rules[0].recoverySeconds", "REQUIRED"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        connection("activeConnectionsRatio", "0.80", "0.90", "0.95", 15, 305, true), slow)),
                        "rules[0]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(
                        connection("activeConnectionsRatio", "0.80", "0.90", "0.95", 15, 15, null), slow)),
                        "rules[0].enabled", "REQUIRED"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(connection,
                        slow("slowQueriesPerSecond", "0.0", "5.0", null, 15, 15, true))),
                        "rules[1]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(connection,
                        slow("slowQueriesPerSecond", "5.0", "5.0", null, 15, 15, true))),
                        "rules[1]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(connection,
                        slow("slowQueriesPerSecond", "1.0", "5.0", "9.0", 15, 15, true))),
                        "rules[1]", "INVALID_VALUE"),
                invalid(new PolicyWrite(5L, 30, 60, List.of(connection,
                        slow("wrongMetric", "1.0", "5.0", null, 15, 15, true))),
                        "rules[1]", "INVALID_VALUE"));

        for (InvalidPolicy invalid : cases) {
            assertThatThrownBy(() -> service.update("12", invalid.write()))
                    .isInstanceOfSatisfying(ApiException.class, failure -> {
                        assertThat(failure.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(failure.getCode()).isEqualTo("VALIDATION_ERROR");
                        assertThat(failure.getFieldErrors()).singleElement().satisfies(field -> {
                            assertThat(field.field()).isEqualTo(invalid.field());
                            assertThat(field.code()).isEqualTo(invalid.code());
                        });
                    });
            verifyNoInteractions(store, outbox, auditEvents);
            reset(store, outbox, auditEvents);
        }
    }

    private RiskMutationLock lock() {
        RiskPolicy current = new RiskPolicy(12L, 5L, 30, 300, MonitoringContracts.defaultRules(),
                NOW.minusSeconds(60));
        RiskState state = new RiskState(
                12L, 3L, 10L, true, false,
                ConnectionStatus.UP, DataFreshness.FRESH, RiskLevel.FATAL,
                NOW.minusSeconds(600), NOW.minusSeconds(10), NOW.minusSeconds(10), 41L,
                NOW.minusSeconds(10));
        List<Incident> incidents = List.of(
                incident(SYSTEM_B, RuleId.COLLECTION_STALE, IncidentSeverity.CRITICAL, 9L),
                incident(CONFIG_B, RuleId.SLOW_QUERY_RATE, IncidentSeverity.WARNING, 7L),
                incident(SYSTEM_A, RuleId.CONNECTION_FAILURE, IncidentSeverity.FATAL, 2L),
                incident(CONFIG_A, RuleId.CONNECTION_RATIO, IncidentSeverity.CRITICAL, 3L));
        Map<RuleId, RuleClock> clocks = Map.of(
                RuleId.CONNECTION_RATIO, RuleClock.empty(RuleId.CONNECTION_RATIO),
                RuleId.SLOW_QUERY_RATE, RuleClock.empty(RuleId.SLOW_QUERY_RATE),
                RuleId.CONNECTION_FAILURE, RuleClock.empty(RuleId.CONNECTION_FAILURE),
                RuleId.COLLECTION_STALE, RuleClock.empty(RuleId.COLLECTION_STALE));
        return new RiskMutationLock(
                new LockedTarget(12L, 3L, true, false, "운영 MariaDB"),
                state,
                current,
                clocks,
                incidents,
                List.of(new PendingDelivery(80L, CONFIG_A), new PendingDelivery(81L, SYSTEM_A)));
    }

    private PolicyWrite write(long version) {
        return new PolicyWrite(
                version,
                120,
                600,
                List.of(
                        new PolicyRuleWrite(
                                RuleId.CONNECTION_RATIO,
                                "activeConnectionsRatio",
                                RuleOperator.GTE,
                                new BigDecimal("0.70"),
                                new BigDecimal("0.85"),
                                new BigDecimal("0.97"),
                                30,
                                20,
                                true),
                        new PolicyRuleWrite(
                                RuleId.SLOW_QUERY_RATE,
                                "slowQueriesPerSecond",
                                RuleOperator.GTE,
                                new BigDecimal("2.0"),
                                new BigDecimal("8.0"),
                                null,
                                25,
                                35,
                                true)));
    }

    private List<PolicyRuleWrite> ruleWrites() {
        return MonitoringContracts.defaultRules().stream().map(PolicyRuleWrite::from).toList();
    }

    private PolicyRuleWrite connection(
            String metricName,
            String warning,
            String critical,
            String fatal,
            Integer sustain,
            Integer recovery,
            Boolean enabled
    ) {
        return new PolicyRuleWrite(
                RuleId.CONNECTION_RATIO,
                metricName,
                RuleOperator.GTE,
                new BigDecimal(warning),
                new BigDecimal(critical),
                fatal == null ? null : new BigDecimal(fatal),
                sustain,
                recovery,
                enabled);
    }

    private PolicyRuleWrite slow(
            String metricName,
            String warning,
            String critical,
            String fatal,
            Integer sustain,
            Integer recovery,
            Boolean enabled
    ) {
        return new PolicyRuleWrite(
                RuleId.SLOW_QUERY_RATE,
                metricName,
                RuleOperator.GTE,
                new BigDecimal(warning),
                new BigDecimal(critical),
                fatal == null ? null : new BigDecimal(fatal),
                sustain,
                recovery,
                enabled);
    }

    private InvalidPolicy invalid(PolicyWrite write, String field, String code) {
        return new InvalidPolicy(write, field, code);
    }

    private record InvalidPolicy(PolicyWrite write, String field, String code) {
    }

    private Incident incident(UUID id, RuleId ruleId, IncidentSeverity severity, long version) {
        RuleType ruleType = switch (ruleId) {
            case CONNECTION_RATIO -> RuleType.CONNECTION_RATIO_EXCEEDED;
            case SLOW_QUERY_RATE -> RuleType.SLOW_QUERIES_HIGH;
            case CONNECTION_FAILURE -> RuleType.CONNECTION_FAILURE;
            case COLLECTION_STALE -> RuleType.COLLECTION_STALE;
        };
        String metricName = switch (ruleId) {
            case CONNECTION_RATIO -> "activeConnectionsRatio";
            case SLOW_QUERY_RATE -> "slowQueriesPerSecond";
            case CONNECTION_FAILURE -> "connectionStatus";
            case COLLECTION_STALE -> "collectionAgeSeconds";
        };
        return new Incident(
                id,
                12L,
                "운영 MariaDB",
                ruleId,
                ruleType,
                severity,
                IncidentStatus.OPEN,
                NOW.minusSeconds(120),
                NOW.minusSeconds(10),
                null,
                null,
                metricName,
                ruleId.configurable() ? BigDecimal.ONE : null,
                ruleId.configurable() ? new BigDecimal("0.80") : null,
                41L,
                "evidence-" + ruleId,
                version);
    }
}
