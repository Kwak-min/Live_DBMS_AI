package com.example.monitoring.realtime.stomp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrokerStompSubscriptionErrorSenderTest {
    @Test
    void routesTheErrorEnvelopeToOnlyTheRequestedWebSocketSession() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(template);
        BrokerStompSubscriptionErrorSender sender =
                new BrokerStompSubscriptionErrorSender(provider, StompTestSupport.CLOCK);
        StompFailure failure = StompFailure.of("DATABASE_NOT_FOUND");

        sender.send("session-a", 12L, "target-sub", failure);

        verify(template).convertAndSend(eq("/queue/errors-usersession-a"), (Object)
                argThat(value -> {
                    BrokerStompSubscriptionErrorSender.ErrorEnvelope envelope =
                            (BrokerStompSubscriptionErrorSender.ErrorEnvelope) value;
                    return envelope.databaseConfigId().equals(12L)
                            && envelope.data().subscriptionId().equals("target-sub")
                            && envelope.data().code().equals("DATABASE_NOT_FOUND")
                            && envelope.publishedAt().equals(StompTestSupport.NOW);
                }));
        assertThat(failure.requestId()).isNotBlank();
    }

    @Test
    void serializesErrorPublishedAtWithRequiredUtcMilliseconds() throws Exception {
        BrokerStompSubscriptionErrorSender.ErrorEnvelope envelope =
                new BrokerStompSubscriptionErrorSender.ErrorEnvelope(
                        1, java.util.UUID.randomUUID(), "Error", 12L, StompTestSupport.NOW,
                        new BrokerStompSubscriptionErrorSender.SubscriptionErrorData(
                                "DATABASE_NOT_FOUND", "Database target was not found.",
                                java.util.UUID.randomUUID().toString(), java.util.List.of(), "target-sub"));
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String publishedAt = objectMapper.readTree(objectMapper.writeValueAsBytes(envelope))
                .path("publishedAt").asText();

        assertThat(publishedAt).isEqualTo("2026-09-29T00:00:00.000Z");
    }
}
