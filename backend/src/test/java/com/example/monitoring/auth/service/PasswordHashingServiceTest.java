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
    void rejectsPasswordShorterThanEightUnicodeCodePoints() {
        assertThatThrownBy(() -> passwordHashingService.hash("seven77"))
                .isInstanceOf(PasswordHashingService.PasswordPolicyException.class);
        assertThatThrownBy(() -> passwordHashingService.hash("😀".repeat(7)))
                .isInstanceOf(PasswordHashingService.PasswordPolicyException.class);
    }

    @Test
    void acceptsEightAndRejectsMoreThan128UnicodeCodePoints() {
        passwordHashingService.validate("eight888");
        passwordHashingService.validate("😀".repeat(8));
        passwordHashingService.validate("a".repeat(128));
        assertThatThrownBy(() -> passwordHashingService.validate("a".repeat(129)))
                .isInstanceOf(PasswordHashingService.PasswordPolicyException.class);
    }
}
