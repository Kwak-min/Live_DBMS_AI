package com.example.monitoring.realtime.stomp;

import com.example.monitoring.realtime.event.MetricBroadcastPort;
import com.example.monitoring.realtime.event.RealtimeMetricMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
final class StompMetricBroadcastAdapter implements MetricBroadcastPort {
    private final SimpMessagingTemplate messagingTemplate;

    StompMetricBroadcastAdapter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publish(RealtimeMetricMessage message) {
        RealtimeMetricMessage required = Objects.requireNonNull(message, "message");
        messagingTemplate.convertAndSend(
                "/topic/databases/" + required.databaseConfigId() + "/metrics", required);
    }
}
