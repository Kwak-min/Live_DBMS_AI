package com.example.monitoring.notification.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicAddressPolicyTest {
    private final PublicAddressPolicy policy = new PublicAddressPolicy();

    @Test
    void acceptsGloballyRoutableAddresses() throws Exception {
        assertThatCode(() -> policy.requirePublic(InetAddress.getByName("8.8.8.8")))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.requirePublic(InetAddress.getByName("2606:4700:4700::1111")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPrivateMetadataDocumentationAndMappedAddresses() throws Exception {
        for (String value : new String[] {
                "0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254",
                "168.63.129.16", "172.16.0.1", "192.168.0.1", "192.0.2.1", "198.18.0.1",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "::", "::1", "fc00::1",
                "fe80::1", "ff00::1", "2001:db8::1", "::ffff:127.0.0.1"
        }) {
            InetAddress address = InetAddress.getByName(value);
            assertThatThrownBy(() -> policy.requirePublic(address))
                    .as(value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
