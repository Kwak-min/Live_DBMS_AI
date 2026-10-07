package com.example.monitoring.auth.service;

import com.example.monitoring.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;

@Service
public class RequestOriginValidator {

    private final String publicOrigin;

    public RequestOriginValidator(@Value("${app.auth.public-origin}") String publicOrigin) {
        this.publicOrigin = strictOrigin(publicOrigin);
    }

    public void validateMutation(HttpServletRequest request) {
        String suppliedOrigin = request.getHeader("Origin");
        if (suppliedOrigin != null && !suppliedOrigin.isBlank()) {
            if (!publicOrigin.equals(strictOrigin(suppliedOrigin))) throw notAllowed();
            return;
        }
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank() || !publicOrigin.equals(originOf(referer))) {
            throw notAllowed();
        }
    }

    public void validateCsrfBootstrap(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            if (!publicOrigin.equals(strictOrigin(origin))) {
                throw notAllowed();
            }
            return;
        }
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
            throw notAllowed();
        }
    }

    private String originOf(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null
                    || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
                throw notAllowed();
            }
            int port = uri.getPort();
            boolean defaultPort = port == -1
                    || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                    || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
            return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase()
                    + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException exception) {
            throw notAllowed();
        }
    }

    private String strictOrigin(String value) {
        try {
            URI uri = URI.create(value);
            String path = uri.getRawPath();
            if ((path != null && !path.isEmpty()) || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw notAllowed();
            }
            return originOf(value);
        } catch (IllegalArgumentException exception) {
            throw notAllowed();
        }
    }

    private ApiException notAllowed() {
        return new ApiException(HttpStatus.FORBIDDEN, "ORIGIN_NOT_ALLOWED", "허용되지 않은 요청 출처입니다.");
    }
}
