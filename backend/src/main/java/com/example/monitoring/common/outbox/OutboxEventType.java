package com.example.monitoring.common.outbox;

import java.util.Arrays;

/**
 * outbox로 발행하는 내부 이벤트 타입. wireName은 이벤트 JSON의 eventType 값과 같다.
 * CollectorHeartbeatEvent는 과거 생존 신호를 재생하지 않도록 outbox 없이 직접 발행한다.
 */
public enum OutboxEventType {

    METRIC_COLLECTED("MetricCollectedEvent", StreamGroup.METRICS),
    MONITORING_STATUS_CHANGED("MonitoringStatusChangedEvent", StreamGroup.STATUSES),
    INCIDENT_CREATED("IncidentCreatedEvent", StreamGroup.INCIDENTS),
    INCIDENT_UPDATED("IncidentUpdatedEvent", StreamGroup.INCIDENTS),
    INCIDENT_RESOLVED("IncidentResolvedEvent", StreamGroup.INCIDENTS);

    private final String wireName;
    private final StreamGroup streamGroup;

    OutboxEventType(String wireName, StreamGroup streamGroup) {
        this.wireName = wireName;
        this.streamGroup = streamGroup;
    }

    public String wireName() {
        return wireName;
    }

    StreamGroup streamGroup() {
        return streamGroup;
    }

    public static OutboxEventType fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(wireName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown outbox event type: " + wireName));
    }

    enum StreamGroup {
        METRICS,
        STATUSES,
        INCIDENTS
    }
}
