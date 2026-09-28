package com.example.monitoring.common.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiIdTest {

    @Test
    void acceptsPositiveJsonSafeInteger() {
        assertThat(ApiId.require(ApiId.MAX_SAFE_INTEGER, "id")).isEqualTo(ApiId.MAX_SAFE_INTEGER);
    }

    @Test
    void rejectsZeroAndValuesAboveJsonSafeInteger() {
        assertThatThrownBy(() -> ApiId.require(0L, "id"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("VALIDATION_ERROR");
        assertThatThrownBy(() -> ApiId.require(ApiId.MAX_SAFE_INTEGER + 1, "id"))
                .isInstanceOf(ApiException.class);
    }
}
