package com.example.monitoring.risk.engine;

import com.example.monitoring.risk.contract.ConnectionStatus;
import com.example.monitoring.risk.contract.DataFreshness;
import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.MonitoringContracts;
import com.example.monitoring.risk.contract.RiskLevel;
import com.example.monitoring.risk.contract.RiskPolicy;
import com.example.monitoring.risk.contract.RiskRule;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleType;
import com.example.monitoring.risk.contract.SeverityTransition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskStateMachineTest {

    private static final long TARGET_ID = 12L;
    private static final UUID INCIDENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000222");

    private final RiskStateMachine engine = new RiskStateMachine();

    @Test
    void warningOpensAt15AndRecoversAt35() {
        Harness harness = new Harness();

        for (int second : List.of(0, 5, 10)) {
            RiskEvaluation result = harness.metric(second, "0.80", "0.0", CollectionOutcome.SUCCESS);
            assertThat(result.incidentTransitions()).isEmpty();
        }
        RiskEvaluation opened = harness.metric(15, "0.80", "0.0", CollectionOutcome.SUCCESS);

        assertThat(opened.incidentTransitions()).singleElement().satisfies(transition -> {
            assertThat(transition.kind()).isEqualTo(IncidentTransitionKind.OPENED);
            assertThat(transition.ruleId()).isEqualTo(RuleId.CONNECTION_RATIO);
            assertThat(transition.severity()).isEqualTo(IncidentSeverity.WARNING);
            assertThat(transition.occurredAt()).isEqualTo(at(15));
            assertThat(transition.nextIncidentVersion()).isOne();
        });
        harness.persist(opened);

        for (int second : List.of(20, 25, 30)) {
            RiskEvaluation recovering = harness.metric(second, "0.79", "0.0", CollectionOutcome.SUCCESS);
            assertThat(recovering.incidentTransitions()).isEmpty();
        }
        RiskEvaluation resolved = harness.metric(35, "0.79", "0.0", CollectionOutcome.SUCCESS);

        assertThat(resolved.incidentTransitions()).singleElement().satisfies(transition -> {
            assertThat(transition.kind()).isEqualTo(IncidentTransitionKind.RESOLVED);
            assertThat(transition.incidentId()).isEqualTo(INCIDENT_ID);
            assertThat(transition.nextIncidentVersion()).isEqualTo(2L);
            assertThat(transition.occurredAt()).isEqualTo(at(35));
        });
        assertThat(resolved.state().riskLevel()).isEqualTo(RiskLevel.INFO);
    }

    @Test
    void invalidAndGapResetClocksButPreserveOpen() {
        Harness harness = Harness.withOpenWarning(at(15));

        RiskEvaluation afterGap = harness.metric(30, "0.95", "0.0", CollectionOutcome.SUCCESS);
        assertThat(afterGap.incidentTransitions()).isEmpty();
        assertThat(afterGap.ruleClocks().get(RuleId.CONNECTION_RATIO).fatalCandidateSince())
                .isEqualTo(at(30));
        harness.persist(afterGap);

        RiskEvaluation partial = harness.metric(35, null, null, CollectionOutcome.PARTIAL_FAILURE);
        RuleClock reset = partial.ruleClocks().get(RuleId.CONNECTION_RATIO);

        assertThat(partial.incidentTransitions()).isEmpty();
        assertThat(reset.warningCandidateSince()).isNull();
        assertThat(reset.criticalCandidateSince()).isNull();
        assertThat(reset.fatalCandidateSince()).isNull();
        assertThat(reset.recoverySince()).isNull();
        assertThat(harness.openIncidents).containsKey(RuleId.CONNECTION_RATIO);
        assertThat(partial.state().riskLevel()).isEqualTo(RiskLevel.WARNING);
        harness.persist(partial);

        RiskEvaluation candidate = harness.metric(40, "0.95", "0.0", CollectionOutcome.SUCCESS);
        harness.persist(candidate);
        RiskEvaluation nullInput = harness.metric(45, null, "0.0", CollectionOutcome.SUCCESS);
        RuleClock afterNull = nullInput.ruleClocks().get(RuleId.CONNECTION_RATIO);
        assertThat(nullInput.incidentTransitions()).isEmpty();
        assertThat(afterNull.warningCandidateSince()).isNull();
        assertThat(afterNull.criticalCandidateSince()).isNull();
        assertThat(afterNull.fatalCandidateSince()).isNull();
        assertThat(afterNull.recoverySince()).isNull();
    }

    @Test
    void exactThresholdsUseIndependentClocksAndHighestOpenRisk() {
        Harness harness = new Harness();
        RiskEvaluation result = null;

        for (int second : List.of(0, 5, 10, 15)) {
            result = harness.metric(second, "0.95", "5.0", CollectionOutcome.SUCCESS);
            if (second < 15) {
                assertThat(result.incidentTransitions()).isEmpty();
            }
        }

        assertThat(result).isNotNull();
        assertThat(result.incidentTransitions()).extracting(IncidentTransition::severity)
                .containsExactlyInAnyOrder(IncidentSeverity.FATAL, IncidentSeverity.CRITICAL);
        assertThat(result.state().riskLevel()).isEqualTo(RiskLevel.FATAL);
        assertThat(result.ruleClocks().get(RuleId.CONNECTION_RATIO).warningCandidateSince()).isEqualTo(at(0));
        assertThat(result.ruleClocks().get(RuleId.CONNECTION_RATIO).criticalCandidateSince()).isEqualTo(at(0));
        assertThat(result.ruleClocks().get(RuleId.CONNECTION_RATIO).fatalCandidateSince()).isEqualTo(at(0));
    }

    @Test
    void everyThresholdUsesGreaterThanOrEqual() {
        assertThat(openedSeverity("0.80", "0.0", RuleId.CONNECTION_RATIO))
                .isEqualTo(IncidentSeverity.WARNING);
        assertThat(openedSeverity("0.90", "0.0", RuleId.CONNECTION_RATIO))
                .isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(openedSeverity("0.95", "0.0", RuleId.CONNECTION_RATIO))
                .isEqualTo(IncidentSeverity.FATAL);
        assertThat(openedSeverity("0.10", "1.0", RuleId.SLOW_QUERY_RATE))
                .isEqualTo(IncidentSeverity.WARNING);
        assertThat(openedSeverity("0.10", "5.0", RuleId.SLOW_QUERY_RATE))
                .isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    void escalationAndDownshiftKeepOneIncidentIdentity() {
        Harness harness = new Harness();
        RiskEvaluation opened = null;
        for (int second : List.of(0, 5, 10, 15)) {
            opened = harness.metric(second, "0.80", "0.0", CollectionOutcome.SUCCESS);
            harness.persist(opened);
        }
        assertThat(opened).isNotNull();

        RiskEvaluation increased = null;
        for (int second : List.of(20, 25, 30, 35)) {
            increased = harness.metric(second, "0.90", "0.0", CollectionOutcome.SUCCESS);
            if (second < 35) {
                assertThat(increased.incidentTransitions()).isEmpty();
            }
            harness.persist(increased);
        }
        assertThat(increased).isNotNull();
        assertThat(increased.incidentTransitions()).singleElement().satisfies(transition -> {
            assertThat(transition.incidentId()).isEqualTo(INCIDENT_ID);
            assertThat(transition.severity()).isEqualTo(IncidentSeverity.CRITICAL);
            assertThat(transition.severityTransition()).isEqualTo(SeverityTransition.INCREASED);
            assertThat(transition.nextIncidentVersion()).isEqualTo(2L);
        });

        RiskEvaluation decreased = null;
        for (int second : List.of(40, 45, 50, 55)) {
            decreased = harness.metric(second, "0.80", "0.0", CollectionOutcome.SUCCESS);
            if (second < 55) {
                assertThat(decreased.incidentTransitions()).isEmpty();
            }
            harness.persist(decreased);
        }
        assertThat(decreased).isNotNull();
        assertThat(decreased.incidentTransitions()).singleElement().satisfies(transition -> {
            assertThat(transition.incidentId()).isEqualTo(INCIDENT_ID);
            assertThat(transition.severity()).isEqualTo(IncidentSeverity.WARNING);
            assertThat(transition.severityTransition()).isEqualTo(SeverityTransition.DECREASED);
            assertThat(transition.nextIncidentVersion()).isEqualTo(3L);
        });
    }

    @Test
    void connectionFailureOpensAndRecoversAtFifteenSeconds() {
        Harness harness = new Harness();
        for (int second : List.of(0, 5, 10)) {
            assertThat(harness.metric(second, null, null, CollectionOutcome.CONNECTION_FAILED)
                    .incidentTransitions()).isEmpty();
        }
        RiskEvaluation opened = harness.metric(15, null, null, CollectionOutcome.CONNECTION_FAILED);
        assertThat(opened.incidentTransitions()).singleElement().satisfies(transition -> {
            assertThat(transition.ruleId()).isEqualTo(RuleId.CONNECTION_FAILURE);
            assertThat(transition.severity()).isEqualTo(IncidentSeverity.FATAL);
        });
        harness.persist(opened);

        for (int second : List.of(20, 25, 30)) {
            RiskEvaluation recovering = harness.metric(second, "0.1", "0.0", CollectionOutcome.SUCCESS);
            assertThat(recovering.incidentTransitions()).isEmpty();
            harness.persist(recovering);
        }
        RiskEvaluation resolved = harness.metric(35, "0.1", "0.0", CollectionOutcome.SUCCESS);
        assertThat(resolved.incidentTransitions()).singleElement().satisfies(transition -> {
            assertThat(transition.ruleId()).isEqualTo(RuleId.CONNECTION_FAILURE);
            assertThat(transition.kind()).isEqualTo(IncidentTransitionKind.RESOLVED);
            assertThat(transition.incidentId()).isEqualTo(INCIDENT_ID);
        });
    }

    @Test
    void staleTransitionUsesLogicalDueInstantAndSuccessOnlyRecovery() {
        Harness harness = new Harness();
        Instant dueAt = at(30);

        RiskEvaluation stale = engine.evaluate(harness.snapshot(), new StaleDueObservation(dueAt, dueAt.plusMillis(700)));

        assertThat(stale.state().dataFreshness()).isEqualTo(DataFreshness.STALE);
        assertThat(stale.state().updatedAt()).isEqualTo(dueAt);
        assertThat(stale.incidentTransitions()).singleElement().satisfies(transition -> {
            assertThat(transition.ruleId()).isEqualTo(RuleId.COLLECTION_STALE);
            assertThat(transition.severity()).isEqualTo(IncidentSeverity.CRITICAL);
            assertThat(transition.occurredAt()).isEqualTo(dueAt);
        });
        harness.persist(stale);

        RiskEvaluation failedFreshAttempt = harness.metric(31, null, null, CollectionOutcome.CONNECTION_FAILED);
        assertThat(failedFreshAttempt.state().dataFreshness()).isEqualTo(DataFreshness.FRESH);
        assertThat(failedFreshAttempt.state().connectionStatus()).isEqualTo(ConnectionStatus.DOWN);
        assertThat(failedFreshAttempt.incidentTransitions()).isEmpty();
        harness.persist(failedFreshAttempt);

        for (int second : List.of(32, 37, 42)) {
            RiskEvaluation recovering = harness.metric(second, "0.1", "0.0", CollectionOutcome.SUCCESS);
            assertThat(recovering.incidentTransitions()).isEmpty();
            harness.persist(recovering);
        }
        RiskEvaluation recovered = harness.metric(47, "0.1", "0.0", CollectionOutcome.SUCCESS);
        assertThat(recovered.incidentTransitions()).singleElement()
                .extracting(IncidentTransition::kind)
                .isEqualTo(IncidentTransitionKind.RESOLVED);
    }

    @Test
    void repeatStaleChangesFreshStateWithoutReopeningIncident() {
        Harness harness = new Harness();
        RiskEvaluation firstStale = engine.evaluate(
                harness.snapshot(), new StaleDueObservation(at(30), at(30).plusMillis(200)));
        harness.persist(firstStale);

        RiskEvaluation failedFreshAttempt = harness.metric(
                31, null, null, CollectionOutcome.CONNECTION_FAILED);
        assertThat(failedFreshAttempt.state().dataFreshness()).isEqualTo(DataFreshness.FRESH);
        harness.persist(failedFreshAttempt);
        long freshVersion = failedFreshAttempt.state().stateVersion();

        RiskEvaluation staleAgain = engine.evaluate(
                harness.snapshot(), new StaleDueObservation(at(61), at(61).plusMillis(300)));

        assertThat(staleAgain.state().dataFreshness()).isEqualTo(DataFreshness.STALE);
        assertThat(staleAgain.state().updatedAt()).isEqualTo(at(61));
        assertThat(staleAgain.state().stateVersion()).isEqualTo(freshVersion + 1L);
        assertThat(staleAgain.incidentTransitions()).isEmpty();
        assertThat(harness.openIncidents).containsKey(RuleId.COLLECTION_STALE);
    }

    @Test
    void expiredMetricIsOutsideAcceptedObservationContract() {
        MetricRiskObservation expired = new MetricRiskObservation(
                1L,
                new UUID(1L, 1L),
                at(0),
                at(31),
                CollectionOutcome.SUCCESS,
                new BigDecimal("0.10"),
                BigDecimal.ZERO);

        assertThatThrownBy(() -> engine.evaluate(
                new RiskEngineSnapshot(policy(), initialState(), emptyClocks(), Map.of()),
                expired))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accepted metric");
    }

    @Test
    void exactStaleBoundaryIsAcceptedWithSchemaValidLogicalClock() {
        MetricRiskObservation exactDue = new MetricRiskObservation(
                1L,
                new UUID(1L, 1L),
                at(0),
                at(30),
                CollectionOutcome.SUCCESS,
                new BigDecimal("0.10"),
                BigDecimal.ZERO);

        RiskEvaluation result = engine.evaluate(
                new RiskEngineSnapshot(policy(), initialState(), emptyClocks(), Map.of()),
                exactDue);

        RuleClock stale = result.ruleClocks().get(RuleId.COLLECTION_STALE);
        assertThat(result.state().dataFreshness()).isEqualTo(DataFreshness.STALE);
        assertThat(result.state().updatedAt()).isEqualTo(at(30));
        assertThat(stale.criticalCandidateSince()).isEqualTo(at(30));
        assertThat(stale.lastObservedAt()).isEqualTo(at(30));
        assertThat(result.incidentTransitions()).singleElement()
                .extracting(IncidentTransition::occurredAt)
                .isEqualTo(at(30));
    }

    @Test
    void infoRequiresFreshSuccessfulValidEnabledInputsAndPausedAlwaysHasNullRisk() {
        Harness harness = new Harness();

        assertThat(harness.metric(0, "0.10", "0.0", CollectionOutcome.SUCCESS).state().riskLevel())
                .isEqualTo(RiskLevel.INFO);
        harness = new Harness();
        assertThat(harness.metric(0, "0.10", null, CollectionOutcome.SUCCESS).state().riskLevel()).isNull();
        harness = new Harness();
        assertThat(harness.metric(0, null, null, CollectionOutcome.PARTIAL_FAILURE).state().riskLevel()).isNull();

        RiskState paused = initialState().paused(at(0));
        RiskEvaluation ignored = engine.evaluate(
                new RiskEngineSnapshot(policy(), paused, emptyClocks(), Map.of()),
                metricSignal(1, "0.95", "5.0", CollectionOutcome.SUCCESS));
        assertThat(ignored.state().dataFreshness()).isEqualTo(DataFreshness.PAUSED);
        assertThat(ignored.state().riskLevel()).isNull();
        assertThat(ignored.incidentTransitions()).isEmpty();

        RiskRule ratio = MonitoringContracts.defaultRules().get(0);
        RiskRule slow = MonitoringContracts.defaultRules().get(1);
        RiskRule disabledSlow = new RiskRule(
                slow.ruleId(), slow.metricName(), slow.operator(), slow.warningThreshold(),
                slow.criticalThreshold(), slow.fatalThreshold(), slow.sustainSeconds(),
                slow.recoverySeconds(), false);
        RiskPolicy onlyRatio = new RiskPolicy(
                TARGET_ID, 1L, 30, 300, List.of(ratio, disabledSlow), at(0));
        RiskEvaluation disabledInput = engine.evaluate(
                new RiskEngineSnapshot(onlyRatio, initialState(), emptyClocks(), Map.of()),
                metricSignal(0, "0.10", null, CollectionOutcome.SUCCESS));
        assertThat(disabledInput.state().riskLevel()).isEqualTo(RiskLevel.INFO);
    }

    private IncidentSeverity openedSeverity(String ratio, String slow, RuleId ruleId) {
        Harness harness = new Harness();
        RiskEvaluation result = null;
        for (int second : List.of(0, 5, 10, 15)) {
            result = harness.metric(second, ratio, slow, CollectionOutcome.SUCCESS);
            harness.persist(result);
        }
        assertThat(result).isNotNull();
        return result.incidentTransitions().stream()
                .filter(transition -> transition.ruleId() == ruleId)
                .findFirst()
                .orElseThrow()
                .severity();
    }

    private static final class Harness {
        private RiskState state;
        private Map<RuleId, RuleClock> clocks;
        private final Map<RuleId, Incident> openIncidents = new EnumMap<>(RuleId.class);

        private Harness() {
            state = initialState();
            clocks = emptyClocks();
        }

        private static Harness withOpenWarning(Instant observedAt) {
            Harness harness = new Harness();
            harness.clocks = new EnumMap<>(harness.clocks);
            harness.clocks.put(RuleId.CONNECTION_RATIO, new RuleClock(
                    RuleId.CONNECTION_RATIO, at(0), null, null, null, observedAt, 4L));
            harness.openIncidents.put(RuleId.CONNECTION_RATIO, new Incident(
                    INCIDENT_ID, TARGET_ID, "production", RuleId.CONNECTION_RATIO,
                    RuleType.CONNECTION_RATIO_EXCEEDED, IncidentSeverity.WARNING, IncidentStatus.OPEN,
                    observedAt, observedAt, null, null, "activeConnectionsRatio",
                    new BigDecimal("0.80"), new BigDecimal("0.80"), 4L, "warning", 1L));
            return harness;
        }

        private RiskEvaluation metric(int second, String ratio, String slow, CollectionOutcome outcome) {
            RiskEvaluation evaluation = new RiskStateMachine().evaluate(
                    snapshot(), metricSignal(second, ratio, slow, outcome));
            state = evaluation.state();
            clocks = evaluation.ruleClocks();
            return evaluation;
        }

        private RiskEngineSnapshot snapshot() {
            return new RiskEngineSnapshot(policy(), state, clocks, openIncidents);
        }

        private void persist(RiskEvaluation evaluation) {
            state = evaluation.state();
            clocks = evaluation.ruleClocks();
            for (IncidentTransition transition : evaluation.incidentTransitions()) {
                if (transition.kind() == IncidentTransitionKind.RESOLVED) {
                    openIncidents.remove(transition.ruleId());
                } else {
                    UUID id = transition.incidentId() == null ? INCIDENT_ID : transition.incidentId();
                    Instant openedAt = transition.kind() == IncidentTransitionKind.OPENED
                            ? transition.occurredAt()
                            : openIncidents.get(transition.ruleId()).openedAt();
                    openIncidents.put(transition.ruleId(), new Incident(
                            id, TARGET_ID, "production", transition.ruleId(), transition.ruleType(),
                            transition.severity(), IncidentStatus.OPEN, openedAt, transition.occurredAt(),
                            null, null, transition.metricName(), transition.metricValue(),
                            transition.thresholdValue(), transition.sourceMetricId(), transition.message(),
                            transition.nextIncidentVersion()));
                }
            }
        }
    }

    private static RiskPolicy policy() {
        return new RiskPolicy(TARGET_ID, 1L, 30, 300, MonitoringContracts.defaultRules(), at(0));
    }

    private static RiskState initialState() {
        return new RiskState(
                TARGET_ID, 1L, 1L, true, false, ConnectionStatus.UNKNOWN, DataFreshness.NO_DATA,
                null, at(0), null, null, null, at(0));
    }

    private static Map<RuleId, RuleClock> emptyClocks() {
        Map<RuleId, RuleClock> clocks = new EnumMap<>(RuleId.class);
        for (RuleId ruleId : RuleId.values()) {
            clocks.put(ruleId, RuleClock.empty(ruleId));
        }
        return clocks;
    }

    private static MetricRiskObservation metricSignal(
            int second,
            String ratio,
            String slow,
            CollectionOutcome outcome
    ) {
        Instant observedAt = at(second);
        return new MetricRiskObservation(
                second + 1L,
                new UUID(1L, second + 1L),
                observedAt,
                observedAt,
                outcome,
                ratio == null ? null : new BigDecimal(ratio),
                slow == null ? null : new BigDecimal(slow));
    }

    private static Instant at(int second) {
        return Instant.parse("2026-10-02T00:00:00Z").plusSeconds(second);
    }
}
