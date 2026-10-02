package com.example.monitoring.realtime.incident;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompIncidentBroadcastAdapterTest {

    @Test
    void publishesThePublicEnvelopeToTheExactIncidentTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        RealtimeIncidentMessage message = mock(RealtimeIncidentMessage.class);
        when(message.databaseConfigId()).thenReturn(37L);

        new StompIncidentBroadcastAdapter(template).publish(message);

        verify(template).convertAndSend("/topic/databases/37/incidents", message);
    }
}
