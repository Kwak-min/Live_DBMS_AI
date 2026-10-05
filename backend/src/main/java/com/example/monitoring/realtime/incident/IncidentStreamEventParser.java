package com.example.monitoring.realtime.incident;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import com.example.monitoring.common.stream.StrictEventFields;
import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.ResolutionReason;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleType;
import com.example.monitoring.risk.contract.SeverityTransition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public final class IncidentStreamEventParser {

    private static final String CREATED = "IncidentCreatedEvent";
    private static final String UPDATED = "IncidentUpdatedEvent";
    private static final String RESOLVED = "IncidentResolvedEvent";
    private static final Set<String> EVENT_TYPES = Set.of(CREATED, UPDATED, RESOLVED);
    private final StrictEventFields fields;

    public IncidentStreamEventParser(ObjectMapper objectMapper) {
        fields = new StrictEventFields(objectMapper);
    }

    public IncidentStreamEvent parse(byte[] payload) {
        ObjectNode root = fields.parseObject(payload);
        if (fields.requiredInt(root, "schemaVersion") != 1) {
            throw fields.invalid(root, "UNSUPPORTED_SCHEMA_VERSION", "Unsupported incident schemaVersion");
        }
        String eventType = fields.requiredText(root, "eventType", 1, 64);
        if (!EVENT_TYPES.contains(eventType)) {
            throw fields.invalid(root, "UNSUPPORTED_EVENT_TYPE", "Unsupported incident eventType");
        }
        UUID eventId = fields.requiredUuid(root, "eventId");
        Instant publishedAt = fields.requiredInstant(root, "publishedAt");
        Instant timestamp = fields.requiredInstant(root, "timestamp");
        UUID sourceEventId = fields.requiredNullableUuid(root, "sourceEventId");
        try {
            Incident incident = new Incident(
                    fields.requiredUuid(root, "incidentId"),
                    fields.requiredSafeLong(root, "databaseConfigId"),
                    fields.requiredText(root, "databaseName", 1, 100),
                    fields.requiredEnum(root, "ruleId", RuleId.class),
                    fields.requiredEnum(root, "ruleType", RuleType.class),
                    fields.requiredEnum(root, "severity", IncidentSeverity.class),
                    fields.requiredEnum(root, "status", IncidentStatus.class),
                    fields.requiredInstant(root, "openedAt"),
                    fields.requiredInstant(root, "lastObservedAt"),
                    fields.requiredNullableInstant(root, "resolvedAt"),
                    fields.requiredNullableEnum(root, "resolutionReason", ResolutionReason.class),
                    fields.requiredText(root, "metricName", 1, 100),
                    fields.requiredNullableNonNegativeDecimal(root, "metricValue"),
                    fields.requiredNullableNonNegativeDecimal(root, "thresholdValue"),
                    fields.requiredNullableSafeLong(root, "sourceMetricId"),
                    fields.requiredText(root, "message", 1, StrictEventFields.MAX_PAYLOAD_BYTES),
                    fields.requiredSafeLong(root, "incidentVersion"));
            validateEvent(root, eventType, incident);
            SeverityTransition transition = transition(root, eventType);
            return new IncidentStreamEvent(
                    eventId,
                    eventType,
                    publishedAt,
                    timestamp,
                    sourceEventId,
                    incident,
                    transition);
        } catch (InvalidStreamRecordException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw fields.invalid(root, "INVALID_INCIDENT_EVENT", "Incident payload is incoherent", exception);
        }
    }

    private SeverityTransition transition(ObjectNode root, String eventType) {
        if (!UPDATED.equals(eventType)) {
            if (root.has("severityTransition")) {
                throw fields.invalid(
                        root,
                        "INVALID_INCIDENT_TRANSITION",
                        "severityTransition is allowed only on IncidentUpdatedEvent");
            }
            return null;
        }
        try {
            return fields.requiredEnum(root, "severityTransition", SeverityTransition.class);
        } catch (InvalidStreamRecordException exception) {
            throw fields.invalid(
                    root,
                    "INVALID_INCIDENT_TRANSITION",
                    "IncidentUpdatedEvent requires INCREASED or DECREASED severityTransition",
                    exception);
        }
    }

    private void validateEvent(ObjectNode root, String eventType, Incident incident) {
        boolean statusMatches = RESOLVED.equals(eventType)
                ? incident.status() == IncidentStatus.RESOLVED
                : incident.status() == IncidentStatus.OPEN;
        if (!statusMatches || !ruleTypeMatches(incident.ruleId(), incident.ruleType())) {
            throw fields.invalid(root, "INVALID_INCIDENT_EVENT", "Incident event fields are incoherent");
        }
    }

    private boolean ruleTypeMatches(RuleId ruleId, RuleType ruleType) {
        return switch (ruleId) {
            case CONNECTION_RATIO -> ruleType == RuleType.CONNECTION_RATIO_EXCEEDED;
            case SLOW_QUERY_RATE -> ruleType == RuleType.SLOW_QUERIES_HIGH;
            case CONNECTION_FAILURE -> ruleType == RuleType.CONNECTION_FAILURE;
            case COLLECTION_STALE -> ruleType == RuleType.COLLECTION_STALE;
        };
    }
}
