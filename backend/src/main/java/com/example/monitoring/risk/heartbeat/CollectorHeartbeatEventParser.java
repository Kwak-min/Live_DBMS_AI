package com.example.monitoring.risk.heartbeat;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import com.example.monitoring.common.stream.StrictEventFields;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "monitoring.risk", name = "enabled", havingValue = "true")
public final class CollectorHeartbeatEventParser {

    private static final String EVENT_TYPE = "CollectorHeartbeatEvent";
    private final StrictEventFields fields;

    public CollectorHeartbeatEventParser(ObjectMapper objectMapper) {
        fields = new StrictEventFields(objectMapper);
    }

    public CollectorHeartbeatEvent parse(byte[] payload) {
        ObjectNode root = fields.parseObject(payload);
        if (fields.requiredInt(root, "schemaVersion") != 1) {
            throw fields.invalid(
                    root, "UNSUPPORTED_SCHEMA_VERSION", "Unsupported heartbeat schemaVersion");
        }
        if (!EVENT_TYPE.equals(fields.requiredText(root, "eventType", 1, 64))) {
            throw fields.invalid(
                    root, "UNSUPPORTED_EVENT_TYPE", "Unsupported heartbeat eventType");
        }
        try {
            return new CollectorHeartbeatEvent(
                    fields.requiredUuid(root, "eventId"),
                    fields.requiredInstant(root, "publishedAt"),
                    fields.requiredText(root, "collectorId", 1, Integer.MAX_VALUE),
                    fields.requiredInstant(root, "timestamp"),
                    fields.requiredNullableInstant(root, "lastCycleStartedAt"),
                    fields.requiredNullableInstant(root, "lastCycleCompletedAt"),
                    fields.requiredBoolean(root, "cycleInProgress"));
        } catch (InvalidStreamRecordException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw fields.invalid(
                    root,
                    "INVALID_HEARTBEAT_INVARIANT",
                    "Heartbeat payload is incoherent",
                    exception);
        }
    }
}
