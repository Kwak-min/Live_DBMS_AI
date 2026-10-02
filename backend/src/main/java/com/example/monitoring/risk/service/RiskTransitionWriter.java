package com.example.monitoring.risk.service;

import com.example.monitoring.common.outbox.OutboxEventType;
import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.IncidentEventPayload;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.StatusSnapshot;
import com.example.monitoring.risk.engine.IncidentTransition;
import com.example.monitoring.risk.engine.IncidentTransitionKind;
import com.example.monitoring.risk.engine.RiskEvaluation;
import com.example.monitoring.risk.engine.RuleClock;
import com.example.monitoring.risk.persistence.RiskJdbcStore;
import com.example.monitoring.risk.persistence.RiskMutationLock;
import com.example.monitoring.risk.persistence.RiskOutboxAppender;
import com.example.monitoring.risk.persistence.RiskPersistenceInvariantException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class RiskTransitionWriter {

    private final RiskJdbcStore store;
    private final RiskOutboxAppender outbox;
    private final Supplier<UUID> ids;

    @Autowired
    public RiskTransitionWriter(RiskJdbcStore store, RiskOutboxAppender outbox) {
        this(store, outbox, UUID::randomUUID);
    }

    RiskTransitionWriter(
            RiskJdbcStore store,
            RiskOutboxAppender outbox,
            Supplier<UUID> ids
    ) {
        this.store = store;
        this.outbox = outbox;
        this.ids = ids;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StatusSnapshot persistMetric(
            RiskMutationLock locked,
            RiskEvaluation evaluation
    ) {
        int touched = store.touchOpenIncidents(
                locked.target().databaseConfigId(), evaluation.state().lastAttemptAt());
        if (touched != locked.openIncidents().size()) {
            throw invariant("metric observation could not touch every locked OPEN incident");
        }
        return persist(locked, evaluation);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StatusSnapshot persistTimer(
            RiskMutationLock locked,
            RiskEvaluation evaluation
    ) {
        return persist(locked, evaluation);
    }

    private StatusSnapshot persist(
            RiskMutationLock locked,
            RiskEvaluation evaluation
    ) {
        requireMatchingTarget(locked, evaluation);
        for (RuleId ruleId : RuleId.values()) {
            RuleClock clock = evaluation.ruleClocks().get(ruleId);
            if (clock == null) {
                throw invariant("evaluation omitted rule clock " + ruleId);
            }
            store.saveRuleClock(
                    locked.target().databaseConfigId(), clock, evaluation.state().updatedAt());
        }

        EnumMap<RuleId, Incident> projected = new EnumMap<>(RuleId.class);
        for (Incident incident : locked.openIncidents()) {
            projected.put(incident.ruleId(), incident);
        }

        for (IncidentTransition transition : evaluation.incidentTransitions()) {
            Incident incident = materialize(locked, projected, transition);
            if (transition.kind() == IncidentTransitionKind.OPENED) {
                store.insertIncident(incident, transition.sourceEventId());
            } else {
                store.updateIncident(incident, transition.sourceEventId());
            }
            appendIncidentEvent(transition, incident);
            if (incident.status() == IncidentStatus.RESOLVED) {
                projected.remove(incident.ruleId());
            } else {
                projected.put(incident.ruleId(), incident);
            }
        }

        store.updateState(evaluation.state());
        List<UUID> openIds = projected.values().stream()
                .map(Incident::incidentId)
                .toList();
        StatusSnapshot status = new StatusSnapshot(
                evaluation.state().databaseConfigId(),
                evaluation.state().configVersion(),
                evaluation.state().deleted(),
                evaluation.state().enabled(),
                evaluation.state().connectionStatus(),
                evaluation.state().dataFreshness(),
                evaluation.state().riskLevel(),
                evaluation.state().lastAttemptAt(),
                evaluation.state().lastSuccessAt(),
                evaluation.state().latestMetricId(),
                openIds,
                evaluation.state().stateVersion(),
                evaluation.state().updatedAt());
        outbox.appendStatus(ids.get(), status);
        return status;
    }

    private Incident materialize(
            RiskMutationLock locked,
            EnumMap<RuleId, Incident> projected,
            IncidentTransition transition
    ) {
        Incident current = projected.get(transition.ruleId());
        if (transition.kind() == IncidentTransitionKind.OPENED) {
            if (current != null || transition.incidentId() != null) {
                throw invariant("OPENED transition conflicts with an existing incident");
            }
            return new Incident(
                    ids.get(),
                    locked.target().databaseConfigId(),
                    locked.target().databaseName(),
                    transition.ruleId(),
                    transition.ruleType(),
                    transition.severity(),
                    IncidentStatus.OPEN,
                    transition.occurredAt(),
                    transition.occurredAt(),
                    null,
                    null,
                    transition.metricName(),
                    transition.metricValue(),
                    transition.thresholdValue(),
                    transition.sourceMetricId(),
                    transition.message(),
                    transition.nextIncidentVersion());
        }
        if (current == null || !current.incidentId().equals(transition.incidentId())) {
            throw invariant("incident transition does not match the locked OPEN incident");
        }
        boolean resolved = transition.kind() == IncidentTransitionKind.RESOLVED;
        return new Incident(
                current.incidentId(),
                current.databaseConfigId(),
                current.databaseName(),
                current.ruleId(),
                current.ruleType(),
                transition.severity(),
                resolved ? IncidentStatus.RESOLVED : IncidentStatus.OPEN,
                current.openedAt(),
                transition.occurredAt(),
                resolved ? transition.occurredAt() : null,
                resolved ? transition.resolutionReason() : null,
                transition.metricName(),
                transition.metricValue(),
                transition.thresholdValue(),
                transition.sourceMetricId(),
                transition.message(),
                transition.nextIncidentVersion());
    }

    private void appendIncidentEvent(IncidentTransition transition, Incident incident) {
        UUID eventId = ids.get();
        Instant occurredAt = transition.occurredAt();
        switch (transition.kind()) {
            case OPENED -> outbox.appendIncident(
                    eventId,
                    OutboxEventType.INCIDENT_CREATED,
                    IncidentEventPayload.created(
                            incident, occurredAt, transition.sourceEventId()));
            case UPDATED -> outbox.appendIncident(
                    eventId,
                    OutboxEventType.INCIDENT_UPDATED,
                    IncidentEventPayload.updated(
                            incident,
                            occurredAt,
                            transition.sourceEventId(),
                            transition.severityTransition()));
            case RESOLVED -> outbox.appendIncident(
                    eventId,
                    OutboxEventType.INCIDENT_RESOLVED,
                    IncidentEventPayload.resolved(
                            incident, occurredAt, transition.sourceEventId()));
        }
    }

    private void requireMatchingTarget(
            RiskMutationLock locked,
            RiskEvaluation evaluation
    ) {
        if (locked.target().databaseConfigId() != evaluation.state().databaseConfigId()
                || locked.state().configVersion() != evaluation.state().configVersion()
                || locked.state().stateVersion() + 1L != evaluation.state().stateVersion()) {
            throw invariant("evaluation does not advance the locked monitoring state exactly once");
        }
    }

    private RiskPersistenceInvariantException invariant(String message) {
        return new RiskPersistenceInvariantException(
                "Risk transition persistence failed: " + message);
    }
}
