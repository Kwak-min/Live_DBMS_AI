package com.example.monitoring.risk.engine;

import com.example.monitoring.risk.contract.ConnectionStatus;
import com.example.monitoring.risk.contract.DataFreshness;
import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.ResolutionReason;
import com.example.monitoring.risk.contract.RiskLevel;
import com.example.monitoring.risk.contract.RiskRule;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleType;
import com.example.monitoring.risk.contract.SeverityTransition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public final class RiskStateMachine {

    static final int MAX_OBSERVATION_GAP_SECONDS = 10;
    static final int SYSTEM_RECOVERY_SECONDS = 15;

    public RiskEvaluation evaluate(RiskEngineSnapshot snapshot, RiskObservation observation) {
        if (!snapshot.state().enabled() || snapshot.state().deleted()) {
            return new RiskEvaluation(snapshot.state(), snapshot.ruleClocks(), List.of());
        }
        if (observation instanceof MetricRiskObservation metric) {
            return evaluateMetric(snapshot, metric);
        }
        return evaluateStale(snapshot, (StaleDueObservation) observation);
    }

    private RiskEvaluation evaluateMetric(RiskEngineSnapshot snapshot, MetricRiskObservation metric) {
        Instant staleDueAt = metric.observedAt()
                .plusSeconds(snapshot.policy().staleAfterSeconds());
        if (metric.evaluatedAt().isAfter(staleDueAt)) {
            throw new IllegalArgumentException(
                    "An accepted metric cannot be older than staleAfterSeconds");
        }
        EnumMap<RuleId, RuleClock> clocks = clocks(snapshot.ruleClocks());
        List<IncidentTransition> transitions = new ArrayList<>();

        for (RiskRule rule : snapshot.policy().rules()) {
            RuleResult result = evaluateThresholdRule(
                    rule,
                    clocks.get(rule.ruleId()),
                    snapshot.openIncidents().get(rule.ruleId()),
                    metric);
            clocks.put(rule.ruleId(), result.clock());
            add(transitions, result.transition());
        }

        RuleResult connection = evaluateConnectionFailure(
                clocks.get(RuleId.CONNECTION_FAILURE),
                snapshot.openIncidents().get(RuleId.CONNECTION_FAILURE),
                metric);
        clocks.put(RuleId.CONNECTION_FAILURE, connection.clock());
        add(transitions, connection.transition());

        boolean fresh = metric.evaluatedAt().isBefore(staleDueAt);
        RuleResult stale = evaluateMetricStale(
                snapshot.policy().staleAfterSeconds(),
                clocks.get(RuleId.COLLECTION_STALE),
                snapshot.openIncidents().get(RuleId.COLLECTION_STALE),
                metric,
                fresh);
        clocks.put(RuleId.COLLECTION_STALE, stale.clock());
        add(transitions, stale.transition());

        RiskLevel risk = projectedRisk(snapshot.openIncidents(), transitions);
        if (risk == null && inputsSupportInfo(snapshot, metric, fresh)) {
            risk = RiskLevel.INFO;
        }
        RiskState current = snapshot.state();
        RiskState next = new RiskState(
                current.databaseConfigId(),
                current.configVersion(),
                RiskState.increment(current.stateVersion(), "stateVersion"),
                true,
                false,
                connectionStatus(metric.outcome()),
                fresh ? DataFreshness.FRESH : DataFreshness.STALE,
                risk,
                current.activationAt(),
                metric.observedAt(),
                metric.outcome() == CollectionOutcome.SUCCESS
                        ? metric.observedAt() : current.lastSuccessAt(),
                metric.metricId(),
                fresh ? later(current.updatedAt(), metric.observedAt()) : staleDueAt);
        return new RiskEvaluation(next, clocks, transitions);
    }

    private RiskEvaluation evaluateStale(RiskEngineSnapshot snapshot, StaleDueObservation observation) {
        boolean incidentAlreadyOpen = snapshot.openIncidents().containsKey(RuleId.COLLECTION_STALE);
        if (incidentAlreadyOpen && snapshot.state().dataFreshness() == DataFreshness.STALE) {
            return new RiskEvaluation(snapshot.state(), snapshot.ruleClocks(), List.of());
        }

        EnumMap<RuleId, RuleClock> clocks = clocks(snapshot.ruleClocks());
        RuleClock previous = clocks.get(RuleId.COLLECTION_STALE);
        clocks.put(RuleId.COLLECTION_STALE, new RuleClock(
                RuleId.COLLECTION_STALE,
                null,
                observation.dueAt(),
                null,
                null,
                observation.dueAt(),
                previous.lastMetricId()));

        List<IncidentTransition> transitions;
        if (incidentAlreadyOpen) {
            transitions = List.of();
        } else {
            BigDecimal seconds = BigDecimal.valueOf(snapshot.policy().staleAfterSeconds());
            transitions = List.of(opened(
                    RuleId.COLLECTION_STALE,
                    IncidentSeverity.CRITICAL,
                    observation.dueAt(),
                    "collectionAgeSeconds",
                    seconds,
                    seconds,
                    null,
                    null,
                    "Collection is stale"));
        }
        RiskState current = snapshot.state();
        RiskState next = new RiskState(
                current.databaseConfigId(),
                current.configVersion(),
                RiskState.increment(current.stateVersion(), "stateVersion"),
                true,
                false,
                current.connectionStatus(),
                DataFreshness.STALE,
                projectedRisk(snapshot.openIncidents(), transitions),
                current.activationAt(),
                current.lastAttemptAt(),
                current.lastSuccessAt(),
                current.latestMetricId(),
                observation.dueAt());
        return new RiskEvaluation(next, clocks, transitions);
    }

    private RuleResult evaluateThresholdRule(
            RiskRule rule,
            RuleClock previous,
            Incident openIncident,
            MetricRiskObservation metric
    ) {
        Instant at = observationAt(previous, openIncident, metric.observedAt());
        boolean gap = hasGap(previous, at);
        if (!rule.enabled()) {
            return new RuleResult(previous.resetAt(at, metric.metricId()), null);
        }
        BigDecimal value = switch (rule.ruleId()) {
            case CONNECTION_RATIO -> metric.activeConnectionsRatio();
            case SLOW_QUERY_RATE -> metric.slowQueriesPerSecond();
            default -> throw new IllegalArgumentException("Not a configurable rule: " + rule.ruleId());
        };
        if (metric.outcome() != CollectionOutcome.SUCCESS || value == null) {
            return new RuleResult(previous.resetAt(at, metric.metricId()), null);
        }

        Instant warning = candidate(
                gap ? null : previous.warningCandidateSince(),
                value.compareTo(rule.warningThreshold()) >= 0,
                at);
        Instant critical = candidate(
                gap ? null : previous.criticalCandidateSince(),
                value.compareTo(rule.criticalThreshold()) >= 0,
                at);
        Instant fatal = candidate(
                gap ? null : previous.fatalCandidateSince(),
                rule.fatalThreshold() != null && value.compareTo(rule.fatalThreshold()) >= 0,
                at);
        IncidentSeverity actual = thresholdSeverity(rule, value);
        IncidentSeverity matured = highestMatured(
                warning, critical, fatal, at, rule.sustainSeconds());
        RuleClock clock = new RuleClock(
                rule.ruleId(), warning, critical, fatal,
                gap ? null : previous.recoverySince(), at, metric.metricId());
        return transitionForThreshold(rule, clock, openIncident, actual, matured, value, metric);
    }

    private RuleResult transitionForThreshold(
            RiskRule rule,
            RuleClock clock,
            Incident openIncident,
            IncidentSeverity actual,
            IncidentSeverity matured,
            BigDecimal value,
            MetricRiskObservation metric
    ) {
        if (openIncident == null) {
            if (matured == null) {
                return new RuleResult(clock, null);
            }
            return new RuleResult(withRecovery(clock, null), opened(
                    rule.ruleId(), matured, metric.observedAt(), rule.metricName(), value,
                    threshold(rule, matured), metric.metricId(), metric.sourceEventId(),
                    rule.ruleId() + " reached " + matured));
        }

        IncidentSeverity current = openIncident.severity();
        if (matured != null && rank(matured) > rank(current)) {
            return new RuleResult(withRecovery(clock, null), updated(
                    openIncident, matured, metric.observedAt(), rule.metricName(), value,
                    threshold(rule, matured), metric.metricId(), metric.sourceEventId()));
        }
        if (rank(actual) < rank(current)) {
            Instant recovery = clock.recoverySince() == null
                    ? metric.observedAt() : clock.recoverySince();
            RuleClock recovering = withRecovery(clock, recovery);
            if (!due(recovery, metric.observedAt(), rule.recoverySeconds())) {
                return new RuleResult(recovering, null);
            }
            if (actual == null) {
                return new RuleResult(withRecovery(clock, null), resolved(
                        openIncident, metric.observedAt(), rule.metricName(), value,
                        rule.warningThreshold(), metric.metricId(), metric.sourceEventId()));
            }
            return new RuleResult(withRecovery(clock, null), updated(
                    openIncident, actual, metric.observedAt(), rule.metricName(), value,
                    threshold(rule, actual), metric.metricId(), metric.sourceEventId()));
        }
        return new RuleResult(withRecovery(clock, null), null);
    }

    private RuleResult evaluateConnectionFailure(
            RuleClock previous,
            Incident openIncident,
            MetricRiskObservation metric
    ) {
        Instant at = observationAt(previous, openIncident, metric.observedAt());
        boolean gap = hasGap(previous, at);
        if (metric.outcome() == CollectionOutcome.PARTIAL_FAILURE) {
            return new RuleResult(previous.resetAt(at, metric.metricId()), null);
        }
        if (metric.outcome() == CollectionOutcome.CONNECTION_FAILED) {
            Instant fatal = candidate(gap ? null : previous.fatalCandidateSince(), true, at);
            RuleClock clock = new RuleClock(
                    RuleId.CONNECTION_FAILURE, null, null, fatal, null, at, metric.metricId());
            if (openIncident == null && due(fatal, at, SYSTEM_RECOVERY_SECONDS)) {
                return new RuleResult(clock, opened(
                        RuleId.CONNECTION_FAILURE,
                        IncidentSeverity.FATAL,
                        at,
                        "connectionStatus",
                        null,
                        null,
                        metric.metricId(),
                        metric.sourceEventId(),
                        "Database connection failed continuously"));
            }
            return new RuleResult(clock, null);
        }

        Instant recovery = openIncident == null || gap
                ? (openIncident == null ? null : at)
                : (previous.recoverySince() == null ? at : previous.recoverySince());
        RuleClock clock = new RuleClock(
                RuleId.CONNECTION_FAILURE, null, null, null, recovery, at, metric.metricId());
        if (openIncident != null && due(recovery, at, SYSTEM_RECOVERY_SECONDS)) {
            return new RuleResult(withRecovery(clock, null), resolved(
                    openIncident, at, "connectionStatus", null, null,
                    metric.metricId(), metric.sourceEventId()));
        }
        return new RuleResult(clock, null);
    }

    private RuleResult evaluateMetricStale(
            int staleAfterSeconds,
            RuleClock previous,
            Incident openIncident,
            MetricRiskObservation metric,
            boolean fresh
    ) {
        Instant at = observationAt(previous, openIncident, metric.observedAt());
        boolean gap = hasGap(previous, at);
        if (openIncident == null && !fresh) {
            Instant dueAt = metric.observedAt().plusSeconds(staleAfterSeconds);
            BigDecimal seconds = BigDecimal.valueOf(staleAfterSeconds);
            return new RuleResult(
                    new RuleClock(
                            RuleId.COLLECTION_STALE, null, dueAt, null, null, dueAt, metric.metricId()),
                    opened(
                            RuleId.COLLECTION_STALE,
                            IncidentSeverity.CRITICAL,
                            dueAt,
                            "collectionAgeSeconds",
                            seconds,
                            seconds,
                            metric.metricId(),
                            metric.sourceEventId(),
                            "Collection is stale"));
        }
        if (openIncident == null) {
            return new RuleResult(previous.resetAt(at, metric.metricId()), null);
        }

        Instant recovery = null;
        if (metric.outcome() == CollectionOutcome.SUCCESS && fresh) {
            recovery = gap || previous.recoverySince() == null ? at : previous.recoverySince();
        }
        RuleClock clock = new RuleClock(
                RuleId.COLLECTION_STALE,
                null,
                previous.criticalCandidateSince(),
                null,
                recovery,
                at,
                metric.metricId());
        if (recovery != null && due(recovery, at, SYSTEM_RECOVERY_SECONDS)) {
            return new RuleResult(
                    new RuleClock(RuleId.COLLECTION_STALE, null, null, null, null, at, metric.metricId()),
                    resolved(
                            openIncident,
                            at,
                            "collectionAgeSeconds",
                            BigDecimal.ZERO,
                            BigDecimal.valueOf(staleAfterSeconds),
                            metric.metricId(),
                            metric.sourceEventId()));
        }
        return new RuleResult(clock, null);
    }

    private boolean inputsSupportInfo(
            RiskEngineSnapshot snapshot,
            MetricRiskObservation metric,
            boolean fresh
    ) {
        if (metric.outcome() != CollectionOutcome.SUCCESS || !fresh) {
            return false;
        }
        for (RiskRule rule : snapshot.policy().rules()) {
            if (!rule.enabled()) {
                continue;
            }
            BigDecimal value = rule.ruleId() == RuleId.CONNECTION_RATIO
                    ? metric.activeConnectionsRatio() : metric.slowQueriesPerSecond();
            if (value == null) {
                return false;
            }
        }
        return true;
    }

    private RiskLevel projectedRisk(
            Map<RuleId, Incident> openIncidents,
            List<IncidentTransition> transitions
    ) {
        EnumMap<RuleId, IncidentSeverity> projected = new EnumMap<>(RuleId.class);
        openIncidents.forEach((rule, incident) -> projected.put(rule, incident.severity()));
        for (IncidentTransition transition : transitions) {
            if (transition.kind() == IncidentTransitionKind.RESOLVED) {
                projected.remove(transition.ruleId());
            } else {
                projected.put(transition.ruleId(), transition.severity());
            }
        }
        return projected.values().stream()
                .max(java.util.Comparator.comparingInt(this::rank))
                .map(IncidentSeverity::asRiskLevel)
                .orElse(null);
    }

    private IncidentTransition opened(
            RuleId ruleId,
            IncidentSeverity severity,
            Instant at,
            String metricName,
            BigDecimal metricValue,
            BigDecimal threshold,
            Long sourceMetricId,
            java.util.UUID sourceEventId,
            String message
    ) {
        return new IncidentTransition(
                IncidentTransitionKind.OPENED,
                null,
                ruleId,
                ruleType(ruleId),
                null,
                severity,
                null,
                at,
                metricName,
                metricValue,
                threshold,
                sourceMetricId,
                sourceEventId,
                message,
                1L,
                null);
    }

    private IncidentTransition updated(
            Incident incident,
            IncidentSeverity severity,
            Instant at,
            String metricName,
            BigDecimal metricValue,
            BigDecimal threshold,
            Long sourceMetricId,
            java.util.UUID sourceEventId
    ) {
        SeverityTransition direction = rank(severity) > rank(incident.severity())
                ? SeverityTransition.INCREASED : SeverityTransition.DECREASED;
        return new IncidentTransition(
                IncidentTransitionKind.UPDATED,
                incident.incidentId(),
                incident.ruleId(),
                incident.ruleType(),
                incident.severity(),
                severity,
                direction,
                at,
                metricName,
                metricValue,
                threshold,
                sourceMetricId,
                sourceEventId,
                incident.ruleId() + " changed to " + severity,
                RiskState.increment(incident.incidentVersion(), "incidentVersion"),
                null);
    }

    private IncidentTransition resolved(
            Incident incident,
            Instant at,
            String metricName,
            BigDecimal metricValue,
            BigDecimal threshold,
            Long sourceMetricId,
            java.util.UUID sourceEventId
    ) {
        return new IncidentTransition(
                IncidentTransitionKind.RESOLVED,
                incident.incidentId(),
                incident.ruleId(),
                incident.ruleType(),
                incident.severity(),
                incident.severity(),
                null,
                at,
                metricName,
                metricValue,
                threshold,
                sourceMetricId,
                sourceEventId,
                incident.ruleId() + " recovered",
                RiskState.increment(incident.incidentVersion(), "incidentVersion"),
                ResolutionReason.RECOVERED);
    }

    private RuleType ruleType(RuleId ruleId) {
        return switch (ruleId) {
            case CONNECTION_RATIO -> RuleType.CONNECTION_RATIO_EXCEEDED;
            case SLOW_QUERY_RATE -> RuleType.SLOW_QUERIES_HIGH;
            case CONNECTION_FAILURE -> RuleType.CONNECTION_FAILURE;
            case COLLECTION_STALE -> RuleType.COLLECTION_STALE;
        };
    }

    private IncidentSeverity thresholdSeverity(RiskRule rule, BigDecimal value) {
        if (rule.fatalThreshold() != null && value.compareTo(rule.fatalThreshold()) >= 0) {
            return IncidentSeverity.FATAL;
        }
        if (value.compareTo(rule.criticalThreshold()) >= 0) {
            return IncidentSeverity.CRITICAL;
        }
        if (value.compareTo(rule.warningThreshold()) >= 0) {
            return IncidentSeverity.WARNING;
        }
        return null;
    }

    private BigDecimal threshold(RiskRule rule, IncidentSeverity severity) {
        return switch (severity) {
            case WARNING -> rule.warningThreshold();
            case CRITICAL -> rule.criticalThreshold();
            case FATAL -> rule.fatalThreshold();
        };
    }

    private IncidentSeverity highestMatured(
            Instant warning,
            Instant critical,
            Instant fatal,
            Instant at,
            int sustainSeconds
    ) {
        if (due(fatal, at, sustainSeconds)) {
            return IncidentSeverity.FATAL;
        }
        if (due(critical, at, sustainSeconds)) {
            return IncidentSeverity.CRITICAL;
        }
        if (due(warning, at, sustainSeconds)) {
            return IncidentSeverity.WARNING;
        }
        return null;
    }

    private Instant candidate(Instant previous, boolean matches, Instant at) {
        if (!matches) {
            return null;
        }
        return previous == null ? at : previous;
    }

    private boolean hasGap(RuleClock clock, Instant at) {
        return clock.lastObservedAt() != null
                && at.isAfter(clock.lastObservedAt().plusSeconds(MAX_OBSERVATION_GAP_SECONDS));
    }

    private Instant observationAt(
            RuleClock clock,
            Incident openIncident,
            Instant observedAt
    ) {
        Instant result = later(observedAt, clock.lastObservedAt());
        return openIncident == null ? result : later(result, openIncident.lastObservedAt());
    }

    private Instant later(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private boolean due(Instant since, Instant at, int seconds) {
        return since != null && !at.isBefore(since.plusSeconds(seconds));
    }

    private int rank(IncidentSeverity severity) {
        return severity == null ? -1 : severity.ordinal();
    }

    private ConnectionStatus connectionStatus(CollectionOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> ConnectionStatus.UP;
            case CONNECTION_FAILED -> ConnectionStatus.DOWN;
            case PARTIAL_FAILURE -> ConnectionStatus.UNKNOWN;
        };
    }

    private RuleClock withRecovery(RuleClock clock, Instant recovery) {
        return new RuleClock(
                clock.ruleId(),
                clock.warningCandidateSince(),
                clock.criticalCandidateSince(),
                clock.fatalCandidateSince(),
                recovery,
                clock.lastObservedAt(),
                clock.lastMetricId());
    }

    private EnumMap<RuleId, RuleClock> clocks(Map<RuleId, RuleClock> source) {
        EnumMap<RuleId, RuleClock> result = new EnumMap<>(RuleId.class);
        for (RuleId ruleId : RuleId.values()) {
            result.put(ruleId, source.getOrDefault(ruleId, RuleClock.empty(ruleId)));
        }
        return result;
    }

    private void add(List<IncidentTransition> transitions, IncidentTransition transition) {
        if (transition != null) {
            transitions.add(transition);
        }
    }

    private record RuleResult(RuleClock clock, IncidentTransition transition) {
    }
}
