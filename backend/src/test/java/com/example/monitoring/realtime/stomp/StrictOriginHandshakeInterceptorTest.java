package com.example.monitoring.realtime.stomp;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StrictOriginHandshakeInterceptorTest {
    private final StrictOriginHandshakeInterceptor interceptor =
            new StrictOriginHandshakeInterceptor("https://monitor.example");

    @Test
    void acceptsAllowedOriginsAndExtractsQueryParameters() {
        HashMap<String, Object> attributes = new HashMap<>();
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setQueryString("access_token=secret");
        servletRequest.addHeader(HttpHeaders.ORIGIN, "https://monitor.example");
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ServletServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes.get("token")).isEqualTo("secret");

        assertThat(handshake("https://monitor.example", null)).isTrue();
        assertThat(handshake("http://localhost:3000", null)).isTrue();
        assertThat(handshake("http://localhost:5173", null)).isTrue();
        assertThat(handshake(null, null)).isFalse();
        assertThat(handshake("https://other.example", null)).isFalse();
    }

    @Test
    void rejectsMultipleOriginHeaders() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.addHeader(HttpHeaders.ORIGIN,
                new String[]{"https://monitor.example", "https://other.example"});
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ServletServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    private boolean handshake(String origin, String query) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setQueryString(query);
        if (origin != null) {
            servletRequest.addHeader(HttpHeaders.ORIGIN, origin);
        }
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ServletServerHttpResponse response = new ServletServerHttpResponse(servletResponse);
        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>());
        if (!accepted) {
            assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        }
        return accepted;
    }
}
