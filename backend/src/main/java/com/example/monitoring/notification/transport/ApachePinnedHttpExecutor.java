package com.example.monitoring.notification.transport;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.HttpsSupport;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public final class ApachePinnedHttpExecutor implements PinnedHttpExecutor {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final SSLContext sslContext;

    public ApachePinnedHttpExecutor() {
        this(SSLContexts.createSystemDefault());
    }

    public ApachePinnedHttpExecutor(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    @Override
    public PinnedHttpsResponse execute(PinnedHttpsRequest request, InetAddress[] pinnedAddresses) {
        String expectedHost = request.uri().getHost();
        DnsResolver pinnedResolver = new SingleHostDnsResolver(expectedHost, pinnedAddresses);
        var sslSocketFactory = SSLConnectionSocketFactoryBuilder.create()
                .setSslContext(sslContext)
                .setHostnameVerifier(HttpsSupport.getDefaultHostnameVerifier())
                .build();
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(sslSocketFactory)
                .setDnsResolver(pinnedResolver)
                .build();
        manager.setMaxTotal(1);
        manager.setDefaultMaxPerRoute(1);

        Timeout timeout = Timeout.ofMilliseconds(request.timeout().toMillis());
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout)
                .setRedirectsEnabled(false)
                .build();

        HttpUriRequestBase outbound = new HttpUriRequestBase(request.method(), request.uri());
        request.headers().forEach(outbound::setHeader);
        outbound.setEntity(new ByteArrayEntity(request.body(), null));
        ExecutorService deadlineExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notification-https-request");
            thread.setDaemon(true);
            return thread;
        });
        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(config)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                .build()) {
            Future<PinnedHttpsResponse> result = deadlineExecutor.submit(() -> executeOnce(client, outbound));
            try {
                return result.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                abort(outbound, client, result);
                throw new PinnedTransportException(PinnedTransportException.Kind.TIMEOUT, exception);
            } catch (InterruptedException exception) {
                abort(outbound, client, result);
                Thread.currentThread().interrupt();
                throw new PinnedTransportException(PinnedTransportException.Kind.CONNECTION, exception);
            } catch (ExecutionException exception) {
                throw translate(exception.getCause());
            }
        } catch (IOException exception) {
            if (hasTimeoutCause(exception)) {
                throw new PinnedTransportException(PinnedTransportException.Kind.TIMEOUT, exception);
            }
            throw new PinnedTransportException(PinnedTransportException.Kind.CONNECTION, exception);
        } finally {
            deadlineExecutor.shutdownNow();
            awaitTermination(deadlineExecutor);
        }
    }

    private PinnedHttpsResponse executeOnce(CloseableHttpClient client, HttpUriRequestBase outbound) throws IOException {
        try (CloseableHttpResponse response = client.execute(outbound)) {
            return new PinnedHttpsResponse(response.getCode(), responseHeaders(response.getHeaders()),
                    readBounded(response.getEntity()));
        }
    }

    private void abort(HttpUriRequestBase outbound, CloseableHttpClient client,
                       Future<PinnedHttpsResponse> result) {
        outbound.cancel();
        result.cancel(true);
        try {
            client.close();
        } catch (IOException ignored) {
            result.cancel(true);
        }
    }

    private PinnedTransportException translate(Throwable exception) {
        if (hasTimeoutCause(exception)) {
            return new PinnedTransportException(PinnedTransportException.Kind.TIMEOUT, exception);
        }
        return new PinnedTransportException(PinnedTransportException.Kind.CONNECTION, exception);
    }

    private void awaitTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, List<String>> responseHeaders(Header[] headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Header header : headers) {
            result.computeIfAbsent(header.getName().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(header.getValue());
        }
        return result;
    }

    private byte[] readBounded(HttpEntity entity) throws IOException {
        if (entity == null) {
            return new byte[0];
        }
        if (entity.getContentLength() > MAX_RESPONSE_BYTES) {
            throw new IOException("Notification response was too large.");
        }
        try (InputStream input = entity.getContent()) {
            byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("Notification response was too large.");
            }
            return bytes;
        }
    }

    private boolean hasTimeoutCause(Throwable value) {
        Throwable current = value;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class SingleHostDnsResolver implements DnsResolver {
        private final String expectedHost;
        private final InetAddress[] pinnedAddresses;

        private SingleHostDnsResolver(String expectedHost, InetAddress[] pinnedAddresses) {
            if (expectedHost == null || pinnedAddresses == null || pinnedAddresses.length == 0) {
                throw new IllegalArgumentException("Pinned notification destination is required.");
            }
            this.expectedHost = expectedHost;
            this.pinnedAddresses = pinnedAddresses.clone();
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!expectedHost.equalsIgnoreCase(host)) {
                throw new UnknownHostException("Unexpected notification destination host.");
            }
            return pinnedAddresses.clone();
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            if (!expectedHost.equalsIgnoreCase(host)) {
                throw new UnknownHostException("Unexpected notification destination host.");
            }
            return expectedHost;
        }

        @Override
        public String toString() {
            return "SingleHostDnsResolver[host=" + expectedHost + ", addresses=redacted]";
        }
    }
}
