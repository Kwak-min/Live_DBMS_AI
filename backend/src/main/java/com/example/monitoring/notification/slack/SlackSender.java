package com.example.monitoring.notification.slack;

import com.example.monitoring.notification.security.SlackWebhookPolicy;
import com.example.monitoring.notification.transport.DeliveryOutcome;
import com.example.monitoring.notification.transport.DeliveryOutcomeKind;
import com.example.monitoring.notification.transport.NotificationProvider;
import com.example.monitoring.notification.transport.PinnedHttpsRequest;
import com.example.monitoring.notification.transport.PinnedHttpsResponse;
import com.example.monitoring.notification.transport.PinnedHttpsTransport;
import com.example.monitoring.notification.transport.PinnedTransportException;
import com.example.monitoring.notification.transport.RetryAfterParser;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.util.Map;

@Component
public final class SlackSender {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final SlackWebhookPolicy webhookPolicy;
    private final SlackPayloadRenderer renderer;
    private final PinnedHttpsTransport transport;
    private final Clock clock;

    public SlackSender(SlackWebhookPolicy webhookPolicy, SlackPayloadRenderer renderer,
                       PinnedHttpsTransport transport, Clock clock) {
        this.webhookPolicy = webhookPolicy;
        this.renderer = renderer;
        this.transport = transport;
        this.clock = clock;
    }

    public DeliveryOutcome send(String webhookUrl, SlackMessage message) {
        try {
            URI uri = webhookPolicy.validate(webhookUrl);
            SlackPayload payload = renderer.render(message);
            PinnedHttpsRequest request = new PinnedHttpsRequest(NotificationProvider.SLACK, uri, "POST",
                    Map.of("Content-Type", "application/json; charset=utf-8"), payload.body(), REQUEST_TIMEOUT);
            return classify(transport.execute(request));
        } catch (PinnedTransportException exception) {
            return DeliveryOutcome.of(exception.kind() == PinnedTransportException.Kind.TIMEOUT
                    ? DeliveryOutcomeKind.TIMEOUT : DeliveryOutcomeKind.PROVIDER_ERROR);
        } catch (IllegalArgumentException exception) {
            return DeliveryOutcome.of(DeliveryOutcomeKind.REJECTED);
        }
    }

    private DeliveryOutcome classify(PinnedHttpsResponse response) {
        int status = response.statusCode();
        if (status == 200 && "ok".equals(new String(response.body(), StandardCharsets.UTF_8))) {
            return DeliveryOutcome.of(DeliveryOutcomeKind.SENT);
        }
        if (status == 429) {
            return DeliveryOutcome.rateLimited(
                    RetryAfterParser.parse(response.firstHeader("Retry-After").orElse(null), clock.instant()).orElse(null));
        }
        if (status == 404 || status == 410) {
            return DeliveryOutcome.of(DeliveryOutcomeKind.RECIPIENT_GONE);
        }
        if ((status >= 200 && status < 500) || status >= 600) {
            return DeliveryOutcome.of(DeliveryOutcomeKind.REJECTED);
        }
        return DeliveryOutcome.of(DeliveryOutcomeKind.PROVIDER_ERROR);
    }
}
