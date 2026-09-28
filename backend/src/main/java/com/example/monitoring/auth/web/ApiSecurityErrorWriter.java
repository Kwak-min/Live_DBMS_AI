package com.example.monitoring.auth.web;

import com.example.monitoring.common.api.ApiErrorResponse;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ApiSecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletRequest request, HttpServletResponse response, ApiException exception)
            throws IOException {
        response.setStatus(exception.getStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        exception.getResponseHeaders().forEach(response::setHeader);
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        ApiErrorResponse body = new ApiErrorResponse(
                exception.getCode(), exception.getMessage(),
                requestId instanceof String value ? value : "unknown",
                exception.getFieldErrors() == null ? List.of() : exception.getFieldErrors());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
