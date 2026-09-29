package com.example.monitoring.realtime.stomp;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
final class BrokerStompSubscriptionErrorSender implements StompSubscriptionErrorSender {
    private final ObjectProvider<SimpMessagingTemplate> messagingTemplate;
    private final Clock clock;

    @Autowired
    BrokerStompSubscriptionErrorSender(ObjectProvider<SimpMessagingTemplate> messagingTemplate) {
        this(messagingTemplate, Clock.systemUTC());
    }

    BrokerStompSubscriptionErrorSender(ObjectProvider<SimpMessagingTemplate> messagingTemplate, Clock clock) {
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    @Override
    public void send(String sessionId, Long databaseConfigId, String subscriptionId, StompFailure failure) {
        SubscriptionErrorData data = new SubscriptionErrorData(
                failure.code(), failure.message(), failure.requestId(), List.of(), subscriptionId);
        ErrorEnvelope envelope = new ErrorEnvelope(
                1, UUID.randomUUID(), "Error", databaseConfigId, clock.instant(), data);
        messagingTemplate.getObject().convertAndSend("/queue/errors-user" + sessionId, envelope);
    }

    record ErrorEnvelope(int schemaVersion, UUID eventId, String eventType, Long databaseConfigId,
                         @JsonFormat(shape = JsonFormat.Shape.STRING,
                                 pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
                         Instant publishedAt, SubscriptionErrorData data) { }

    record SubscriptionErrorData(String code, String message, String requestId,
                                 List<Object> fieldErrors, String subscriptionId) { }
}
