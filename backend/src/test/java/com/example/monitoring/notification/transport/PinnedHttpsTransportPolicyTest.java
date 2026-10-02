package com.example.monitoring.notification.transport;

import com.example.monitoring.notification.security.PublicAddressPolicy;
import com.example.monitoring.notification.security.PushEndpointPolicy;
import com.example.monitoring.notification.security.SlackWebhookPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinnedHttpsTransportPolicyTest {
    private final HostResolutionExecutor resolutions = new HostResolutionExecutor(1);

    @AfterEach
    void closeResolverExecutor() {
        resolutions.close();
    }

    @Test
    void pinsValidatedPublicAddressThenRejectsPrivateRebindingBeforeExecutor() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress("fixture-public", new byte[] {8, 8, 8, 8});
        InetAddress privateAddress = InetAddress.getByAddress("fixture-private", new byte[] {10, 0, 0, 7});
        AtomicInteger resolutionCount = new AtomicInteger();
        AtomicReference<String> resolvedHost = new AtomicReference<>();
        HostResolver resolver = host -> {
            resolvedHost.set(host);
            return resolutionCount.incrementAndGet() == 1
                    ? new InetAddress[] {publicAddress}
                    : new InetAddress[] {privateAddress};
        };
        RecordingExecutor outbound = new RecordingExecutor();
        PinnedHttpsTransport transport = new PinnedHttpsTransport(
                new PushEndpointPolicy(""), new SlackWebhookPolicy(), resolver, resolutions,
                new PublicAddressPolicy(), outbound);
        URI endpoint = URI.create("https://fcm.googleapis.com/push/policy-fixture");
        PinnedHttpsRequest request = new PinnedHttpsRequest(
                NotificationProvider.WEB_PUSH, endpoint, "POST", Map.of(), new byte[0], Duration.ofSeconds(1));

        PinnedHttpsResponse first = transport.execute(request);

        assertThat(first.statusCode()).isEqualTo(204);
        assertThat(resolvedHost).hasValue("fcm.googleapis.com");
        assertThat(resolutionCount).hasValue(1);
        assertThat(outbound.calls()).isOne();
        assertThat(outbound.lastRequest().uri()).isEqualTo(endpoint);
        assertThat(outbound.lastAddresses()).containsExactly(publicAddress);

        assertThatThrownBy(() -> transport.execute(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Resolved notification destination is not public.");
        assertThat(resolutionCount).hasValue(2);
        assertThat(outbound.calls()).isOne();
        assertThat(outbound.lastAddresses()).containsExactly(publicAddress);
    }

    private static final class RecordingExecutor implements PinnedHttpExecutor {
        private final AtomicInteger calls = new AtomicInteger();
        private PinnedHttpsRequest lastRequest;
        private InetAddress[] lastAddresses;

        @Override
        public PinnedHttpsResponse execute(PinnedHttpsRequest request, InetAddress[] pinnedAddresses) {
            calls.incrementAndGet();
            lastRequest = request;
            lastAddresses = pinnedAddresses.clone();
            return new PinnedHttpsResponse(204, Map.of(), new byte[0]);
        }

        int calls() {
            return calls.get();
        }

        PinnedHttpsRequest lastRequest() {
            return lastRequest;
        }

        InetAddress[] lastAddresses() {
            return lastAddresses.clone();
        }
    }
}
