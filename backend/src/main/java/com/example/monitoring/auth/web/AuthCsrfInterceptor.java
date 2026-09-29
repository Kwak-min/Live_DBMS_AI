package com.example.monitoring.auth.web;

import com.example.monitoring.auth.service.CsrfTokenService;
import com.example.monitoring.auth.service.RequestOriginValidator;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class AuthCsrfInterceptor implements HandlerInterceptor {

    public static final String CSRF_COOKIE = "csrfSession";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private final CsrfTokenService csrfTokenService;
    private final RequestOriginValidator originValidator;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        originValidator.validateMutation(request);
        csrfTokenService.validate(cookie(request, CSRF_COOKIE), request.getHeader(CSRF_HEADER));
        return true;
    }

    public static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
