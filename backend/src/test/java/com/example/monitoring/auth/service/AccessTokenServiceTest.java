package com.example.monitoring.auth.service;

import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.common.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenServiceTest {

    private AccessTokenService service;

    @BeforeEach
    void setUp() {
        String secret = Base64.getEncoder().encodeToString(new byte[32]);
        JwtKeySet keySet = new JwtKeySet(new ObjectMapper(), "{\"test-key\":\"" + secret + "\"}", "test-key");
        keySet.validate();
        service = new AccessTokenService(keySet);
    }

    @Test
    void issuesAndVerifiesEveryRequiredClaim() {
        UUID sessionId = UUID.randomUUID();
        UserAccount user = UserAccount.builder()
                .id(12L)
                .email("user@example.com")
                .displayName("User")
                .passwordHash("hash")
                .role(UserRole.ADMIN)
                .authVersion(3L)
                .build();

        AccessTokenService.IssuedAccessToken issued = service.issue(user, sessionId);
        AccessTokenService.VerifiedAccessToken verified = service.verify(issued.value());

        assertThat(verified.userId()).isEqualTo(12L);
        assertThat(verified.role()).isEqualTo("ADMIN");
        assertThat(verified.sessionId()).isEqualTo(sessionId);
        assertThat(verified.authVersion()).isEqualTo(3L);
        assertThat(verified.expiresAt()).isEqualTo(issued.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void rejectsTamperedSignature() {
        UserAccount user = UserAccount.builder()
                .id(1L).email("user@example.com").displayName("User")
                .passwordHash("hash").role(UserRole.USER).build();
        String token = service.issue(user, UUID.randomUUID()).value();
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("INVALID_TOKEN");
    }
}
