package com.example.monitoring.notification.webpush;

import com.example.monitoring.notification.transport.DeliveryOutcome;
import com.example.monitoring.notification.transport.DeliveryOutcomeKind;
import com.example.monitoring.notification.transport.PinnedHttpsResponse;
import com.example.monitoring.notification.transport.PinnedHttpsTransport;
import com.example.monitoring.notification.transport.PinnedTransportException;
import com.example.monitoring.notification.transport.RetryAfterParser;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public final class WebPushSender {
    private final WebPushRequestPreparer requestPreparer;
    private final PinnedHttpsTransport transport;
    private final Clock clock;

    public WebPushSender(WebPushRequestPreparer requestPreparer, PinnedHttpsTransport transport, Clock clock) {
        this.requestPreparer = requestPreparer;
        this.transport = transport;
        this.clock = clock;
    }

    public DeliveryOutcome send(WebPushRecipient recipient, WebPushMessage message) {
        try {
            return classify(transport.execute(requestPreparer.prepare(recipient, message)));
        } catch (PinnedTransportException exception) {
            return DeliveryOutcome.of(exception.kind() == PinnedTransportException.Kind.TIMEOUT
                    ? DeliveryOutcomeKind.TIMEOUT : DeliveryOutcomeKind.PROVIDER_ERROR);
        } catch (WebPushPreparationException exception) {
            return DeliveryOutcome.of(DeliveryOutcomeKind.PROVIDER_ERROR);
        } catch (IllegalArgumentException exception) {
            return DeliveryOutcome.of(DeliveryOutcomeKind.REJECTED);
        }
    }

    private DeliveryOutcome classify(PinnedHttpsResponse response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return DeliveryOutcome.of(DeliveryOutcomeKind.SENT);
        }
        if (status == 429) {
            return DeliveryOutcome.rateLimited(
                    RetryAfterParser.parse(response.firstHeader("Retry-After").orElse(null), clock.instant()).orElse(null));
        }
        if (status == 404 || status == 410) {
            return DeliveryOutcome.of(DeliveryOutcomeKind.RECIPIENT_GONE);
        }
        if (status >= 400 && status < 500) {
            return DeliveryOutcome.of(DeliveryOutcomeKind.REJECTED);
        }
        return DeliveryOutcome.of(DeliveryOutcomeKind.PROVIDER_ERROR);
    }
}
