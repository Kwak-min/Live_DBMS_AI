package com.example.monitoring.notification.integration;

import com.example.monitoring.integration.PartCNativeQaEvidence;
import com.example.monitoring.notification.config.VapidConfigurationProvider;
import com.example.monitoring.notification.delivery.SlackAttemptPacer;
import com.example.monitoring.notification.security.PublicAddressPolicy;
import com.example.monitoring.notification.security.PushEndpointPolicy;
import com.example.monitoring.notification.security.SlackWebhookPolicy;
import com.example.monitoring.notification.slack.SlackMessage;
import com.example.monitoring.notification.slack.SlackIncidentLinkFactory;
import com.example.monitoring.notification.slack.SlackPayloadRenderer;
import com.example.monitoring.notification.slack.SlackSender;
import com.example.monitoring.notification.transport.ApachePinnedHttpExecutor;
import com.example.monitoring.notification.transport.DeliveryOutcome;
import com.example.monitoring.notification.transport.DeliveryOutcomeKind;
import com.example.monitoring.notification.transport.HostResolutionExecutor;
import com.example.monitoring.notification.transport.HostResolver;
import com.example.monitoring.notification.transport.NotificationType;
import com.example.monitoring.notification.transport.PinnedHttpsTransport;
import com.example.monitoring.notification.webpush.WebPushMessage;
import com.example.monitoring.notification.webpush.WebPushPayloadRenderer;
import com.example.monitoring.notification.webpush.WebPushRecipient;
import com.example.monitoring.notification.webpush.WebPushRequestPreparer;
import com.example.monitoring.notification.webpush.WebPushSender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.ssl.HttpsSupport;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "PART_C_NATIVE_QA_ENABLED", matches = "true")
class PartCProviderNativeQaTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Instant NOW = Instant.parse("2026-10-03T01:02:03.456Z");
    private HostResolutionExecutor resolutionExecutor;

    @AfterEach
    void closeResolutionExecutorAndProveNoRequestThreadRemains() throws Exception {
        if (resolutionExecutor != null) {
            resolutionExecutor.close();
            resolutionExecutor = null;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        List<Thread> remaining;
        do {
            remaining = liveNotificationRequestThreads();
            if (remaining.isEmpty()) {
                return;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        assertThat(remaining).extracting(Thread::getName).isEmpty();
    }

    @Test
    void webPushTlsCaptureDecryptsCiphertextAndValidatesVapid() throws Exception {
        int port = requiredPort("PART_C_NATIVE_QA_WEB_PUSH_PORT");
        try (LocalTlsProviderFixture fixture = LocalTlsProviderFixture.start(
                "fcm.googleapis.com", port,
                ignored -> LocalTlsProviderFixture.Response.immediate(201, ""));
             var socketBuilder = mockStatic(SSLConnectionSocketFactoryBuilder.class)) {
            AtomicInteger routedConnections = new AtomicInteger();
            SSLConnectionSocketFactory sockets = new SSLConnectionSocketFactory(
                    fixture.clientContext(), HttpsSupport.getDefaultHostnameVerifier()) {
                @Override
                public Socket connectSocket(Socket socket, HttpHost host,
                                            InetSocketAddress remoteAddress, InetSocketAddress localAddress,
                                            Timeout timeout, Object attachment, HttpContext context) throws IOException {
                    assertThat(host.getHostName()).isEqualTo("fcm.googleapis.com");
                    assertThat(host.getPort()).isEqualTo(443);
                    assertThat(remoteAddress.getAddress()).isEqualTo(InetAddress.getByName("127.0.0.1"));
                    assertThat(remoteAddress.getPort()).isEqualTo(443);
                    routedConnections.incrementAndGet();
                    return super.connectSocket(socket, host,
                            new InetSocketAddress(remoteAddress.getAddress(), port), localAddress,
                            timeout, attachment, context);
                }
            };
            SSLConnectionSocketFactoryBuilder builder = mock(SSLConnectionSocketFactoryBuilder.class, RETURNS_SELF);
            when(builder.build()).thenReturn(sockets);
            socketBuilder.when(SSLConnectionSocketFactoryBuilder::create).thenReturn(builder);
            AtomicInteger resolutions = new AtomicInteger();
            PushEndpointPolicy pushPolicy = new PushEndpointPolicy("");
            PinnedHttpsTransport transport = transport(
                    pushPolicy,
                    mock(SlackWebhookPolicy.class),
                    fixture,
                    "fcm.googleapis.com",
                    resolutions);

            KeyPair recipient = WebPushCryptoProof.ecKeyPair();
            byte[] recipientPublic = WebPushCryptoProof.uncompressed(
                    (java.security.interfaces.ECPublicKey) recipient.getPublic());
            byte[] authSecret = new byte[16];
            Arrays.fill(authSecret, (byte) 0x5a);
            byte[] vapidPrivate = new byte[32];
            vapidPrivate[31] = 1;
            byte[] vapidPublic = CustomNamedCurves.getByName("secp256r1")
                    .getG().getEncoded(false);
            String subject = "mailto:ops@example.test";
            VapidConfigurationProvider configuration = new VapidConfigurationProvider(
                    URL_ENCODER.encodeToString(vapidPublic),
                    URL_ENCODER.encodeToString(vapidPrivate),
                    subject);
            WebPushSender sender = new WebPushSender(
                    new WebPushRequestPreparer(
                            configuration,
                            new WebPushPayloadRenderer(MAPPER),
                            pushPolicy),
                    transport,
                    Clock.fixed(NOW, ZoneOffset.UTC));

            UUID incidentId = UUID.fromString("d38f135a-34c3-40df-915a-f26b2ebf4162");
            String endpoint = "https://fcm.googleapis.com/push/native-proof";
            DeliveryOutcome outcome = sender.send(
                    new WebPushRecipient(
                            endpoint,
                            URL_ENCODER.encodeToString(recipientPublic),
                            URL_ENCODER.encodeToString(authSecret)),
                    new WebPushMessage(
                            81L,
                            incidentId,
                            NotificationType.INCIDENT_OPENED,
                            "CRITICAL database alert",
                            "Connection failure threshold exceeded",
                            NOW));

            assertThat(outcome.kind()).isEqualTo(DeliveryOutcomeKind.SENT);
            fixture.awaitCaptureCount(1, Duration.ofSeconds(5));
            LocalTlsProviderFixture.Capture capture = fixture.captures().get(0);
            assertThat(capture.method()).isEqualTo("POST");
            assertThat(capture.path()).isEqualTo("/push/native-proof");
            assertThat(capture.header("Host")).isEqualTo("fcm.googleapis.com");
            assertThat(capture.header("Content-Encoding")).isEqualTo("aes128gcm");
            assertThat(capture.header("TTL")).isEqualTo("600");
            Map<String, Object> payload = WebPushCryptoProof.decryptAndVerify(
                    capture.body(),
                    capture.header("Authorization"),
                    recipient,
                    recipientPublic,
                    authSecret,
                    vapidPublic,
                    "https://fcm.googleapis.com",
                    subject,
                    MAPPER);
            assertThat(payload)
                    .containsOnlyKeys(
                            "schemaVersion", "deliveryId", "incidentId", "type", "title", "body",
                            "url", "tag", "sentAt")
                    .containsEntry("schemaVersion", 1)
                    .containsEntry("deliveryId", 81)
                    .containsEntry("incidentId", incidentId.toString())
                    .containsEntry("type", "INCIDENT_OPENED")
                    .containsEntry("title", "CRITICAL database alert")
                    .containsEntry("body", "Connection failure threshold exceeded")
                    .containsEntry("url", "/incidents/" + incidentId)
                    .containsEntry("tag", "incident:" + incidentId)
                    .containsEntry("sentAt", "2026-10-03T01:02:03.456Z");
            assertThat(payload).doesNotContainKey("path");

            assertThatThrownBy(() -> new PublicAddressPolicy().requirePublic(
                    "fcm.googleapis.com",
                    new InetAddress[]{InetAddress.getByName("127.0.0.1")}))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PushEndpointPolicy("").validate(
                    "https://fcm.googleapis.com:" + port + "/push/native-proof"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(resolutions).hasValue(1);
            assertThat(routedConnections).hasValue(1);
            assertThat(fixture.captures()).hasSize(1);

            PartCNativeQaEvidence.write("webpush-tls.json", Map.of(
                    "tlsRequest", true,
                    "ciphertextDecrypted", true,
                    "vapidValid", true,
                    "ssrfRejected", true,
                    "noPublicEgress", true,
                    "requestCount", fixture.captures().size(),
                    "providerOutcome", outcome.kind().name()));
        }
    }

    @Test
    void slackTlsSuccessRetryPacingAndErrorsAreExact() throws Exception {
        int port = requiredPort("PART_C_NATIVE_QA_SLACK_PORT");
        try (LocalTlsProviderFixture fixture = LocalTlsProviderFixture.start(
                "hooks.slack.com", port, PartCProviderNativeQaTest::slackResponse)) {
            AtomicInteger resolutions = new AtomicInteger();
            SlackWebhookPolicy slackPolicy = acceptingSlackPolicy();
            PinnedHttpsTransport transport = transport(
                    mock(PushEndpointPolicy.class),
                    slackPolicy,
                    fixture,
                    "hooks.slack.com",
                    resolutions);
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            SlackSender sender = new SlackSender(
                    slackPolicy,
                    new SlackPayloadRenderer(
                            MAPPER,
                            new SlackIncidentLinkFactory("http://localhost:5173")),
                    transport,
                    clock);
            SlackAttemptPacer pacer = new SlackAttemptPacer();
            SlackMessage message = new SlackMessage(
                    "CRITICAL",
                    "DB <primary> & replica",
                    "connections > 90%",
                    NotificationType.INCIDENT_OPENED,
                    NOW,
                    UUID.fromString("d38f135a-34c3-40df-915a-f26b2ebf4162"));

            String ok = slackUri(port, "ok");
            long leaseAcquired = System.nanoTime();
            String pacingIdentity = slackPolicy.canonicalIdentity(ok);
            assertThat(pacer.awaitPermit(pacingIdentity, leaseAcquired)).isTrue();
            DeliveryOutcome first;
            try {
                first = sender.send(ok, message);
            } finally {
                pacer.recordCompletion(pacingIdentity);
            }
            assertThat(pacer.awaitPermit(pacingIdentity, leaseAcquired)).isTrue();
            DeliveryOutcome second;
            try {
                second = sender.send(ok, message);
            } finally {
                pacer.recordCompletion(pacingIdentity);
            }
            assertThat(first.kind()).isEqualTo(DeliveryOutcomeKind.SENT);
            assertThat(second.kind()).isEqualTo(DeliveryOutcomeKind.SENT);
            fixture.awaitCaptureCount(2, Duration.ofSeconds(5));
            List<LocalTlsProviderFixture.Capture> paced = fixture.captures().subList(0, 2);
            long firstDelayMillis = Duration.ofNanos(
                    paced.get(0).observedAtNanos() - leaseAcquired).toMillis();
            long spacingMillis = Duration.ofNanos(
                    paced.get(1).observedAtNanos() - paced.get(0).observedAtNanos()).toMillis();
            assertThat(firstDelayMillis).isGreaterThanOrEqualTo(900L);
            assertThat(spacingMillis).isGreaterThanOrEqualTo(900L);

            Map<String, Object> json = MAPPER.readValue(
                    paced.get(0).body(), new TypeReference<>() {
                    });
            assertThat(json).containsOnlyKeys("text", "blocks", "unfurl_links", "unfurl_media")
                    .containsEntry("unfurl_links", false)
                    .containsEntry("unfurl_media", false);
            String text = (String) json.get("text");
            assertThat(text)
                    .contains("DB &lt;primary&gt; &amp; replica")
                    .contains("connections &gt; 90%")
                    .doesNotContain("<primary>");
            Map<?, ?> section = (Map<?, ?>) ((List<?>) json.get("blocks")).get(0);
            Map<?, ?> blockText = (Map<?, ?>) section.get("text");
            assertThat(blockText.get("type")).isEqualTo("plain_text");
            assertThat(blockText.get("text")).isEqualTo(text);

            DeliveryOutcome limited = sendPaced(sender, pacer, slackPolicy,
                    slackUri(port, "rate"), message, leaseAcquired);
            DeliveryOutcome limitedDate = sendPaced(sender, pacer, slackPolicy,
                    slackUri(port, "rate-date"), message, leaseAcquired);
            DeliveryOutcome limitedHuge = sendPaced(sender, pacer, slackPolicy,
                    slackUri(port, "rate-huge"), message, leaseAcquired);
            DeliveryOutcome gone404 = sendPaced(sender, pacer, slackPolicy,
                    slackUri(port, "gone-404"), message, leaseAcquired);
            DeliveryOutcome gone410 = sendPaced(sender, pacer, slackPolicy,
                    slackUri(port, "gone-410"), message, leaseAcquired);
            DeliveryOutcome rejected = sendPaced(sender, pacer, slackPolicy,
                    slackUri(port, "rejected"), message, leaseAcquired);
            DeliveryOutcome provider = sendPaced(sender, pacer, slackPolicy,
                    slackUri(port, "provider"), message, leaseAcquired);
            DeliveryOutcome nonOk = sendPaced(sender, pacer, slackPolicy,
                    slackUri(port, "non-ok"), message, leaseAcquired);
            int beforeRedirect = fixture.captures().size();
            DeliveryOutcome redirect = sendPaced(sender, pacer, slackPolicy,
                    slackUri(port, "redirect"), message, leaseAcquired);
            assertThat(limited.kind()).isEqualTo(DeliveryOutcomeKind.RATE_LIMITED);
            assertThat(limited.retryAfter()).contains(Duration.ofSeconds(1));
            assertThat(limitedDate.kind()).isEqualTo(DeliveryOutcomeKind.RATE_LIMITED);
            assertThat(limitedDate.retryAfter()).contains(Duration.between(
                    NOW, NOW.plusSeconds(2).truncatedTo(ChronoUnit.SECONDS)));
            assertThat(limitedHuge.kind()).isEqualTo(DeliveryOutcomeKind.RATE_LIMITED);
            assertThat(limitedHuge.retryAfter()).contains(Duration.ofSeconds(Long.MAX_VALUE));
            assertThat(gone404.kind()).isEqualTo(DeliveryOutcomeKind.RECIPIENT_GONE);
            assertThat(gone410.kind()).isEqualTo(DeliveryOutcomeKind.RECIPIENT_GONE);
            assertThat(rejected.kind()).isEqualTo(DeliveryOutcomeKind.REJECTED);
            assertThat(provider.kind()).isEqualTo(DeliveryOutcomeKind.PROVIDER_ERROR);
            assertThat(nonOk.kind()).isEqualTo(DeliveryOutcomeKind.REJECTED);
            assertThat(redirect.kind()).isEqualTo(DeliveryOutcomeKind.REJECTED);
            assertThat(fixture.captures()).hasSize(beforeRedirect + 1);

            long timeoutStarted = System.nanoTime();
            DeliveryOutcome timeout = sendPaced(
                    sender, pacer, slackPolicy, slackUri(port, "slow"), message, leaseAcquired);
            long timeoutMillis = Duration.ofNanos(System.nanoTime() - timeoutStarted).toMillis();
            assertThat(timeout.kind()).isEqualTo(DeliveryOutcomeKind.TIMEOUT);
            assertThat(timeoutMillis).isBetween(4_000L, 7_500L);
            fixture.awaitIdle(Duration.ofSeconds(4));
            DeliveryOutcome afterTimeout = sendPaced(
                    sender, pacer, slackPolicy,
                    slackUri(port, "after-timeout"), message, leaseAcquired);
            assertThat(afterTimeout.kind()).isEqualTo(DeliveryOutcomeKind.SENT);
            fixture.awaitIdle(Duration.ofSeconds(2));

            String fixtureUrl = slackUri(port, "ok");
            assertThatThrownBy(() -> new SlackWebhookPolicy().validate(fixtureUrl))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(resolutions.get()).isEqualTo(fixture.captures().size());

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("success", true);
            evidence.put("retry", true);
            evidence.put("pacing", true);
            evidence.put("errorClassification", true);
            evidence.put("timeoutBounded", true);
            evidence.put("noLingeringRequest", true);
            evidence.put("noPublicEgress", true);
            evidence.put("requestCount", fixture.captures().size());
            evidence.put("firstPermitDelayMs", firstDelayMillis);
            evidence.put("sameDestinationSpacingMs", spacingMillis);
            evidence.put("timeoutMs", timeoutMillis);
            PartCNativeQaEvidence.write("slack-tls.json", evidence);
        }
    }

    private static DeliveryOutcome sendPaced(
            SlackSender sender,
            SlackAttemptPacer pacer,
            SlackWebhookPolicy policy,
            String destination,
            SlackMessage message,
            long leaseAcquired
    ) {
        String pacingIdentity = policy.canonicalIdentity(destination);
        assertThat(pacer.awaitPermit(pacingIdentity, leaseAcquired)).isTrue();
        try {
            return sender.send(destination, message);
        } finally {
            pacer.recordCompletion(pacingIdentity);
        }
    }

    private static LocalTlsProviderFixture.Response slackResponse(String path) {
        return switch (path.substring(path.lastIndexOf('/') + 1)) {
            case "ok", "after-timeout" -> LocalTlsProviderFixture.Response.immediate(200, "ok");
            case "rate" -> LocalTlsProviderFixture.Response.rateLimited("1");
            case "rate-date" -> LocalTlsProviderFixture.Response.rateLimited(
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(2).atZone(ZoneOffset.UTC)));
            case "rate-huge" -> LocalTlsProviderFixture.Response.rateLimited(
                    "999999999999999999999999999999999999999999999999999999999999");
            case "gone-404" -> LocalTlsProviderFixture.Response.immediate(404, "missing");
            case "gone-410" -> LocalTlsProviderFixture.Response.immediate(410, "gone");
            case "rejected" -> LocalTlsProviderFixture.Response.immediate(400, "bad request");
            case "provider" -> LocalTlsProviderFixture.Response.immediate(503, "busy");
            case "non-ok" -> LocalTlsProviderFixture.Response.immediate(200, "ok\n");
            case "redirect" -> LocalTlsProviderFixture.Response.redirect("https://example.com/forbidden");
            case "slow" -> LocalTlsProviderFixture.Response.slow(
                    200, "ok", Duration.ofSeconds(7));
            default -> LocalTlsProviderFixture.Response.immediate(404, "missing");
        };
    }

    private PinnedHttpsTransport transport(
            PushEndpointPolicy pushPolicy,
            SlackWebhookPolicy slackPolicy,
            LocalTlsProviderFixture fixture,
            String expectedHost,
            AtomicInteger resolutions
    ) throws Exception {
        HostResolver resolver = host -> {
            assertThat(host).isEqualToIgnoringCase(expectedHost);
            resolutions.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
        };
        PublicAddressPolicy addressPolicy = mock(PublicAddressPolicy.class);
        when(addressPolicy.requirePublic(anyString(), any(InetAddress[].class)))
                .thenAnswer(invocation -> ((InetAddress[]) invocation.getArgument(1)).clone());
        assertThat(resolutionExecutor).isNull();
        resolutionExecutor = new HostResolutionExecutor();
        return new PinnedHttpsTransport(
                pushPolicy,
                slackPolicy,
                resolver,
                resolutionExecutor,
                addressPolicy,
                new ApachePinnedHttpExecutor(fixture.clientContext()));
    }

    private static List<Thread> liveNotificationRequestThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith("notification-dns-resolver-")
                        || thread.getName().equals("notification-https-total")
                        || thread.getName().equals("notification-https-request"))
                .toList();
    }

    private static SlackWebhookPolicy acceptingSlackPolicy() {
        SlackWebhookPolicy policy = mock(SlackWebhookPolicy.class);
        when(policy.validate(anyString())).thenAnswer(
                invocation -> URI.create(invocation.getArgument(0)));
        when(policy.canonicalIdentity(anyString())).thenAnswer(
                invocation -> "fixture:" + Integer.toHexString(
                        invocation.<String>getArgument(0).toLowerCase(java.util.Locale.ROOT).hashCode()));
        return policy;
    }

    private static String slackUri(int port, String result) {
        return "https://hooks.slack.com:" + port + "/services/T000/B000/" + result;
    }

    private static int requiredPort(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return port;
    }
}
