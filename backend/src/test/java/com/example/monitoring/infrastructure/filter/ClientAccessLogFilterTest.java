package com.example.monitoring.infrastructure.filter;

import com.example.monitoring.service.AuditLogService;
import com.example.monitoring.common.web.ClientIpResolver;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ClientAccessLogFilterTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private FilterChain filterChain;

    private ClientAccessLogFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ClientAccessLogFilter(auditLogService, clientIpResolver);
    }

    @Test
    @DisplayName("Delegates client IP selection to the trusted-proxy resolver")
    void extractClientIp_delegatesToResolver() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");

        org.mockito.Mockito.when(clientIpResolver.resolve(request)).thenReturn("203.0.113.195");
        String clientIp = filter.extractClientIp(request);

        assertEquals("203.0.113.195", clientIp);
    }

    @Test
    @DisplayName("Returns the resolver's direct-peer result when forwarded headers are absent")
    void extractClientIp_fallbackToResolver() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");

        org.mockito.Mockito.when(clientIpResolver.resolve(request)).thenReturn("192.168.1.50");
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
        org.mockito.Mockito.when(clientIpResolver.resolve(request)).thenReturn("198.51.100.22");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(auditLogService).logAccess(
                isNull(),
                eq("198.51.100.22"),
                eq("GET"),
                eq("/api/databases/1/ping"),
                eq(200),
                anyLong(),
                any(java.util.UUID.class)
        );
    }

    @Test
    @DisplayName("Access log persistence failure does not replace the API response")
    void doFilter_ignoresAccessLogPersistenceFailure() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/databases");
        MockHttpServletResponse response = new MockHttpServletResponse();
        org.mockito.Mockito.when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        org.mockito.Mockito.when(auditLogService.logAccess(isNull(), anyString(), anyString(), anyString(),
                        anyInt(), anyLong(), any(java.util.UUID.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> filter.doFilter(request, response, filterChain));
        verify(filterChain).doFilter(request, response);
    }
}
