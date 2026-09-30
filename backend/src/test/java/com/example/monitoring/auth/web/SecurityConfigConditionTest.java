package com.example.monitoring.auth.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigConditionTest {

    @Test
    void offlineBootstrapDoesNotCreateServletSecurityConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(SecurityConfig.class).run(context -> {
            assertThat(context).doesNotHaveBean(SecurityConfig.class);
            assertThat(context).doesNotHaveBean("securityFilterChain");
        });
    }
}
