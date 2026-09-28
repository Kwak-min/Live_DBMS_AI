package com.example.monitoring.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordHashingServiceTest {

    private final PasswordHashingService passwordHashingService = new PasswordHashingService();

    @Test
    void hashesAndVerifiesAValidPasswordWithoutTrimmingIt() {
        String rawPassword = "  twelve chars  ";

        String hash = passwordHashingService.hash(rawPassword);

        assertThat(hash).startsWith("$argon2id$");
        assertThat(passwordHashingService.matches(rawPassword, hash)).isTrue();
        assertThat(passwordHashingService.matches(rawPassword.trim(), hash)).isFalse();
    }

    @Test
    void rejectsPasswordShorterThanTwelveUnicodeCodePoints() {
        assertThatThrownBy(() -> passwordHashingService.hash("short-pass"))
                .isInstanceOf(PasswordHashingService.PasswordPolicyException.class);
    }
}
