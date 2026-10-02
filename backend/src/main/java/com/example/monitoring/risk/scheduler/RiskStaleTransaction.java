package com.example.monitoring.risk.scheduler;

import com.example.monitoring.risk.contract.DataFreshness;
import com.example.monitoring.risk.engine.RiskEvaluation;
import com.example.monitoring.risk.engine.RiskState;
import com.example.monitoring.risk.engine.RiskStateMachine;
import com.example.monitoring.risk.engine.StaleDueObservation;
import com.example.monitoring.risk.persistence.RiskJdbcStore;
import com.example.monitoring.risk.persistence.RiskMutationLock;
import com.example.monitoring.risk.persistence.RiskPersistenceInvariantException;
import com.example.monitoring.risk.persistence.StaleCandidate;
import com.example.monitoring.risk.service.RiskTransitionWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

@Service
public class RiskStaleTransaction {

    private final RiskJdbcStore store;
    private final RiskStateMachine stateMachine;
    private final RiskTransitionWriter writer;

    public RiskStaleTransaction(
            RiskJdbcStore store,
            RiskStateMachine stateMachine,
            RiskTransitionWriter writer
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Transactional
    public Outcome process(StaleCandidate candidate, Instant scannedAt) {
        StaleCandidate required = Objects.requireNonNull(candidate, "candidate");
        Instant scan = millis(scannedAt);
        Optional<RiskMutationLock> maybeLocked = store.lockForMutation(required.databaseConfigId());
        if (maybeLocked.isEmpty()) {
            return Outcome.IGNORED;
        }
        RiskMutationLock locked = maybeLocked.get();
        requireConsistentLock(locked);
        if (!locked.target().enabled() || locked.target().deleted()) {
            return Outcome.IGNORED;
        }

        RiskState state = locked.state();
        Instant basis = state.lastAttemptAt() == null
                ? state.activationAt() : state.lastAttemptAt();
        Instant dueAt = basis.plusSeconds(locked.policy().staleAfterSeconds())
                .truncatedTo(ChronoUnit.MILLIS);
        if (!dueAt.equals(millis(required.dueAt()))
                || scan.isBefore(dueAt)
                || state.dataFreshness() == DataFreshness.STALE) {
            return Outcome.IGNORED;
        }

        RiskEvaluation evaluation;
        try {
            evaluation = stateMachine.evaluate(
                    locked.engineSnapshot(), new StaleDueObservation(dueAt, scan));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new RiskPersistenceInvariantException(
                    "Risk stale evaluation rejected authoritative state", exception);
        }
        writer.persistTimer(locked, evaluation);
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

    private Instant millis(Instant value) {
        return Objects.requireNonNull(value, "instant").truncatedTo(ChronoUnit.MILLIS);
    }

    public enum Outcome {
        APPLIED,
        IGNORED
    }
}
