package com.example.monitoring.notification.slack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Component
public final class SlackIncidentLinkFactory {
    private final String publicOrigin;

    public SlackIncidentLinkFactory(@Value("${app.auth.public-origin}") String configuredOrigin) {
        this.publicOrigin = validate(configuredOrigin);
    }

    public String incident(UUID incidentId) {
        return publicOrigin + "/incidents/" + Objects.requireNonNull(incidentId, "incidentId");
    }

    private String validate(String value) {
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException | NullPointerException exception) {
            throw invalid();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        if ((!"https".equals(scheme) && !isLocalHttp(scheme, host)) || host.isEmpty()
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (path != null && !path.isEmpty()) || uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw invalid();
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        String renderedHost = host.indexOf(':') >= 0 ? '[' + host + ']' : host;
        return scheme + "://" + renderedHost + (defaultPort ? "" : ":" + port);
    }

    private boolean isLocalHttp(String scheme, String host) {
        return "http".equals(scheme) && ("localhost".equals(host) || "127.0.0.1".equals(host)
                || "::1".equals(host));
    }

    private IllegalStateException invalid() {
        return new IllegalStateException("PUBLIC_ORIGIN is invalid for Slack incident links.");
    }
}
