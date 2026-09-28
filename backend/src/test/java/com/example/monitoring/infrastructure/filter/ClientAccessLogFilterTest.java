package com.example.monitoring.infrastructure.filter;

import com.example.monitoring.service.AuditLogService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ClientAccessLogFilterTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private FilterChain filterChain;

    private ClientAccessLogFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ClientAccessLogFilter(auditLogService);
    }

    @Test
    @DisplayName("Extracts first IP when X-Forwarded-For contains comma-separated proxy IP list")
    void extractClientIp_xForwardedFor_multipleIps() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");

        String clientIp = filter.extractClientIp(request);

        assertEquals("203.0.113.195", clientIp);
    }

    @Test
    @DisplayName("Falls back to request.getRemoteAddr when proxy headers are missing")
    void extractClientIp_fallbackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");

        String clientIp = filter.extractClientIp(request);

        assertEquals("192.168.1.50", clientIp);
    }

    @Test
    @DisplayName("Filter logs request details via AuditLogService after chain execution")
    void doFilter_logsAccessToAuditService() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/databases/1/ping");
        request.addHeader("User-Agent", "TestAgent/1.0");
        request.addHeader("X-Forwarded-For", "198.51.100.22");

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(auditLogService).logAccess(
                eq("198.51.100.22"),
                eq("GET"),
                eq("/api/databases/1/ping"),
                eq("TestAgent/1.0"),
                eq(200),
                anyLong()
        );
    }
}
