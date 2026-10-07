package com.example.monitoring.auth.service;

import com.example.monitoring.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestOriginValidatorTest {

    private final RequestOriginValidator validator = new RequestOriginValidator("http://localhost:5173");

    @Test
    void acceptsExactOriginAndRejectsLookalikeOrigin() {
        MockHttpServletRequest accepted = new MockHttpServletRequest();
        accepted.addHeader("Origin", "http://localhost:5173");
        validator.validateMutation(accepted);

        MockHttpServletRequest rejected = new MockHttpServletRequest();
        rejected.addHeader("Origin", "http://localhost:5173.evil.example");
        assertThatThrownBy(() -> validator.validateMutation(rejected))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void acceptsLocalhost3000And127001Origins() {
        MockHttpServletRequest req3000 = new MockHttpServletRequest();
        req3000.addHeader("Origin", "http://localhost:3000");
        validator.validateMutation(req3000);

        MockHttpServletRequest req127 = new MockHttpServletRequest();
        req127.addHeader("Origin", "http://127.0.0.1:5173");
        validator.validateMutation(req127);
    }

    @Test
    void rejectsOriginHeaderContainingAPath() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "http://localhost:5173/not-an-origin");

        assertThatThrownBy(() -> validator.validateMutation(request))
                .isInstanceOf(ApiException.class);
    }
}
