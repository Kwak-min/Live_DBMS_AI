package com.example.monitoring.notification.config;

import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VapidConfigurationTest {
    @Test
    void allEmptyIsDisabledWithoutGeneratingKeys() {
        VapidConfigurationProvider provider = new VapidConfigurationProvider("", "", "");

        assertThat(provider.configured()).isEmpty();
        assertThatThrownBy(provider::requireConfigured)
                .isInstanceOf(VapidUnavailableException.class)
                .hasMessageNotContaining("private");
    }

    @Test
    void validatesP256KeyPairAndRedactsIt() {
        byte[] privateKey = new byte[32];
        privateKey[31] = 1;
        byte[] publicKey = CustomNamedCurves.getByName("secp256r1").getG().getEncoded(false);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

        VapidConfiguration config = new VapidConfigurationProvider(
                encoder.encodeToString(publicKey), encoder.encodeToString(privateKey), "mailto:ops@example.com")
                .requireConfigured();

        assertThat(config.publicKeyBytes()).containsExactly(publicKey);
        assertThat(config.privateKeyBytes()).containsExactly(privateKey);
        assertThat(config.toString()).doesNotContain(encoder.encodeToString(privateKey), encoder.encodeToString(publicKey));
    }

    @Test
    void rejectsPartialMismatchedAndUnsafeSubjectConfiguration() {
        byte[] privateKey = new byte[32];
        privateKey[31] = 2;
        byte[] publicKey = CustomNamedCurves.getByName("secp256r1").getG().getEncoded(false);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

        assertThatThrownBy(() -> new VapidConfigurationProvider("only-public", "", ""))
                .isInstanceOf(VapidConfigurationException.class);
        assertThatThrownBy(() -> new VapidConfigurationProvider(
                encoder.encodeToString(publicKey), encoder.encodeToString(privateKey), "file:///tmp/key"))
                .isInstanceOf(VapidConfigurationException.class);
    }
}
