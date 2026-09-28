package com.example.monitoring.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {
    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void removesTrustedHopsFromRightAndReturnsFirstUntrustedAddress() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.3");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.2");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }
}
