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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
class StrictOriginHandshakeInterceptor implements HandshakeInterceptor {
    private final String publicOrigin;
    private final Set<String> allowedOrigins;

    StrictOriginHandshakeInterceptor(@Value("${app.auth.public-origin}") String publicOrigin) {
        this.publicOrigin = publicOrigin;
        Set<String> set = new LinkedHashSet<>();
        if (publicOrigin != null && !publicOrigin.isBlank()) {
            set.add(publicOrigin);
        }
        set.add("http://localhost:3000");
        set.add("http://localhost:5173");
        set.add("http://127.0.0.1:3000");
        set.add("http://127.0.0.1:5173");
        this.allowedOrigins = Collections.unmodifiableSet(set);
    }

    String publicOrigin() {
        return publicOrigin;
    }

    String[] allowedOrigins() {
        return allowedOrigins.toArray(new String[0]);
    }

    boolean isAllowedOrigin(String origin) {
        return origin != null && allowedOrigins.contains(origin);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        List<String> origins = request.getHeaders().get(HttpHeaders.ORIGIN);
        boolean exactOrigin = origins != null && origins.size() == 1 && isAllowedOrigin(origins.get(0));
        if (!exactOrigin) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        URI uri = request.getURI();
        if (uri.getRawQuery() != null) {
            String query = uri.getRawQuery();
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2) {
                    try {
                        String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                        String val = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        attributes.put(key, val);
                        if ("access_token".equalsIgnoreCase(key) || "token".equalsIgnoreCase(key)) {
                            attributes.put("token", val);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
