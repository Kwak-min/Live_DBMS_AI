package com.example.monitoring.notification.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class PushEndpointPolicy {
    private static final int MAX_ENDPOINT_LENGTH = 2_048;
    private static final Pattern HOST = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    private static final Set<String> DEFAULT_HOSTS = Set.of(
            "fcm.googleapis.com",
            "updates.push.services.mozilla.com",
            "web.push.apple.com",
            "notify.windows.com"
    );

    private final Set<String> allowedHosts;

    public PushEndpointPolicy(@Value("${PUSH_ALLOWED_HOSTS:}") String configuredHosts) {
        this.allowedHosts = parseAllowedHosts(configuredHosts);
    }

    public URI validate(String endpoint) {
        if (endpoint == null || endpoint.isBlank()
                || endpoint.codePointCount(0, endpoint.length()) > MAX_ENDPOINT_LENGTH) {
            throw invalid();
        }
        final URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException exception) {
            throw invalid();
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isBlank()
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || host.endsWith(".")) {
            throw invalid();
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!HOST.matcher(normalizedHost).matches() || !isAllowed(normalizedHost)) {
            throw invalid();
        }
        return uri;
    }

    private boolean isAllowed(String host) {
        return allowedHosts.stream().anyMatch(allowed -> host.equals(allowed) || host.endsWith('.' + allowed));
    }

    private Set<String> parseAllowedHosts(String configuredHosts) {
        if (configuredHosts == null || configuredHosts.isBlank()) {
            return DEFAULT_HOSTS;
        }
        Set<String> parsed = new LinkedHashSet<>();
        Arrays.stream(configuredHosts.split(",", -1))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .forEach(value -> {
                    if (!HOST.matcher(value).matches() || value.endsWith(".")) {
                        throw new IllegalArgumentException("Invalid Web Push host allowlist.");
                    }
                    parsed.add(value);
                });
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("Invalid Web Push host allowlist.");
        }
        return Set.copyOf(parsed);
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Web Push endpoint is not allowed.");
    }
}
