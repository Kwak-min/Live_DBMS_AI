package com.example.monitoring.realtime.incident;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public final class StompIncidentBroadcastAdapter implements IncidentBroadcastPort {

    private final SimpMessagingTemplate messagingTemplate;

    public StompIncidentBroadcastAdapter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = Objects.requireNonNull(messagingTemplate, "messagingTemplate");
    }

    @Override
    public void publish(RealtimeIncidentMessage message) {
        RealtimeIncidentMessage required = Objects.requireNonNull(message, "message");
        messagingTemplate.convertAndSend(
                "/topic/databases/" + required.databaseConfigId() + "/incidents",
                required);
    }
}
