package com.example.monitoring.notification.webpush;

import com.example.monitoring.notification.config.VapidConfiguration;
import com.example.monitoring.notification.config.VapidConfigurationProvider;
import com.example.monitoring.notification.security.PushEndpointPolicy;
import com.example.monitoring.notification.transport.NotificationProvider;
import com.example.monitoring.notification.transport.PinnedHttpsRequest;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.Header;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.util.EntityUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.Security;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public final class WebPushRequestPreparer {
    private static final int TTL_SECONDS = 600;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final VapidConfigurationProvider configurationProvider;
    private final WebPushPayloadRenderer payloadRenderer;
    private final PushEndpointPolicy endpointPolicy;

    public WebPushRequestPreparer(VapidConfigurationProvider configurationProvider,
                                  WebPushPayloadRenderer payloadRenderer,
                                  PushEndpointPolicy endpointPolicy) {
        this.configurationProvider = configurationProvider;
        this.payloadRenderer = payloadRenderer;
        this.endpointPolicy = endpointPolicy;
    }

    public PinnedHttpsRequest prepare(WebPushRecipient recipient, WebPushMessage message) {
        URI endpoint = endpointPolicy.validate(recipient.endpoint());
        if ("fcm.googleapis.com".equalsIgnoreCase(endpoint.getHost())
                && endpoint.getRawPath().startsWith("/fcm/send/")) {
            endpoint = endpointPolicy.validate(endpoint.toString().replaceFirst("/fcm/send/", "/wp/"));
        }
        VapidConfiguration configuration = configurationProvider.requireConfigured();
        ensureBouncyCastle();
        try {
            PushService pushService = new PushService(
                    configuration.publicKey(), configuration.privateKey(), configuration.subject());
            Notification notification = new Notification(endpoint.toString(), recipient.p256dh(), recipient.auth(),
                    payloadRenderer.render(message), TTL_SECONDS);
            HttpPost prepared = pushService.preparePost(notification, Encoding.AES128GCM);
            if (!endpoint.equals(prepared.getURI())) {
                throw new IllegalStateException("Prepared Web Push endpoint changed.");
            }
            Map<String, String> headers = headers(prepared.getAllHeaders());
            requirePreparedHeaders(headers);
            byte[] body = prepared.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(prepared.getEntity());
            return new PinnedHttpsRequest(NotificationProvider.WEB_PUSH, endpoint, "POST", headers, body, REQUEST_TIMEOUT);
        } catch (GeneralSecurityException | java.io.IOException | org.jose4j.lang.JoseException exception) {
            throw new WebPushPreparationException(exception);
        }
    }

    private Map<String, String> headers(Header[] values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Header header : values) {
            if (result.put(header.getName(), header.getValue()) != null) {
                throw new IllegalStateException("Duplicate Web Push request header.");
            }
        }
        return result;
    }

    private void requirePreparedHeaders(Map<String, String> headers) {
        Map<String, String> lower = new LinkedHashMap<>();
        headers.forEach((name, value) -> lower.put(name.toLowerCase(Locale.ROOT), value));
        if (!"aes128gcm".equals(lower.get("content-encoding"))
                || !Integer.toString(TTL_SECONDS).equals(lower.get("ttl"))
                || !"application/octet-stream".equalsIgnoreCase(lower.getOrDefault("content-type", ""))
                || lower.getOrDefault("authorization", "").isBlank()) {
            throw new IllegalStateException("Web Push request preparation produced invalid headers.");
        }
    }

    private static synchronized void ensureBouncyCastle() {
        Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (provider == null) {
            Security.addProvider(new BouncyCastleProvider());
            provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        }
        if (!(provider instanceof BouncyCastleProvider) || !"1.77".equals(provider.getVersionStr())) {
            throw new IllegalStateException("Required cryptography provider is unavailable.");
        }
    }
}
