package com.example.monitoring.realtime.stomp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

/**
 * Handles incoming client messages sent to the /app destination prefix.
 */
@Controller
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public class RealtimeNotificationMessageController {

    @MessageMapping("/ping")
    @SendToUser("/queue/pong")
    public Map<String, Object> ping(Principal principal) {
        String user = principal != null ? principal.getName() : "anonymous";
        return Map.of("status", "pong", "user", user, "timestamp", System.currentTimeMillis());
    }

    @MessageMapping("/ack")
    public void acknowledge(Map<String, Object> payload, Principal principal) {
        // Client acknowledgment of received real-time frames
    }
}
