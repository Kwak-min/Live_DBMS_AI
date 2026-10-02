package com.example.monitoring.realtime.status;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import com.example.monitoring.common.stream.StrictEventFields;
import com.example.monitoring.risk.contract.ConnectionStatus;
import com.example.monitoring.risk.contract.DataFreshness;
import com.example.monitoring.risk.contract.RiskLevel;
import com.example.monitoring.risk.contract.StatusSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public final class StatusStreamEventParser {

    private static final String EVENT_TYPE = "MonitoringStatusChangedEvent";
    private final StrictEventFields fields;

    public StatusStreamEventParser(ObjectMapper objectMapper) {
        fields = new StrictEventFields(objectMapper);
    }

    public StatusStreamEvent parse(byte[] payload) {
        ObjectNode root = fields.parseObject(payload);
        if (fields.requiredInt(root, "schemaVersion") != 1) {
            throw fields.invalid(root, "UNSUPPORTED_SCHEMA_VERSION", "Unsupported status schemaVersion");
        }
        if (!EVENT_TYPE.equals(fields.requiredText(root, "eventType", 1, 64))) {
            throw fields.invalid(root, "UNSUPPORTED_EVENT_TYPE", "Unsupported status eventType");
        }
        UUID eventId = fields.requiredUuid(root, "eventId");
        java.time.Instant publishedAt = fields.requiredInstant(root, "publishedAt");
        try {
            StatusSnapshot status = new StatusSnapshot(
                    fields.requiredSafeLong(root, "databaseConfigId"),
                    fields.requiredSafeLong(root, "configVersion"),
                    fields.requiredBoolean(root, "deleted"),
                    fields.requiredBoolean(root, "enabled"),
                    fields.requiredEnum(root, "connectionStatus", ConnectionStatus.class),
                    fields.requiredEnum(root, "dataFreshness", DataFreshness.class),
                    fields.requiredNullableEnum(root, "riskLevel", RiskLevel.class),
                    fields.requiredNullableInstant(root, "lastAttemptAt"),
                    fields.requiredNullableInstant(root, "lastSuccessAt"),
                    fields.requiredNullableSafeLong(root, "latestMetricId"),
                    uuidList(root, "openIncidentIds"),
                    fields.requiredSafeLong(root, "stateVersion"),
                    fields.requiredInstant(root, "updatedAt"));
            return new StatusStreamEvent(eventId, publishedAt, status);
        } catch (InvalidStreamRecordException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw fields.invalid(root, "INVALID_STATUS_INVARIANT", "Status payload is incoherent", exception);
        }
    }

    private List<UUID> uuidList(ObjectNode root, String field) {
        ArrayNode values = fields.requiredArray(root, field);
        List<UUID> result = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw fields.invalid(root, "INVALID_FIELD_TYPE", field + " must contain UUID strings");
            }
            try {
                UUID parsed = UUID.fromString(value.textValue());
                if (!parsed.toString().equals(value.textValue())) {
                    throw new IllegalArgumentException("UUID is not canonical lowercase");
                }
                result.add(parsed);
            } catch (IllegalArgumentException exception) {
                throw fields.invalid(root, "INVALID_UUID", field + " contains an invalid UUID", exception);
            }
        }
        return result;
    }
}
