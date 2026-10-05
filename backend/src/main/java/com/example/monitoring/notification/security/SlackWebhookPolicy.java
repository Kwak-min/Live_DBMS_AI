package com.example.monitoring.notification.security;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

@Component
public final class SlackWebhookPolicy {
    private static final int MAX_URL_LENGTH = 2_048;
    private static final Pattern PATH = Pattern.compile("/services/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+");

    public URI validate(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank() || webhookUrl.length() > MAX_URL_LENGTH) {
            throw invalid();
        }
        final URI uri;
        try {
            uri = new URI(webhookUrl);
        } catch (URISyntaxException exception) {
            throw invalid();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"hooks.slack.com".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !PATH.matcher(uri.getRawPath()).matches()) {
            throw invalid();
        }
        return uri;
    }

    public String canonicalIdentity(String webhookUrl) {
        URI uri = validate(webhookUrl);
        return "https://hooks.slack.com" + uri.getRawPath();
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Slack webhook URL is not allowed.");
    }
}
