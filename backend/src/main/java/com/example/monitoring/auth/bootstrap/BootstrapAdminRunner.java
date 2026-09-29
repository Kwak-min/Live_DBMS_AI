package com.example.monitoring.auth.bootstrap;

import com.example.monitoring.auth.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Deliberately available only under the bootstrap-admin profile. It never runs in
 * normal server profiles and refuses to create a second bootstrap administrator.
 */
@Slf4j
@Component
@Profile("bootstrap-admin")
@RequiredArgsConstructor
public class BootstrapAdminRunner implements ApplicationRunner {

    private final UserAccountService userAccountService;
    private final Environment environment;
    private final ConfigurableApplicationContext applicationContext;

    @Override
    public void run(ApplicationArguments args) {
        String email = required("BOOTSTRAP_ADMIN_EMAIL");
        String password = required("BOOTSTRAP_ADMIN_PASSWORD");
        String displayName = required("BOOTSTRAP_ADMIN_DISPLAY_NAME");

        userAccountService.createBootstrapAdmin(email, displayName, password);
        log.info("Bootstrap administrator created successfully; closing bootstrap-only process.");
        SpringApplication.exit(applicationContext, () -> 0);
    }

    private String required(String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required for bootstrap-admin profile.");
        }
        return value;
    }
}
