package com.example.monitoring.lifecycle.adapter;

import com.example.monitoring.lifecycle.port.MonitoringLifecyclePort;
import com.example.monitoring.lifecycle.port.TargetChange;
import com.example.monitoring.lifecycle.port.TargetChangeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public final class JdbcMonitoringLifecyclePort implements MonitoringLifecyclePort {

    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    static final String REQUIRED_TRANSACTION_MESSAGE =
            "Monitoring lifecycle requires an active writable transaction";

    private final LifecycleJdbcStore store;
    private final LifecycleEventCodec events;
    private final LifecycleOutboxWriter outbox;
    private final TransactionOperations mandatoryTransaction;

    @Autowired
    public JdbcMonitoringLifecyclePort(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this(
                new LifecycleJdbcStore(jdbc),
                new LifecycleEventCodec(objectMapper),
                new LifecycleOutboxWriter(jdbc),
                mandatoryTransaction(transactionManager));
    }

    JdbcMonitoringLifecyclePort(
            LifecycleJdbcStore store,
            LifecycleEventCodec events,
            LifecycleOutboxWriter outbox,
            TransactionOperations mandatoryTransaction
    ) {
        this.store = store;
        this.events = events;
        this.outbox = outbox;
        this.mandatoryTransaction = mandatoryTransaction;
    }

    @Override
    public void applyChange(TargetChange change) {
        requireActiveTransaction();
        mandatoryTransaction.executeWithoutResult(ignored -> {
            requireWritableTransaction();
            applyInTransaction(Objects.requireNonNull(change, "change"));
        });
    }

    private void applyInTransaction(TargetChange change) {
        Instant occurredAt = change.occurredAt().truncatedTo(ChronoUnit.MILLIS);
        validateSafe("databaseConfigId", change.databaseConfigId());
        validateSafe("configVersion", change.configVersion());
        if (change.actorId() != null) {
            validateSafe("actorId", change.actorId());
        }

        LockedTarget target = store.lockTarget(change.databaseConfigId())
                .orElseThrow(() -> invariant("B target row is missing"));
        validateTarget(change, target);

        LockedMonitoringState current = store.lockState(change.databaseConfigId()).orElse(null);
        if (change.changeType() == TargetChangeType.CREATED) {
            applyCreated(change, occurredAt, current);
            return;
        }
        applyExisting(change, occurredAt, requireExistingState(current));
    }

    private void applyCreated(TargetChange change, Instant occurredAt, LockedMonitoringState current) {
        if (change.configVersion() != 1L) {
            throw invariant("CREATED requires configVersion 1");
        }
        if (current != null) {
            throw invariant("CREATED requires no monitoring state");
        }

        MonitoringStateWrite next = state(
                change, 1L, false, change.enabled() ? "NO_DATA" : "PAUSED",
                change.enabled() ? occurredAt : null, occurredAt);
        String policyJson = events.defaultPolicyJson();
        SerializedLifecycleEvent statusEvent = events.statusChanged(next);

        store.insertState(next);
        store.insertDefaultPolicy(change.databaseConfigId(), policyJson, occurredAt);
        outbox.append(statusEvent);
    }

    private void applyExisting(TargetChange change, Instant occurredAt, LockedMonitoringState current) {
        validateExistingTransition(change, current);
        List<LockedIncident> incidents = store.lockOpenIncidents(change.databaseConfigId());
        long nextStateVersion = increment("stateVersion", current.stateVersion());
        String reason = resolutionReason(change.changeType());
        List<IncidentResolution> resolutions = new ArrayList<>(incidents.size());
        List<SerializedLifecycleEvent> incidentEvents = new ArrayList<>(incidents.size());

        for (LockedIncident incident : incidents) {
            if (occurredAt.isBefore(incident.lastObservedAt())) {
                throw invariant("Lifecycle change precedes incident observation time");
            }
            IncidentResolution resolution = new IncidentResolution(
                    incident, increment("incidentVersion", incident.incidentVersion()), reason, occurredAt);
            resolutions.add(resolution);
            incidentEvents.add(events.incidentResolved(resolution));
        }

        boolean deleted = change.changeType() == TargetChangeType.DELETED;
        boolean activates = change.enabled()
                && (change.changeType() == TargetChangeType.UPDATED
                || change.changeType() == TargetChangeType.RESUMED);
        MonitoringStateWrite next = state(
                change,
                nextStateVersion,
                deleted,
                change.enabled() ? "NO_DATA" : "PAUSED",
                activates ? occurredAt : null,
                occurredAt);
        SerializedLifecycleEvent statusEvent = events.statusChanged(next);

        store.updateState(next, current);
        store.deleteRuleStates(change.databaseConfigId());
        for (int index = 0; index < resolutions.size(); index++) {
            IncidentResolution resolution = resolutions.get(index);
            store.resolveIncident(resolution);
            store.cancelPendingDeliveries(resolution.incident().incidentId());
            outbox.append(incidentEvents.get(index));
        }
        outbox.append(statusEvent);
    }

    private void validateTarget(TargetChange change, LockedTarget target) {
        validateSafe("B configVersion", target.configVersion());
        if (target.configVersion() != change.configVersion()
                || target.enabled() != change.enabled()
                || !target.name().equals(change.name())) {
            throw invariant("TargetChange does not match the flushed B row");
        }

        boolean deletion = change.changeType() == TargetChangeType.DELETED;
        if (target.deleted() != deletion) {
            throw invariant("TargetChange deletion type does not match the flushed B row");
        }
        if (change.changeType() == TargetChangeType.PAUSED && change.enabled()) {
            throw invariant("PAUSED requires enabled=false");
        }
        if (change.changeType() == TargetChangeType.RESUMED && !change.enabled()) {
            throw invariant("RESUMED requires enabled=true");
        }
        if (deletion && change.enabled()) {
            throw invariant("DELETED requires enabled=false");
        }
    }

    private void validateExistingTransition(TargetChange change, LockedMonitoringState current) {
        validateSafe("state configVersion", current.configVersion());
        validateSafe("stateVersion", current.stateVersion());
        if (current.deleted()) {
            throw invariant("Monitoring state is already deleted");
        }
        long expectedConfigVersion = increment("configVersion", current.configVersion());
        if (change.configVersion() != expectedConfigVersion) {
            throw invariant("TargetChange configVersion must advance by exactly one");
        }

        switch (change.changeType()) {
            case UPDATED -> {
                if (current.enabled() != change.enabled()) {
                    throw invariant("UPDATED cannot change enabled state");
                }
            }
            case PAUSED -> {
                if (!current.enabled() || change.enabled()) {
                    throw invariant("PAUSED requires an enabled state and enabled=false result");
                }
            }
            case RESUMED -> {
                if (current.enabled() || !change.enabled()) {
                    throw invariant("RESUMED requires a paused state and enabled=true result");
                }
            }
            case DELETED -> {
                if (change.enabled()) {
                    throw invariant("DELETED requires enabled=false");
                }
            }
            case CREATED -> throw invariant("CREATED cannot update an existing state");
        }
    }

    private MonitoringStateWrite state(
            TargetChange change,
            long stateVersion,
            boolean deleted,
            String dataFreshness,
            Instant activationAt,
            Instant updatedAt
    ) {
        return new MonitoringStateWrite(
                change.databaseConfigId(),
                change.configVersion(),
                stateVersion,
                change.enabled(),
                deleted,
                dataFreshness,
                activationAt,
                updatedAt);
    }

    private LockedMonitoringState requireExistingState(LockedMonitoringState current) {
        if (current == null) {
            throw invariant("Lifecycle change requires an existing monitoring state");
        }
        return current;
    }

    private String resolutionReason(TargetChangeType changeType) {
        return switch (changeType) {
            case UPDATED, RESUMED -> "CONFIG_CHANGED";
            case PAUSED -> "MONITORING_PAUSED";
            case DELETED -> "TARGET_DELETED";
            case CREATED -> throw invariant("CREATED has no incident resolution reason");
        };
    }

    private long increment(String field, long value) {
        validateSafe(field, value);
        if (value == MAX_SAFE_INTEGER) {
            throw invariant(field + " cannot be incremented safely");
        }
        return value + 1L;
    }

    private void validateSafe(String field, long value) {
        if (value < 1L || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(field + " must be a positive JavaScript-safe integer");
        }
    }

    private void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(REQUIRED_TRANSACTION_MESSAGE);
        }
    }

    private void requireWritableTransaction() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(REQUIRED_TRANSACTION_MESSAGE);
        }
    }

    static TransactionTemplate mandatoryTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        return transaction;
    }

    private IllegalStateException invariant(String message) {
        return new IllegalStateException("Monitoring lifecycle invariant failed: " + message);
    }
}
