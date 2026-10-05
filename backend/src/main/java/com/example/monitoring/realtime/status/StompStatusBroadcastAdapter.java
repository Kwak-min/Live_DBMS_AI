package com.example.monitoring.realtime.status;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public final class StompStatusBroadcastAdapter implements StatusBroadcastPort {

    private final SimpMessagingTemplate messagingTemplate;

    public StompStatusBroadcastAdapter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = Objects.requireNonNull(messagingTemplate, "messagingTemplate");
    }

    @Override
    public void publish(RealtimeStatusMessage message) {
        RealtimeStatusMessage required = Objects.requireNonNull(message, "message");
        messagingTemplate.convertAndSend(
                "/topic/databases/" + required.databaseConfigId() + "/status",
                required);
    }
}
