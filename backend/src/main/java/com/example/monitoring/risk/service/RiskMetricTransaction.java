package com.example.monitoring.risk.service;

import com.example.monitoring.common.outbox.ProcessedEventStore;
import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.dto.MetricResponseDto;
import com.example.monitoring.metric.MetricQueryService;
import com.example.monitoring.realtime.event.MetricCollectedPayloadV1;
import com.example.monitoring.risk.engine.CollectionOutcome;
import com.example.monitoring.risk.engine.MetricRiskObservation;
import com.example.monitoring.risk.engine.RiskEvaluation;
import com.example.monitoring.risk.engine.RiskState;
import com.example.monitoring.risk.engine.RiskStateMachine;
import com.example.monitoring.risk.persistence.RiskJdbcStore;
import com.example.monitoring.risk.persistence.RiskMutationLock;
import com.example.monitoring.risk.persistence.RiskPersistenceInvariantException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

@Service
public class RiskMetricTransaction {

    public static final String CONSUMER_GROUP = "cg:risk";

    private final ProcessedEventStore processedEvents;
    private final RiskJdbcStore store;
    private final MetricQueryService metrics;
    private final RiskStateMachine stateMachine;
    private final RiskTransitionWriter writer;
    private final Clock clock;

    public RiskMetricTransaction(
            ProcessedEventStore processedEvents,
            RiskJdbcStore store,
            MetricQueryService metrics,
            RiskStateMachine stateMachine,
            RiskTransitionWriter writer,
            Clock clock
    ) {
        this.processedEvents = Objects.requireNonNull(processedEvents, "processedEvents");
        this.store = Objects.requireNonNull(store, "store");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public Outcome process(String sourceStream, MetricCollectedPayloadV1 event) {
        if (sourceStream == null || sourceStream.isBlank()) {
            throw new IllegalArgumentException("sourceStream must not be blank");
        }
        MetricCollectedPayloadV1 required = Objects.requireNonNull(event, "event");
        if (!processedEvents.markProcessed(sourceStream, CONSUMER_GROUP, required.eventId())) {
            return Outcome.DUPLICATE;
        }

        Optional<RiskMutationLock> maybeLocked = store.lockForMutation(required.databaseConfigId());
        if (maybeLocked.isEmpty()) {
            return Outcome.IGNORED;
        }
        RiskMutationLock locked = maybeLocked.get();
        requireConsistentLock(locked);
        if (required.configVersion() != locked.target().configVersion()
                || !locked.target().enabled()
                || locked.target().deleted()) {
            return Outcome.IGNORED;
        }

        Instant observedAt = millis(required.collectionAttemptTime());
        Instant evaluatedAt = millis(clock.instant());
        RiskState state = locked.state();
        if (observedAt.isBefore(state.activationAt())
                || observedAt.isAfter(evaluatedAt)
                || !isNewer(state, observedAt, required.metricId())) {
            return Outcome.IGNORED;
        }

        Optional<MetricResponseDto> maybeLatest = metrics.latest(
                required.databaseConfigId(), required.configVersion());
        if (maybeLatest.isEmpty() || !matches(required, maybeLatest.get())) {
            return Outcome.IGNORED;
        }
        MetricResponseDto latest = maybeLatest.get();
        if (evaluatedAt.isAfter(observedAt.plusSeconds(locked.policy().staleAfterSeconds()))) {
            return Outcome.IGNORED;
        }

        MetricRiskObservation observation = new MetricRiskObservation(
                required.metricId(),
                required.eventId(),
                observedAt,
                evaluatedAt,
                outcome(latest.getCollectionStatus()),
                connectionRatio(latest.getActiveConnections(), latest.getMaxConnections()),
                decimal(latest.getSlowQueriesPerSecond()));
        RiskEvaluation evaluation;
        try {
            evaluation = stateMachine.evaluate(locked.engineSnapshot(), observation);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new RiskPersistenceInvariantException(
                    "Risk metric evaluation rejected authoritative state", exception);
        }
        writer.persistMetric(locked, evaluation);
        return Outcome.APPLIED;
    }

    private void requireConsistentLock(RiskMutationLock locked) {
        long databaseConfigId = locked.target().databaseConfigId();
        RiskState state = locked.state();
        if (state.databaseConfigId() != databaseConfigId
                || locked.policy().databaseConfigId() != databaseConfigId
                || state.configVersion() != locked.target().configVersion()
                || state.enabled() != locked.target().enabled()
                || state.deleted() != locked.target().deleted()) {
            throw new RiskPersistenceInvariantException(
                    "Risk mutation lock does not describe one coherent target");
        }
    }

    private boolean isNewer(RiskState state, Instant observedAt, long metricId) {
        if (state.lastAttemptAt() == null) {
            return true;
        }
        int timeOrder = observedAt.compareTo(state.lastAttemptAt());
        if (timeOrder != 0) {
            return timeOrder > 0;
        }
        return state.latestMetricId() != null && metricId > state.latestMetricId();
    }

    private boolean matches(MetricCollectedPayloadV1 event, MetricResponseDto latest) {
        return Objects.equals(latest.getId(), event.metricId())
                && Objects.equals(latest.getDatabaseConfigId(), event.databaseConfigId())
                && Objects.equals(latest.getConfigVersion(), event.configVersion())
                && latest.getCollectionAttemptTime() != null
                && millis(latest.getCollectionAttemptTime()).equals(millis(event.collectionAttemptTime()));
    }

    private CollectionOutcome outcome(CollectionStatus status) {
        if (status == null) {
            throw new RiskPersistenceInvariantException(
                    "Authoritative metric is missing collection status");
        }
        return CollectionOutcome.valueOf(status.name());
    }

    private BigDecimal connectionRatio(Long activeConnections, Long maxConnections) {
        if (activeConnections == null || activeConnections < 0
                || maxConnections == null || maxConnections <= 0) {
            return null;
        }
        return BigDecimal.valueOf(activeConnections)
                .divide(BigDecimal.valueOf(maxConnections), MathContext.DECIMAL128);
    }

    private BigDecimal decimal(Double value) {
        return value == null || !Double.isFinite(value) || value < 0
                ? null : BigDecimal.valueOf(value);
    }

    private Instant millis(Instant value) {
        return Objects.requireNonNull(value, "instant").truncatedTo(ChronoUnit.MILLIS);
    }

    public enum Outcome {
        APPLIED,
        DUPLICATE,
        IGNORED
    }
}
