package com.example.monitoring.realtime.stomp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
final class StrictOriginHandshakeInterceptor implements HandshakeInterceptor {
    private final String publicOrigin;

    StrictOriginHandshakeInterceptor(@Value("${app.auth.public-origin}") String publicOrigin) {
        this.publicOrigin = publicOrigin;
    }

    String publicOrigin() {
        return publicOrigin;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        List<String> origins = request.getHeaders().get(HttpHeaders.ORIGIN);
        boolean exactOrigin = origins != null && origins.size() == 1 && publicOrigin.equals(origins.get(0));
        boolean noQuery = request.getURI().getRawQuery() == null;
        if (exactOrigin && noQuery) {
            return true;
        }
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
