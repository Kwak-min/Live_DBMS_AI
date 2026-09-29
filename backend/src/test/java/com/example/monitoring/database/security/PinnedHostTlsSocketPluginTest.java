package com.example.monitoring.database.security;

import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.plugin.TlsSocketPlugin;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class PinnedHostTlsSocketPluginTest {
    @Test
    void isDiscoverableByMariaDbServiceLoader() {
        assertThat(ServiceLoader.load(TlsSocketPlugin.class).stream()
                .map(provider -> provider.get().type()))
                .contains(PinnedHostTlsSocketPlugin.TYPE);
    }
}
