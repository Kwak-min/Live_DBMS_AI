package com.example.monitoring.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenServiceTest {

    private final RefreshTokenService service = new RefreshTokenService();

    @Test
    void generatesOpaqueThirtyTwoByteBase64UrlTokenAndHashesIt() {
        String token = service.generate();

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(service.hash(token)).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(service.hash(token)).isEqualTo(service.hash(token));
    }

    @Test
    void rejectsValuesOutsideTheRefreshTokenFormat() {
        assertThat(service.hash(null)).isNull();
        assertThat(service.hash("not-a-refresh-token")).isNull();
        assertThat(service.hash("가".repeat(43))).isNull();
    }
}
