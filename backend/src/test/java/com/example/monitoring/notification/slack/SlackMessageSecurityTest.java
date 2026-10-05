package com.example.monitoring.notification.slack;

import com.example.monitoring.notification.transport.NotificationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackMessageSecurityTest {
    @Test
    void ordinarySecurityWordsInApprovedDisplayTextAreAllowed() {
        SlackMessage message = message("token-service", "secret rotation policy");

        assertThat(message.displayName()).isEqualTo("token-service");
        assertThat(message.ruleName()).isEqualTo("secret rotation policy");
    }

    @Test
    void rejectsCredentialAddressAccountAndSqlShapesWithoutEchoingThem() {
        String[] forbidden = {
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature",
                "password=do-not-copy",
                "jdbc:mariadb://db.example:3306/monitoring",
                "operator@example.com",
                "10.23.4.5",
                "SELECT password FROM users"
        };

        for (String value : forbidden) {
            assertThatThrownBy(() -> message("primary", value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Notification content contains a forbidden value.")
                    .hasMessageNotContaining(value);
        }
    }

    private SlackMessage message(String displayName, String ruleName) {
        return new SlackMessage("CRITICAL", displayName, ruleName, NotificationType.INCIDENT_OPENED,
                Instant.parse("2026-10-03T01:02:03Z"), UUID.randomUUID());
    }
}
