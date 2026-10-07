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
import java.util.Objects;

@Component
public final class WebPushRequestPreparer {
    private static final int TTL_SECONDS = 600;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String FCM_HOST = "fcm.googleapis.com";
    private static final String FCM_SEND_PATH = "/fcm/send/";
    private static final String FCM_VAPID_PATH = "/wp/";

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
        VapidConfiguration configuration = configurationProvider.requireConfigured();
        ensureBouncyCastle();
        try {
            PushService pushService = new PushService(
                    configuration.publicKey(), configuration.privateKey(), configuration.subject());
            Notification notification = new Notification(recipient.endpoint(), recipient.p256dh(), recipient.auth(),
                    payloadRenderer.render(message), TTL_SECONDS);
            HttpPost prepared = pushService.preparePost(notification, Encoding.AES128GCM);
            URI target = sendTarget(endpoint, prepared.getURI());
            Map<String, String> headers = headers(prepared.getAllHeaders());
            requirePreparedHeaders(headers);
            byte[] body = prepared.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(prepared.getEntity());
            return new PinnedHttpsRequest(NotificationProvider.WEB_PUSH, target, "POST", headers, body, REQUEST_TIMEOUT);
        } catch (GeneralSecurityException | java.io.IOException | org.jose4j.lang.JoseException exception) {
            throw new WebPushPreparationException(exception);
        }
    }

    /**
     * 실제로 보낼 주소. web-push 라이브러리는 Chrome(FCM)의 구독 경로 {@code /fcm/send/{token}}을 VAPID 전송 경로
     * {@code /wp/{token}}으로 바꿔 보낸다. 그 변경 하나만 허용하고 바뀐 주소도 같은 Push 정책으로 다시 검증한다.
     * 그 밖의 주소 변경은 거절한다.
     */
    URI sendTarget(URI endpoint, URI prepared) {
        if (endpoint.equals(prepared)) {
            return endpoint;
        }
        String path = endpoint.getRawPath();
        boolean fcmRewrite = FCM_HOST.equalsIgnoreCase(endpoint.getHost())
                && path != null && path.startsWith(FCM_SEND_PATH) && path.length() > FCM_SEND_PATH.length()
                && "https".equalsIgnoreCase(prepared.getScheme())
                && endpoint.getHost().equalsIgnoreCase(prepared.getHost())
                && endpoint.getPort() == prepared.getPort()
                && prepared.getRawUserInfo() == null && prepared.getRawFragment() == null
                && Objects.equals(endpoint.getRawQuery(), prepared.getRawQuery())
                && (FCM_VAPID_PATH + path.substring(FCM_SEND_PATH.length())).equals(prepared.getRawPath());
        if (!fcmRewrite) {
            throw new IllegalStateException("Prepared Web Push endpoint changed.");
        }
        return endpointPolicy.validate(prepared.toString());
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
