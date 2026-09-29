package com.example.monitoring.realtime.stomp;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompOutboundSecurityInterceptorTest {
    @Test
    void suppressesOutboundMessageWhenFreshAuthenticationFails() {
        StompSessionRegistry sessions = mock(StompSessionRegistry.class);
        when(sessions.revalidateForDelivery("session-a")).thenReturn(false);
        StompOutboundSecurityInterceptor interceptor = new StompOutboundSecurityInterceptor(sessions);
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId("session-a");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNull();
        verify(sessions).revalidateForDelivery("session-a");
    }
}
