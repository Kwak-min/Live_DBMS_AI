package com.example.monitoring.realtime.stomp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
final class StompOutboundSecurityInterceptor implements ChannelInterceptor {
    private final StompSessionRegistry sessions;

    StompOutboundSecurityInterceptor(StompSessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageType messageType = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        if (messageType != SimpMessageType.MESSAGE || sessionId == null) {
            return message;
        }
        return sessions.revalidateForDelivery(sessionId) ? message : null;
    }
}
