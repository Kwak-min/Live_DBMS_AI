package com.example.monitoring.realtime.status;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompStatusBroadcastAdapterTest {

    @Test
    void publishesThePublicEnvelopeToTheExactStatusTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        RealtimeStatusMessage message = mock(RealtimeStatusMessage.class);
        when(message.databaseConfigId()).thenReturn(37L);

        new StompStatusBroadcastAdapter(template).publish(message);

        verify(template).convertAndSend("/topic/databases/37/status", message);
    }
}
