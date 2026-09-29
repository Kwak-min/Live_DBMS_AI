package com.example.monitoring.common.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/** Validation shared by API identifiers that must remain exactly representable in JSON clients. */
public final class ApiId {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private ApiId() {
    }

    public static long require(Long value, String field) {
        if (value == null) {
            throw invalid(field, "REQUIRED", field + "은(는) 필수입니다.");
        }
        if (value < 1 || value > MAX_SAFE_INTEGER) {
            throw invalid(field, "OUT_OF_RANGE", field + "은(는) 1~9007199254740991 범위여야 합니다.");
        }
        return value;
    }

    public static void validateOptional(Long value, String field) {
        if (value != null) require(value, field);
    }

    private static ApiException invalid(String field, String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값을 확인해 주세요.",
                List.of(new FieldErrorResponse(field, code, message)));
    }
}
