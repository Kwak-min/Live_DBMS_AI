package com.example.monitoring.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public final class VapidConfigurationProvider {
    private final VapidConfiguration configuration;

    public VapidConfigurationProvider(
            @Value("${WEB_PUSH_VAPID_PUBLIC_KEY:}") String publicKey,
            @Value("${WEB_PUSH_VAPID_PRIVATE_KEY:}") String privateKey,
            @Value("${WEB_PUSH_VAPID_SUBJECT:}") String subject) {
        boolean publicEmpty = empty(publicKey);
        boolean privateEmpty = empty(privateKey);
        boolean subjectEmpty = empty(subject);
        if (publicEmpty && privateEmpty && subjectEmpty) {
            this.configuration = null;
        } else if (publicEmpty || privateEmpty || subjectEmpty) {
            throw new VapidConfigurationException("Incomplete Web Push signing configuration.");
        } else {
            this.configuration = new VapidConfiguration(publicKey, privateKey, subject);
        }
    }

    public Optional<VapidConfiguration> configured() {
        return Optional.ofNullable(configuration);
    }

    public VapidConfiguration requireConfigured() {
        if (configuration == null) {
            throw new VapidUnavailableException();
        }
        return configuration;
    }

    private boolean empty(String value) {
        return value == null || value.isBlank();
    }
}
