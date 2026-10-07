package com.example.monitoring.auth.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigurationTest {

    @Test
    void corsConfigurationPermitsFrontendOrigins() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.auth.public-origin", "https://monitoring.example.com");

        SecurityConfig config = new SecurityConfig(null, null, null, env);
        CorsConfigurationSource source = config.corsConfigurationSource();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/metrics");
        CorsConfiguration cors = source.getCorsConfiguration(request);

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(cors.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
        assertThat(cors.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cors.checkOrigin("http://127.0.0.1:3000")).isEqualTo("http://127.0.0.1:3000");
        assertThat(cors.checkOrigin("http://127.0.0.1:5173")).isEqualTo("http://127.0.0.1:5173");
        assertThat(cors.checkOrigin("https://monitoring.example.com")).isEqualTo("https://monitoring.example.com");
        assertThat(cors.checkOrigin("http://evil.com")).isNull();
        assertThat(cors.getAllowedMethods()).contains("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
