package com.example.monitoring.realtime.stomp;

import com.example.monitoring.realtime.event.RealtimeMetricMessage;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompMetricBroadcastAdapterTest {
    @Test
    void publishesTheUnmodifiedEnvelopeToItsExactDatabaseMetricsTopic() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        RealtimeMetricMessage message = mock(RealtimeMetricMessage.class);
        when(message.databaseConfigId()).thenReturn(37L);
        StompMetricBroadcastAdapter adapter = new StompMetricBroadcastAdapter(messagingTemplate);

        adapter.publish(message);

        verify(messagingTemplate).convertAndSend("/topic/databases/37/metrics", message);
    }
}
