package com.example.monitoring.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesServerRequestIdAndReturnsItInResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.addHeader(RequestIdFilter.HEADER_NAME, "client-value-must-not-be-trusted");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        assertThat(requestId).isNotBlank();
        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo(requestId);
        assertThat(requestId).isNotEqualTo("client-value-must-not-be-trusted");
    }
}
