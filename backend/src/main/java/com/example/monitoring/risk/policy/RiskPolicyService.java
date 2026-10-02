package com.example.monitoring.risk.policy;

import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.ApiId;
import com.example.monitoring.common.api.FieldErrorResponse;
import com.example.monitoring.common.outbox.OutboxEventType;
import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.domain.AuditTargetType;
import com.example.monitoring.partc.api.PartCQueryValidator;
import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.IncidentEventPayload;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.ResolutionReason;
import com.example.monitoring.risk.contract.RiskLevel;
import com.example.monitoring.risk.contract.RiskPolicy;
import com.example.monitoring.risk.contract.RiskRule;
import com.example.monitoring.risk.contract.StatusSnapshot;
import com.example.monitoring.risk.engine.RiskState;
import com.example.monitoring.risk.persistence.RiskJdbcStore;
import com.example.monitoring.risk.persistence.RiskMutationLock;
import com.example.monitoring.risk.persistence.RiskOutboxAppender;
import com.example.monitoring.risk.persistence.RiskPersistenceInvariantException;
import com.example.monitoring.service.AuditEventService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class RiskPolicyService {

    private final RiskPolicyQueryRepository queryRepository;
    private final RiskJdbcStore store;
    private final RiskOutboxAppender outbox;
    private final AuditEventService auditEvents;
    private final PartCQueryValidator validator;
    private final Clock clock;

    public RiskPolicyService(
            RiskPolicyQueryRepository queryRepository,
            RiskJdbcStore store,
            RiskOutboxAppender outbox,
            AuditEventService auditEvents,
            PartCQueryValidator validator,
            Clock clock
    ) {
        this.queryRepository = queryRepository;
        this.store = store;
        this.outbox = outbox;
        this.auditEvents = auditEvents;
        this.validator = validator;
        this.clock = clock;
    }

    public RiskPolicy get(String rawId) {
        long databaseConfigId = validator.requiredId(rawId, "id");
        return queryRepository.findCurrent(databaseConfigId)
                .orElseThrow(RiskPolicyService::databaseNotFound);
    }

    @Transactional
    public RiskPolicy update(String rawId, PolicyWrite request) {
        long databaseConfigId = validator.requiredId(rawId, "id");
        PreparedPolicy prepared = prepare(databaseConfigId, request);
        RiskMutationLock locked = store.lockForMutation(databaseConfigId)
                .orElseThrow(RiskPolicyService::databaseNotFound);
        requireMutableTarget(locked, databaseConfigId);
        if (locked.policy().version() != prepared.expectedVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "POLICY_VERSION_CONFLICT",
                    "정책 버전이 일치하지 않습니다.");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        RiskPolicy policy = prepared.materialize(databaseConfigId, now);

        List<Incident> configurable = locked.openIncidents().stream()
                .filter(incident -> incident.ruleId().configurable())
                .sorted(Comparator.comparing(incident -> incident.incidentId().toString()))
                .toList();
        List<Incident> remaining = locked.openIncidents().stream()
                .filter(incident -> !incident.ruleId().configurable())
                .sorted(Comparator.comparing(incident -> incident.incidentId().toString()))
                .toList();
        List<Incident> resolved = configurable.stream()
                .map(incident -> resolve(incident, now))
                .toList();
        RiskState nextState = nextState(locked.state(), remaining, now);
        StatusSnapshot status = status(nextState, remaining);

        store.updatePolicy(policy, prepared.expectedVersion());
        store.clearConfigurableRuleClocks(databaseConfigId);
        for (Incident incident : resolved) {
            store.updateIncident(incident, null);
            store.cancelPendingDeliveries(incident.incidentId());
            outbox.appendIncident(
                    UUID.randomUUID(),
                    OutboxEventType.INCIDENT_RESOLVED,
                    IncidentEventPayload.resolved(incident, now, null));
        }
        store.updateState(nextState);
        outbox.appendStatus(UUID.randomUUID(), status);
        auditEvents.successCurrent(
                AuditAction.POLICY_UPDATED,
                AuditTargetType.POLICY,
                Long.toString(databaseConfigId),
                databaseConfigId,
                "Risk policy updated to version " + policy.version());
        return policy;
    }

    private PreparedPolicy prepare(long databaseConfigId, PolicyWrite request) {
        if (request == null) {
            throw invalid("body", "REQUIRED", "요청 본문은 필수입니다.");
        }
        if (request.version() == null) {
            throw invalid("version", "REQUIRED", "version은(는) 필수입니다.");
        }
        long expectedVersion = validator.requiredId(request.version().toString(), "version");
        if (expectedVersion == ApiId.MAX_SAFE_INTEGER) {
            throw invalid("version", "OUT_OF_RANGE", "version을 안전하게 증가시킬 수 없습니다.");
        }
        int staleAfterSeconds = requiredRange(
                request.staleAfterSeconds(), "staleAfterSeconds", 30, 300);
        int notificationCooldownSeconds = requiredRange(
                request.notificationCooldownSeconds(), "notificationCooldownSeconds", 60, 3600);
        if (request.rules() == null) {
            throw invalid("rules", "REQUIRED", "rules은(는) 필수입니다.");
        }
        List<RiskRule> rules = new java.util.ArrayList<>(request.rules().size());
        for (int index = 0; index < request.rules().size(); index++) {
            rules.add(rule(request.rules().get(index), index));
        }
        try {
            RiskPolicy validated = new RiskPolicy(
                    databaseConfigId,
                    expectedVersion + 1L,
                    staleAfterSeconds,
                    notificationCooldownSeconds,
                    rules,
                    Instant.EPOCH);
            return new PreparedPolicy(
                    expectedVersion,
                    staleAfterSeconds,
                    notificationCooldownSeconds,
                    validated.rules());
        } catch (IllegalArgumentException exception) {
            throw invalid("rules", "INVALID_VALUE", "rules 구성을 확인해 주세요.");
        }
    }

    private RiskRule rule(PolicyRuleWrite write, int index) {
        String field = "rules[" + index + "]";
        if (write == null) {
            throw invalid(field, "REQUIRED", field + "은(는) 필수입니다.");
        }
        if (write.ruleId() == null) {
            throw invalid(field + ".ruleId", "REQUIRED", "ruleId은(는) 필수입니다.");
        }
        if (write.metricName() == null) {
            throw invalid(field + ".metricName", "REQUIRED", "metricName은(는) 필수입니다.");
        }
        if (write.operator() == null) {
            throw invalid(field + ".operator", "REQUIRED", "operator은(는) 필수입니다.");
        }
        if (write.warningThreshold() == null) {
            throw invalid(field + ".warningThreshold", "REQUIRED", "warningThreshold은(는) 필수입니다.");
        }
        if (write.criticalThreshold() == null) {
            throw invalid(field + ".criticalThreshold", "REQUIRED", "criticalThreshold은(는) 필수입니다.");
        }
        if (write.sustainSeconds() == null) {
            throw invalid(field + ".sustainSeconds", "REQUIRED", "sustainSeconds은(는) 필수입니다.");
        }
        if (write.recoverySeconds() == null) {
            throw invalid(field + ".recoverySeconds", "REQUIRED", "recoverySeconds은(는) 필수입니다.");
        }
        if (write.enabled() == null) {
            throw invalid(field + ".enabled", "REQUIRED", "enabled은(는) 필수입니다.");
        }
        try {
            return new RiskRule(
                    write.ruleId(),
                    write.metricName(),
                    write.operator(),
                    write.warningThreshold(),
                    write.criticalThreshold(),
                    write.fatalThreshold(),
                    write.sustainSeconds(),
                    write.recoverySeconds(),
                    write.enabled());
        } catch (IllegalArgumentException exception) {
            throw invalid(field, "INVALID_VALUE", "규칙 구성을 확인해 주세요.");
        }
    }

    private int requiredRange(Integer value, String field, int minimum, int maximum) {
        if (value == null) {
            throw invalid(field, "REQUIRED", field + "은(는) 필수입니다.");
        }
        if (value < minimum || value > maximum) {
            throw invalid(field, "OUT_OF_RANGE",
                    field + "은(는) " + minimum + "~" + maximum + " 범위여야 합니다.");
        }
        return value;
    }

    private void requireMutableTarget(RiskMutationLock locked, long databaseConfigId) {
        if (locked.target().deleted()) {
            throw databaseNotFound();
        }
        if (locked.target().databaseConfigId() != databaseConfigId
                || locked.state().databaseConfigId() != databaseConfigId
                || locked.policy().databaseConfigId() != databaseConfigId
                || locked.target().configVersion() != locked.state().configVersion()
                || locked.target().enabled() != locked.state().enabled()
                || locked.state().deleted()) {
            throw new RiskPersistenceInvariantException("Policy mutation lock is inconsistent");
        }
    }

    private Incident resolve(Incident incident, Instant now) {
        if (now.isBefore(incident.lastObservedAt())) {
            throw new RiskPersistenceInvariantException("Policy update precedes incident observation time");
        }
        return new Incident(
                incident.incidentId(),
                incident.databaseConfigId(),
                incident.databaseName(),
                incident.ruleId(),
                incident.ruleType(),
                incident.severity(),
                IncidentStatus.RESOLVED,
                incident.openedAt(),
                incident.lastObservedAt(),
                now,
                ResolutionReason.POLICY_CHANGED,
                incident.metricName(),
                incident.metricValue(),
                incident.thresholdValue(),
                incident.sourceMetricId(),
                incident.message(),
                increment(incident.incidentVersion(), "incidentVersion"));
    }

    private RiskState nextState(RiskState current, List<Incident> remaining, Instant now) {
        RiskLevel riskLevel = remaining.stream()
                .map(Incident::severity)
                .max(Comparator.comparingInt(IncidentSeverity::ordinal))
                .map(IncidentSeverity::asRiskLevel)
                .orElse(null);
        return new RiskState(
                current.databaseConfigId(),
                current.configVersion(),
                increment(current.stateVersion(), "stateVersion"),
                current.enabled(),
                current.deleted(),
                current.connectionStatus(),
                current.dataFreshness(),
                riskLevel,
                current.activationAt(),
                current.lastAttemptAt(),
                current.lastSuccessAt(),
                current.latestMetricId(),
                now);
    }

    private StatusSnapshot status(RiskState state, List<Incident> openIncidents) {
        return new StatusSnapshot(
                state.databaseConfigId(),
                state.configVersion(),
                state.deleted(),
                state.enabled(),
                state.connectionStatus(),
                state.dataFreshness(),
                state.riskLevel(),
                state.lastAttemptAt(),
                state.lastSuccessAt(),
                state.latestMetricId(),
                openIncidents.stream().map(Incident::incidentId).toList(),
                state.stateVersion(),
                state.updatedAt());
    }

    private long increment(long value, String field) {
        if (value < 1L || value >= ApiId.MAX_SAFE_INTEGER) {
            throw new RiskPersistenceInvariantException(field + " cannot be incremented safely");
        }
        return value + 1L;
    }

    private static ApiException invalid(String field, String code, String message) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "요청 값을 확인해 주세요.",
                List.of(new FieldErrorResponse(field, code, message)));
    }

    private static ApiException databaseNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND", "DB 설정을 찾을 수 없습니다.");
    }

    private record PreparedPolicy(
            long expectedVersion,
            int staleAfterSeconds,
            int notificationCooldownSeconds,
            List<RiskRule> rules
    ) {
        private PreparedPolicy {
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        }

        private RiskPolicy materialize(long databaseConfigId, Instant updatedAt) {
            return new RiskPolicy(
                    databaseConfigId,
                    expectedVersion + 1L,
                    staleAfterSeconds,
                    notificationCooldownSeconds,
                    rules,
                    updatedAt);
        }
    }
}
