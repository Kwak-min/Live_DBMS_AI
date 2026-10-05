package com.example.monitoring.notification.security;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushEndpointPolicyTest {
    private final PushEndpointPolicy defaults = new PushEndpointPolicy("");

    @Test
    void acceptsOnlyHttps443OnExactOrLabelBoundaryAllowedHosts() {
        assertThat(defaults.validate("https://fcm.googleapis.com/push/abc"))
                .isEqualTo(URI.create("https://fcm.googleapis.com/push/abc"));
        assertThat(defaults.validate("https://tenant.fcm.googleapis.com/push/abc").getHost())
                .isEqualTo("tenant.fcm.googleapis.com");
        assertThat(defaults.validate("https://notify.windows.com/push?token=opaque%2Fvalue").toString())
                .isEqualTo("https://notify.windows.com/push?token=opaque%2Fvalue");

        assertThatThrownBy(() -> defaults.validate("http://fcm.googleapis.com/push/abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.validate("https://fcm.googleapis.com:444/push/abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.validate("https://fcm.googleapis.com.evil.test/push/abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.validate("https://evilfcm.googleapis.com/push/abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmbiguousAuthorityAndOversizedEndpoint() {
        assertThatThrownBy(() -> defaults.validate("https://user@fcm.googleapis.com/push"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.validate("https://fcm.googleapis.com/push#fragment"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.validate("https://fcm.googleapis.com./push"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.validate("https://127.0.0.1/push"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.validate("https://fcm.googleapis.com/" + "x".repeat(2048)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsEndpointLengthByUnicodeCodePoint() {
        String prefix = "https://fcm.googleapis.com/";
        String accepted = prefix + "😀".repeat(2_048 - prefix.codePointCount(0, prefix.length()));
        assertThat(defaults.validate(accepted).toString()).isEqualTo(accepted);

        assertThatThrownBy(() -> defaults.validate(accepted + "😀"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
