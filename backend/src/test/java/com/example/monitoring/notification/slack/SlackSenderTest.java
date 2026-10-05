package com.example.monitoring.notification.slack;

import com.example.monitoring.notification.security.SlackWebhookPolicy;
import com.example.monitoring.notification.transport.DeliveryOutcomeKind;
import com.example.monitoring.notification.transport.NotificationType;
import com.example.monitoring.notification.transport.PinnedHttpsResponse;
import com.example.monitoring.notification.transport.PinnedHttpsTransport;
import com.example.monitoring.notification.transport.PinnedTransportException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlackSenderTest {
    private final PinnedHttpsTransport transport = mock(PinnedHttpsTransport.class);
    private final SlackMessage message = new SlackMessage("CRITICAL", "DB", "connection ratio",
            NotificationType.INCIDENT_OPENED, Instant.now(), UUID.randomUUID());
    private SlackSender sender;

    @BeforeEach
    void setUp() {
        sender = new SlackSender(new SlackWebhookPolicy(), new SlackPayloadRenderer(new ObjectMapper(),
                new SlackIncidentLinkFactory("https://monitoring.example")), transport,
                Clock.fixed(Instant.parse("2026-10-03T03:00:00Z"), java.time.ZoneOffset.UTC));
    }

    @Test
    void acceptsOnlyExactOkBodyForHttp200() {
        when(transport.execute(any())).thenReturn(response(200, "ok"));
        assertThat(sender.send(webhook(), message).kind()).isEqualTo(DeliveryOutcomeKind.SENT);

        when(transport.execute(any())).thenReturn(response(200, "ok\n"));
        assertThat(sender.send(webhook(), message).kind()).isEqualTo(DeliveryOutcomeKind.REJECTED);

        when(transport.execute(any())).thenReturn(response(204, ""));
        assertThat(sender.send(webhook(), message).kind()).isEqualTo(DeliveryOutcomeKind.REJECTED);
    }

    @Test
    void classifiesGoneRejectedProviderRateLimitAndTimeout() {
        when(transport.execute(any())).thenReturn(response(410, "gone"));
        assertThat(sender.send(webhook(), message).kind()).isEqualTo(DeliveryOutcomeKind.RECIPIENT_GONE);
        when(transport.execute(any())).thenReturn(response(400, "invalid_payload"));
        assertThat(sender.send(webhook(), message).kind()).isEqualTo(DeliveryOutcomeKind.REJECTED);
        when(transport.execute(any())).thenReturn(response(503, "busy"));
        assertThat(sender.send(webhook(), message).kind()).isEqualTo(DeliveryOutcomeKind.PROVIDER_ERROR);

        when(transport.execute(any())).thenReturn(new PinnedHttpsResponse(429,
                Map.of("Retry-After", List.of("12")), new byte[0]));
        var limited = sender.send(webhook(), message);
        assertThat(limited.kind()).isEqualTo(DeliveryOutcomeKind.RATE_LIMITED);
        assertThat(limited.retryAfter()).contains(Duration.ofSeconds(12));

        when(transport.execute(any())).thenThrow(new PinnedTransportException(
                PinnedTransportException.Kind.TIMEOUT, new SocketTimeoutException()));
        assertThat(sender.send(webhook(), message).kind()).isEqualTo(DeliveryOutcomeKind.TIMEOUT);
    }

    @Test
    void rejectsWebhookPolicyViolationBeforeTransport() {
        assertThat(sender.send("https://hooks.slack.com.evil.test/services/T/B/X", message).kind())
                .isEqualTo(DeliveryOutcomeKind.REJECTED);
        org.mockito.Mockito.verifyNoInteractions(transport);
    }

    private PinnedHttpsResponse response(int status, String body) {
        return new PinnedHttpsResponse(status, Map.of(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String webhook() {
        return "https://hooks.slack.com/services/T/B/X";
    }
}
