package com.example.monitoring.infrastructure.config;

import com.example.monitoring.infrastructure.filter.ClientAccessLogFilter;
import com.example.monitoring.service.AuditLogService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebFilterConfig {

    @Bean
    public ClientAccessLogFilter clientAccessLogFilter(AuditLogService auditLogService) {
        return new ClientAccessLogFilter(auditLogService);
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
}
