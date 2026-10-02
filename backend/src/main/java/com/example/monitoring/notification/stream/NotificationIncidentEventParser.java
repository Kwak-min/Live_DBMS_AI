package com.example.monitoring.notification.stream;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import com.example.monitoring.common.stream.StrictEventFields;
import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.IncidentEventPayload;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.ResolutionReason;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleType;
import com.example.monitoring.risk.contract.SeverityTransition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public final class NotificationIncidentEventParser {

    private final StrictEventFields fields;

    public NotificationIncidentEventParser(ObjectMapper objectMapper) {
        fields = new StrictEventFields(objectMapper);
    }

    public NotificationIncidentEvent parse(byte[] payload) {
        ObjectNode root = fields.parseObject(payload);
        fields.requireFields(
                root,
                "schemaVersion", "eventId", "eventType", "publishedAt", "timestamp",
                "sourceEventId", "incidentId", "databaseConfigId", "databaseName",
                "ruleId", "ruleType", "severity", "status", "openedAt", "lastObservedAt",
                "resolvedAt", "resolutionReason", "metricName", "metricValue",
                "thresholdValue", "sourceMetricId", "message", "incidentVersion");
        if (fields.requiredInt(root, "schemaVersion") != 1) {
            throw fields.invalid(root, "UNSUPPORTED_SCHEMA_VERSION", "Unsupported incident event schema");
        }

        UUID eventId = fields.requiredUuid(root, "eventId");
        NotificationIncidentEvent.Type type = eventType(root);
        Instant publishedAt = fields.requiredInstant(root, "publishedAt");
        Instant timestamp = fields.requiredInstant(root, "timestamp");
        if (publishedAt.isBefore(timestamp)) {
            throw fields.invalid(root, "INVALID_TIMESTAMP", "publishedAt cannot precede timestamp");
        }

        Incident incident;
        try {
            incident = new Incident(
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
                    fields.requiredText(root, "message", 1, 65_535),
                    fields.requiredSafeLong(root, "incidentVersion"));
        } catch (IllegalArgumentException exception) {
            throw fields.invalid(root, "INVALID_INCIDENT_EVENT", "Incident event fields are incoherent", exception);
        }

        UUID sourceEventId = fields.requiredNullableUuid(root, "sourceEventId");
        IncidentEventPayload incidentPayload = switch (type) {
            case CREATED -> {
                requireStatus(root, incident, IncidentStatus.OPEN);
                rejectTransition(root);
                yield IncidentEventPayload.created(incident, timestamp, sourceEventId);
            }
            case UPDATED -> {
                requireStatus(root, incident, IncidentStatus.OPEN);
                yield IncidentEventPayload.updated(
                        incident, timestamp, sourceEventId, requiredTransition(root));
            }
            case RESOLVED -> {
                requireStatus(root, incident, IncidentStatus.RESOLVED);
                rejectTransition(root);
                yield IncidentEventPayload.resolved(incident, timestamp, sourceEventId);
            }
        };
        return new NotificationIncidentEvent(eventId, type, publishedAt, incidentPayload);
    }

    private NotificationIncidentEvent.Type eventType(ObjectNode root) {
        return switch (fields.requiredText(root, "eventType", 1, 64)) {
            case "IncidentCreatedEvent" -> NotificationIncidentEvent.Type.CREATED;
            case "IncidentUpdatedEvent" -> NotificationIncidentEvent.Type.UPDATED;
            case "IncidentResolvedEvent" -> NotificationIncidentEvent.Type.RESOLVED;
            default -> throw fields.invalid(root, "INVALID_EVENT_TYPE", "Unsupported incident event type");
        };
    }

    private SeverityTransition requiredTransition(ObjectNode root) {
        if (!root.has("severityTransition") || !root.path("severityTransition").isTextual()) {
            throw invalidTransition(root);
        }
        try {
            return SeverityTransition.valueOf(root.path("severityTransition").textValue());
        } catch (IllegalArgumentException exception) {
            throw invalidTransition(root);
        }
    }

    private void rejectTransition(ObjectNode root) {
        if (root.has("severityTransition")) {
            throw fields.invalid(
                    root, "INVALID_INCIDENT_TRANSITION",
                    "severityTransition is valid only for IncidentUpdatedEvent");
        }
    }

    private InvalidStreamRecordException invalidTransition(ObjectNode root) {
        return fields.invalid(
                root,
                "INVALID_INCIDENT_TRANSITION",
                "IncidentUpdatedEvent requires severityTransition INCREASED or DECREASED");
    }

    private void requireStatus(ObjectNode root, Incident incident, IncidentStatus expected) {
        if (incident.status() != expected) {
            throw fields.invalid(root, "INVALID_INCIDENT_EVENT", "Incident event status is incoherent");
        }
    }
}
