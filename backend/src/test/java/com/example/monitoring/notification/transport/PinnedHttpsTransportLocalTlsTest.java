package com.example.monitoring.notification.transport;

import com.example.monitoring.notification.security.PublicAddressPolicy;
import com.example.monitoring.notification.security.PushEndpointPolicy;
import com.example.monitoring.notification.security.SlackWebhookPolicy;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PinnedHttpsTransportLocalTlsTest {
    private HttpsServer server;
    private ExecutorService serverExecutor;
    private SSLContext clientContext;
    private AtomicInteger captureCalls;
    private AtomicReference<String> capturedHost;
    private AtomicReference<byte[]> capturedBody;
    private HostResolutionExecutor resolutionExecutor;

    @BeforeEach
    void startServer() throws Exception {
        TlsFixture fixture = tlsFixture("push.test");
        clientContext = fixture.clientContext();
        captureCalls = new AtomicInteger();
        capturedHost = new AtomicReference<>();
        capturedBody = new AtomicReference<>();
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(fixture.serverContext()));
        server.createContext("/capture", exchange -> {
            captureCalls.incrementAndGet();
            capturedHost.set(exchange.getRequestHeaders().getFirst("Host"));
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", uri("push.test", "/capture").toString());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/drip", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int index = 0; index < 20; index++) {
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    Thread.sleep(500);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        serverExecutor = Executors.newFixedThreadPool(2);
        server.setExecutor(serverExecutor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (resolutionExecutor != null) {
            resolutionExecutor.close();
        }
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void sendsRealTlsRequestUsingPinnedAddressAndOriginalHostname() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        HostResolver resolver = host -> {
            resolutions.incrementAndGet();
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        };
        PinnedHttpsTransport transport = transport(resolver);
        byte[] body = "local TLS body".getBytes(StandardCharsets.UTF_8);

        PinnedHttpsResponse response = transport.execute(request(uri("push.test", "/capture"), body));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("ok");
        assertThat(resolutions).hasValue(1);
        assertThat(captureCalls).hasValue(1);
        assertThat(capturedHost.get()).isEqualTo("push.test:" + server.getAddress().getPort());
        assertThat(capturedBody.get()).containsExactly(body);
    }

    @Test
    void doesNotFollowRedirectAndRejectsWrongCertificateHostname() throws Exception {
        HostResolver resolver = host -> new InetAddress[] {InetAddress.getLoopbackAddress()};
        PinnedHttpsTransport transport = transport(resolver);

        PinnedHttpsResponse redirect = transport.execute(request(uri("push.test", "/redirect"), new byte[0]));
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(captureCalls).hasValue(0);

        assertThatThrownBy(() -> transport.execute(request(uri("wrong.test", "/capture"), new byte[0])))
                .isInstanceOf(PinnedTransportException.class);
        assertThat(captureCalls).hasValue(0);
    }

    @Test
    void resolvesAndRevalidatesEverySendSoRebindingCannotReuseAConnection() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        HostResolver resolver = host -> resolutions.incrementAndGet() == 1
                ? new InetAddress[] {InetAddress.getLoopbackAddress()}
                : new InetAddress[] {InetAddress.getByName("127.0.0.2")};
        PinnedHttpsTransport transport = transport(resolver);

        assertThat(transport.execute(request(uri("push.test", "/capture"), new byte[0])).statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> transport.execute(request(uri("push.test", "/capture"), new byte[0])))
                .isInstanceOf(PinnedTransportException.class);
        assertThat(resolutions).hasValue(2);
        assertThat(captureCalls).hasValue(1);
    }

    @Test
    void boundsTheWholeRequestWhileTheResponseBodyKeepsDripping() throws Exception {
        HostResolver resolver = host -> new InetAddress[] {InetAddress.getLoopbackAddress()};
        PinnedHttpsTransport transport = transport(resolver);
        Duration timeout = Duration.ofSeconds(5);
        long started = System.nanoTime();

        PinnedTransportException failure = null;
        try {
            transport.execute(request(uri("push.test", "/drip"), new byte[0], timeout));
        } catch (PinnedTransportException exception) {
            failure = exception;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(failure).isNotNull();
        assertThat(failure.kind()).isEqualTo(PinnedTransportException.Kind.TIMEOUT);
        assertThat(elapsed).isBetween(Duration.ofMillis(4_500), Duration.ofSeconds(7));
        resolutionExecutor.close();
        assertThat(requestDeadlineThreads()).isEmpty();
    }

    @Test
    void boundsBlockingDnsResolutionInsideTheSameRequestDeadline() throws Exception {
        CountDownLatch resolverInterrupted = new CountDownLatch(1);
        HostResolver resolver = host -> {
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("resolver unexpectedly resumed");
            } catch (InterruptedException exception) {
                resolverInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new java.net.UnknownHostException("resolution interrupted");
            }
        };
        PinnedHttpsTransport transport = transport(resolver);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Duration timeout = Duration.ofMillis(300);
            long started = System.nanoTime();
            PinnedTransportException failure = null;
            try {
                transport.execute(request(uri("push.test", "/capture"), new byte[0], timeout));
            } catch (PinnedTransportException exception) {
                failure = exception;
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(failure).isNotNull();
            assertThat(failure.kind()).isEqualTo(PinnedTransportException.Kind.TIMEOUT);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        });
        assertThat(resolverInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        resolutionExecutor.close();
        assertThat(requestDeadlineThreads()).isEmpty();
    }

    @Test
    void passesOnlyTheRemainingDeadlineToHttpExecution() throws Exception {
        HostResolver resolver = host -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.net.UnknownHostException("resolution interrupted");
            }
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        };
        PinnedHttpExecutor outbound = mock(PinnedHttpExecutor.class);
        when(outbound.execute(org.mockito.ArgumentMatchers.any(PinnedHttpsRequest.class),
                org.mockito.ArgumentMatchers.any(InetAddress[].class)))
                .thenReturn(new PinnedHttpsResponse(200, Map.of(), new byte[0]));
        PinnedHttpsTransport transport = transport(resolver, new HostResolutionExecutor(1), outbound);

        transport.execute(request(uri("push.test", "/capture"), new byte[0], Duration.ofSeconds(1)));

        ArgumentCaptor<PinnedHttpsRequest> request = ArgumentCaptor.forClass(PinnedHttpsRequest.class);
        verify(outbound).execute(request.capture(), org.mockito.ArgumentMatchers.any(InetAddress[].class));
        assertThat(request.getValue().timeout()).isPositive().isLessThan(Duration.ofMillis(900));
    }

    @Test
    void neverSendsAfterAnUninterruptibleResolutionReturnsPastTheDeadline() throws Exception {
        CountDownLatch resolverStarted = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        CountDownLatch resolverFinished = new CountDownLatch(1);
        CountDownLatch interruptObserved = new CountDownLatch(1);
        HostResolver resolver = host -> {
            resolverStarted.countDown();
            while (releaseResolver.getCount() > 0) {
                try {
                    releaseResolver.await();
                } catch (InterruptedException ignored) {
                    interruptObserved.countDown();
                }
            }
            resolverFinished.countDown();
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        };
        PinnedHttpExecutor outbound = mock(PinnedHttpExecutor.class);
        PinnedHttpsTransport transport = transport(resolver, new HostResolutionExecutor(1), outbound);

        try {
            PinnedTransportException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> transport.execute(request(uri("push.test", "/capture"), new byte[0],
                            Duration.ofMillis(200))), PinnedTransportException.class);

            assertThat(resolverStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.kind()).isEqualTo(PinnedTransportException.Kind.TIMEOUT);
            assertThat(interruptObserved.await(1, TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(outbound);
        } finally {
            releaseResolver.countDown();
            assertThat(resolverFinished.await(1, TimeUnit.SECONDS)).isTrue();
        }
        verifyNoInteractions(outbound);
        resolutionExecutor.close();
        assertThat(requestDeadlineThreads()).isEmpty();
    }

    @Test
    void boundsWorkersAndQueueWhenResolversIgnoreCancellation() throws Exception {
        CountDownLatch releaseResolvers = new CountDownLatch(1);
        CountDownLatch resolversFinished = new CountDownLatch(2);
        CountDownLatch interruptsObserved = new CountDownLatch(2);
        AtomicInteger started = new AtomicInteger();
        HostResolver resolver = host -> {
            started.incrementAndGet();
            while (releaseResolvers.getCount() > 0) {
                try {
                    releaseResolvers.await();
                } catch (InterruptedException ignored) {
                    interruptsObserved.countDown();
                }
            }
            resolversFinished.countDown();
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        };
        PinnedHttpExecutor outbound = mock(PinnedHttpExecutor.class);
        PinnedHttpsTransport transport = transport(resolver, new HostResolutionExecutor(2), outbound);

        try {
            long callsStarted = System.nanoTime();
            for (int index = 0; index < 8; index++) {
                PinnedTransportException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> transport.execute(request(uri("push.test", "/capture"), new byte[0],
                                Duration.ofMillis(100))), PinnedTransportException.class);
                assertThat(failure.kind()).isEqualTo(PinnedTransportException.Kind.TIMEOUT);
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - callsStarted);

            assertThat(started).hasValue(2);
            assertThat(interruptsObserved.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(resolverDeadlineThreads()).hasSize(2);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
            verifyNoInteractions(outbound);
        } finally {
            releaseResolvers.countDown();
            assertThat(resolversFinished.await(1, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(started).hasValue(2);
        verifyNoInteractions(outbound);
        resolutionExecutor.close();
        assertThat(requestDeadlineThreads()).isEmpty();
    }

    private PinnedHttpsTransport transport(HostResolver resolver) throws Exception {
        return transport(resolver, new HostResolutionExecutor(), new ApachePinnedHttpExecutor(clientContext));
    }

    private PinnedHttpsTransport transport(HostResolver resolver, HostResolutionExecutor resolutions,
                                           PinnedHttpExecutor outbound) {
        resolutionExecutor = resolutions;
        PushEndpointPolicy pushPolicy = mock(PushEndpointPolicy.class);
        when(pushPolicy.validate(anyString())).thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        PublicAddressPolicy addressPolicy = mock(PublicAddressPolicy.class);
        when(addressPolicy.requirePublic(anyString(), org.mockito.ArgumentMatchers.any(InetAddress[].class)))
                .thenAnswer(invocation -> ((InetAddress[]) invocation.getArgument(1)).clone());
        return new PinnedHttpsTransport(pushPolicy, mock(SlackWebhookPolicy.class), resolver, resolutions,
                addressPolicy, outbound);
    }

    private PinnedHttpsRequest request(URI uri, byte[] body) {
        return request(uri, body, Duration.ofSeconds(3));
    }

    private PinnedHttpsRequest request(URI uri, byte[] body, Duration timeout) {
        return new PinnedHttpsRequest(NotificationProvider.WEB_PUSH, uri, "POST",
                Map.of("Content-Type", "application/octet-stream"), body, timeout);
    }

    private java.util.List<String> requestDeadlineThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.equals("notification-https-request")
                        || name.equals("notification-https-total")
                        || name.startsWith("notification-dns-resolver-"))
                .toList();
    }

    private java.util.List<String> resolverDeadlineThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("notification-dns-resolver-"))
                .toList();
    }

    private URI uri(String host, String path) {
        return URI.create("https://" + host + ':' + server.getAddress().getPort() + path);
    }

    private TlsFixture tlsFixture(String hostname) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        Instant now = Instant.now();
        X500Name subject = new X500Name("CN=" + hostname);
        JcaX509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
                subject, new BigInteger(96, new SecureRandom()),
                Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(1, ChronoUnit.DAYS)),
                subject, keyPair.getPublic());
        certificateBuilder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, hostname)));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer));

        char[] password = "local-fixture".toCharArray();
        KeyStore serverKeys = KeyStore.getInstance("PKCS12");
        serverKeys.load(null, password);
        serverKeys.setKeyEntry("server", keyPair.getPrivate(), password,
                new java.security.cert.Certificate[] {certificate});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeys, password);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, new SecureRandom());

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, password);
        trust.setCertificateEntry("server", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, trustManagers.getTrustManagers(), new SecureRandom());
        return new TlsFixture(serverContext, client);
    }

    private record TlsFixture(SSLContext serverContext, SSLContext clientContext) { }
}
