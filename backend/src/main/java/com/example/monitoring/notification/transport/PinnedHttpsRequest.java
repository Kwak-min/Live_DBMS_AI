package com.example.monitoring.notification.transport;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class PinnedHttpsRequest {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private final NotificationProvider provider;
    private final URI uri;
    private final String method;
    private final Map<String, String> headers;
    private final byte[] body;
    private final Duration timeout;

    public PinnedHttpsRequest(NotificationProvider provider, URI uri, String method,
                              Map<String, String> headers, byte[] body, Duration timeout) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.uri = Objects.requireNonNull(uri, "uri");
        if (!"POST".equals(method)) {
            throw new IllegalArgumentException("Notification transport only supports POST.");
        }
        if (body == null || body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Invalid notification request body.");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Invalid notification request timeout.");
        }
        this.method = method;
        this.headers = validateHeaders(headers);
        this.body = body.clone();
        this.timeout = timeout;
    }

    public NotificationProvider provider() {
        return provider;
    }

    public URI uri() {
        return uri;
    }

    public String method() {
        return method;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public byte[] body() {
        return body.clone();
    }

    public Duration timeout() {
        return timeout;
    }

    @Override
    public String toString() {
        return "PinnedHttpsRequest[provider=" + provider + ", redacted]";
    }

    private Map<String, String> validateHeaders(Map<String, String> input) {
        if (input == null || input.size() > 32) {
            throw new IllegalArgumentException("Invalid notification request headers.");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        input.forEach((name, value) -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!HEADER_NAME.matcher(name == null ? "" : name).matches() || value == null
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.length() > 8_192
                    || lower.equals("host") || lower.equals("content-length") || lower.equals("connection")) {
                throw new IllegalArgumentException("Invalid notification request headers.");
            }
            copy.put(name, value);
        });
        return Map.copyOf(copy);
    }
}
