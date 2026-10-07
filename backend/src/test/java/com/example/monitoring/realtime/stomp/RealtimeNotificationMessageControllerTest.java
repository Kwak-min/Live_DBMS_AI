package com.example.monitoring.realtime.stomp;

import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RealtimeNotificationMessageControllerTest {

    private final RealtimeNotificationMessageController controller = new RealtimeNotificationMessageController();

    @Test
    void pingWithAuthenticatedPrincipalReturnsPongWithUserName() {
        Principal principal = () -> "user@example.com";
        Map<String, Object> result = controller.ping(principal);

        assertThat(result).isNotNull();
        assertThat(result.get("status")).isEqualTo("pong");
        assertThat(result.get("user")).isEqualTo("user@example.com");
        assertThat(result.get("timestamp")).isInstanceOf(Long.class);
    }

    @Test
    void pingWithNullPrincipalReturnsPongWithAnonymous() {
        Map<String, Object> result = controller.ping(null);

        assertThat(result).isNotNull();
        assertThat(result.get("status")).isEqualTo("pong");
        assertThat(result.get("user")).isEqualTo("anonymous");
        assertThat(result.get("timestamp")).isInstanceOf(Long.class);
    }

    @Test
    void acknowledgeExecutesWithoutError() {
        Principal principal = () -> "test-user";
        assertDoesNotThrow(() -> controller.acknowledge(Map.of("messageId", "12345"), principal));
    }
}
