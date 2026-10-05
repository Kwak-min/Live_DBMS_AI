package com.example.monitoring.notification.integration;

import com.sun.net.httpserver.Headers;
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

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class LocalTlsProviderFixture implements AutoCloseable {
    private final HttpsServer server;
    private final ExecutorService executor;
    private final SSLContext clientContext;
    private final Function<String, Response> responses;
    private final CopyOnWriteArrayList<Capture> captures = new CopyOnWriteArrayList<>();
    private final AtomicInteger activeRequests = new AtomicInteger();

    private LocalTlsProviderFixture(String hostname, int port,
                                    Function<String, Response> responses) throws Exception {
        TlsMaterial material = tlsMaterial(hostname);
        this.clientContext = material.clientContext();
        this.responses = responses;
        this.server = HttpsServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(material.serverContext()));
        server.createContext("/", exchange -> {
            activeRequests.incrementAndGet();
            try {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                captures.add(new Capture(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getRawPath(),
                        copyHeaders(exchange.getRequestHeaders()),
                        requestBody,
                        System.nanoTime()));
                Response response = responses.apply(exchange.getRequestURI().getRawPath());
                response.headers().forEach(exchange.getResponseHeaders()::set);
                if (response.body().length == 0) {
                    exchange.sendResponseHeaders(response.status(), -1);
                } else {
                    exchange.sendResponseHeaders(response.status(), response.body().length);
                    exchange.getResponseBody().flush();
                    if (!response.bodyDelay().isZero()) {
                        Thread.sleep(response.bodyDelay().toMillis());
                    }
                    exchange.getResponseBody().write(response.body());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
                activeRequests.decrementAndGet();
            }
        });
        this.executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "part-c-local-tls-" + hostname);
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
    }

    public static LocalTlsProviderFixture start(String hostname, int port,
                                                Function<String, Response> responses) throws Exception {
        return new LocalTlsProviderFixture(hostname, port, responses);
    }

    public SSLContext clientContext() {
        return clientContext;
    }

    public List<Capture> captures() {
        return List.copyOf(captures);
    }

    public void awaitCaptureCount(int expected, Duration timeout) {
        await(timeout, () -> captures.size() >= expected, "capture count " + expected);
    }

    public void awaitIdle(Duration timeout) {
        await(timeout, () -> activeRequests.get() == 0, "fixture request cleanup");
    }

    private void await(Duration timeout, java.util.function.BooleanSupplier condition, String label) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting " + label, interrupted);
            }
        }
        throw new AssertionError("Timed out awaiting " + label);
    }

    @Override
    public void close() throws Exception {
        server.stop(0);
        executor.shutdownNow();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Local TLS fixture executor did not stop");
        }
    }

    private static Map<String, List<String>> copyHeaders(Headers source) {
        java.util.LinkedHashMap<String, List<String>> result = new java.util.LinkedHashMap<>();
        source.forEach((name, values) -> result.put(name, List.copyOf(values)));
        return Map.copyOf(result);
    }

    private static TlsMaterial tlsMaterial(String hostname) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        Instant now = Instant.now();
        X500Name subject = new X500Name("CN=" + hostname);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                new BigInteger(96, new SecureRandom()),
                java.util.Date.from(now.minus(1, ChronoUnit.DAYS)),
                java.util.Date.from(now.plus(1, ChronoUnit.DAYS)),
                subject,
                keyPair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, hostname)));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                .build(keyPair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));

        char[] password = "part-c-local-fixture".toCharArray();
        KeyStore serverKeys = KeyStore.getInstance("PKCS12");
        serverKeys.load(null, password);
        serverKeys.setKeyEntry("server", keyPair.getPrivate(), password,
                new java.security.cert.Certificate[]{certificate});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeys, password);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, new SecureRandom());

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, password);
        trust.setCertificateEntry("server", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trustManagers.getTrustManagers(), new SecureRandom());
        return new TlsMaterial(serverContext, clientContext);
    }

    public record Capture(String method, String path, Map<String, List<String>> headers,
                          byte[] body, long observedAtNanos) {
        public Capture {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public String toString() {
            return "Capture[method=" + method + ", path=" + path + ", redacted]";
        }
    }

    public record Response(int status, Map<String, String> headers, byte[] body,
                           Duration bodyDelay) {
        public Response {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        public static Response immediate(int status, String body) {
            return new Response(status, Map.of(),
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8), Duration.ZERO);
        }

        public static Response rateLimited(String retryAfter) {
            return new Response(429, Map.of("Retry-After", retryAfter), new byte[0], Duration.ZERO);
        }

        public static Response redirect(String location) {
            return new Response(302, Map.of("Location", location), new byte[0], Duration.ZERO);
        }

        public static Response slow(int status, String body, Duration bodyDelay) {
            return new Response(status, Map.of(),
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8), bodyDelay);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private record TlsMaterial(SSLContext serverContext, SSLContext clientContext) {
    }
}
