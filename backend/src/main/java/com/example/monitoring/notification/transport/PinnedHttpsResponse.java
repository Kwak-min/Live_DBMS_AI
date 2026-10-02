package com.example.monitoring.notification.transport;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class PinnedHttpsResponse {
    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    public PinnedHttpsResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
        if (statusCode < 100 || statusCode > 599 || headers == null || body == null) {
            throw new IllegalArgumentException("Invalid notification response.");
        }
        this.statusCode = statusCode;
        this.headers = headers.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> entry.getKey().toLowerCase(Locale.ROOT),
                entry -> List.copyOf(entry.getValue()),
                (left, right) -> left));
        this.body = body.clone();
    }

    public int statusCode() {
        return statusCode;
    }

    public Optional<String> firstHeader(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()).stream().findFirst();
    }

    public byte[] body() {
        return body.clone();
    }

    @Override
    public String toString() {
        return "PinnedHttpsResponse[statusCode=" + statusCode + ", bodyLength=" + body.length + ']';
    }
}
