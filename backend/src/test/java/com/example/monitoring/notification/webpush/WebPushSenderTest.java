package com.example.monitoring.notification.webpush;

import com.example.monitoring.notification.transport.DeliveryOutcomeKind;
import com.example.monitoring.notification.transport.NotificationType;
import com.example.monitoring.notification.transport.PinnedHttpsRequest;
import com.example.monitoring.notification.transport.PinnedHttpsResponse;
import com.example.monitoring.notification.transport.PinnedHttpsTransport;
import com.example.monitoring.notification.transport.PinnedTransportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebPushSenderTest {
    private final WebPushRequestPreparer preparer = mock(WebPushRequestPreparer.class);
    private final PinnedHttpsTransport transport = mock(PinnedHttpsTransport.class);
    private final PinnedHttpsRequest request = mock(PinnedHttpsRequest.class);
    private final WebPushRecipient recipient = new WebPushRecipient("endpoint", "key", "auth");
    private final WebPushMessage message = new WebPushMessage(1L, UUID.randomUUID(),
            NotificationType.INCIDENT_OPENED, "CRITICAL alert", "Threshold exceeded", Instant.now());
    private WebPushSender sender;

    @BeforeEach
    void setUp() {
        sender = new WebPushSender(preparer, transport,
                Clock.fixed(Instant.parse("2026-10-03T03:00:00Z"), ZoneOffset.UTC));
        when(preparer.prepare(recipient, message)).thenReturn(request);
    }

    @Test
    void classifiesProviderResponsesWithoutInspectingSensitiveBodies() {
        assertOutcome(201, new byte[0], Map.of(), DeliveryOutcomeKind.SENT);
        assertOutcome(404, new byte[0], Map.of(), DeliveryOutcomeKind.RECIPIENT_GONE);
        assertOutcome(410, new byte[0], Map.of(), DeliveryOutcomeKind.RECIPIENT_GONE);
        assertOutcome(400, "secret response".getBytes(), Map.of(), DeliveryOutcomeKind.REJECTED);
        assertOutcome(503, new byte[0], Map.of(), DeliveryOutcomeKind.PROVIDER_ERROR);
    }

    @Test
    void sanitizesRateLimitDelayAndClassifiesTimeout() {
        when(transport.execute(request)).thenReturn(new PinnedHttpsResponse(429,
                Map.of("Retry-After", List.of("17")), new byte[0]));
        var rateLimited = sender.send(recipient, message);
        assertThat(rateLimited.kind()).isEqualTo(DeliveryOutcomeKind.RATE_LIMITED);
        assertThat(rateLimited.retryAfter()).contains(java.time.Duration.ofSeconds(17));

        when(transport.execute(request)).thenThrow(new PinnedTransportException(
                PinnedTransportException.Kind.TIMEOUT, new SocketTimeoutException()));
        assertThat(sender.send(recipient, message).kind()).isEqualTo(DeliveryOutcomeKind.TIMEOUT);
    }

    @Test
    void forbiddenContentIsRejectedBeforeAnyTransportCall() {
        assertThatThrownBy(() -> new WebPushMessage(2L, UUID.randomUUID(), NotificationType.INCIDENT_OPENED,
                "credential leak", "Authorization: Bearer token", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Bearer token");
        verifyNoInteractions(transport);
    }

    @Test
    void ordinarySecurityWordsInApprovedDisplayTextAreAllowed() {
        WebPushMessage ordinary = new WebPushMessage(2L, UUID.randomUUID(), NotificationType.INCIDENT_OPENED,
                "token-service alert", "secret rotation policy triggered", Instant.now());

        assertThat(ordinary.title()).isEqualTo("token-service alert");
        assertThat(ordinary.body()).isEqualTo("secret rotation policy triggered");
    }

    private void assertOutcome(int status, byte[] body, Map<String, List<String>> headers,
                               DeliveryOutcomeKind expected) {
        when(transport.execute(request)).thenReturn(new PinnedHttpsResponse(status, headers, body));
        assertThat(sender.send(recipient, message).kind()).isEqualTo(expected);
    }
}
