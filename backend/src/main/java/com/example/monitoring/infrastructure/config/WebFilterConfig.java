package com.example.monitoring.infrastructure.config;

import com.example.monitoring.infrastructure.filter.ClientAccessLogFilter;
import com.example.monitoring.service.AuditLogService;
import com.example.monitoring.common.web.RequestIdFilter;
import com.example.monitoring.common.web.ClientIpResolver;
import com.example.monitoring.common.web.RequestBodySizeLimitFilter;
import com.example.monitoring.auth.web.ApiSecurityErrorWriter;
import com.example.monitoring.audit.web.MutationFailureAuditFilter;
import com.example.monitoring.service.AuditEventService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class WebFilterConfig {

    @Bean
    public RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    public RequestBodySizeLimitFilter requestBodySizeLimitFilter(ApiSecurityErrorWriter errorWriter) {
        return new RequestBodySizeLimitFilter(errorWriter);
    }

    @Bean
    public MutationFailureAuditFilter mutationFailureAuditFilter(AuditEventService auditEventService) {
        return new MutationFailureAuditFilter(auditEventService);
    }

    @Bean
    public FilterRegistrationBean<MutationFailureAuditFilter> mutationFailureAuditFilterRegistration(MutationFailureAuditFilter filter) {
        FilterRegistrationBean<MutationFailureAuditFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setName("mutationFailureAuditFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestBodySizeLimitFilter> requestBodySizeLimitFilterRegistration(RequestBodySizeLimitFilter filter) {
        FilterRegistrationBean<RequestBodySizeLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setName("requestBodySizeLimitFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    @Bean
    public ClientAccessLogFilter clientAccessLogFilter(AuditLogService auditLogService, ClientIpResolver clientIpResolver) {
        return new ClientAccessLogFilter(auditLogService, clientIpResolver);
    }

    @Bean
    public FilterRegistrationBean<ClientAccessLogFilter> clientAccessLogFilterRegistration(ClientAccessLogFilter filter) {
        FilterRegistrationBean<ClientAccessLogFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/*");
        registration.setName("clientAccessLogFilter");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration(RequestIdFilter filter) {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/*");
        registration.setName("requestIdFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
