package com.example.monitoring.auth.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountTest {

    @Test
    void authVersionChangesOnlyWhenRoleOrEnabledValueChanges() {
        UserAccount user = UserAccount.builder()
                .email("user@example.com")
                .displayName("User")
                .passwordHash("hash")
                .build();

        user.changeRole(UserRole.USER);
        user.changeEnabled(true);
        assertThat(user.getAuthVersion()).isEqualTo(1L);

        user.changeRole(UserRole.ADMIN);
        user.changeEnabled(false);
        assertThat(user.getAuthVersion()).isEqualTo(3L);
    }
}
