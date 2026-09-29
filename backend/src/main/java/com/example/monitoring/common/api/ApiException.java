package com.example.monitoring.common.api;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** Use for expected business failures whose safe public response is known. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<FieldErrorResponse> fieldErrors;
    private final Map<String, String> responseHeaders;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<FieldErrorResponse> fieldErrors) {
        this(status, code, message, fieldErrors, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<FieldErrorResponse> fieldErrors,
                        Map<String, String> responseHeaders) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.responseHeaders = Map.copyOf(responseHeaders);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<FieldErrorResponse> getFieldErrors() {
        return fieldErrors;
    }

    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }
}
