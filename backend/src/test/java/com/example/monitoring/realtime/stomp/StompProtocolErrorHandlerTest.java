package com.example.monitoring.realtime.stomp;

import com.example.monitoring.common.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StompProtocolErrorHandlerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StompProtocolErrorHandler handler = new StompProtocolErrorHandler(objectMapper);

    @Test
    void emitsOnlyThePublicErrorShapeAndSanitizesUnexpectedMessages() throws Exception {
        Message<byte[]> result = handler.handleClientMessageProcessingError(
                null, new IllegalStateException("password=do-not-echo"));

        JsonNode body = objectMapper.readTree(result.getPayload());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("message").asText()).isEqualTo("STOMP frame is invalid.");
        assertThatCodeIsUuid(body.path("requestId").asText());
        assertThat(new String(result.getPayload())).doesNotContain("do-not-echo");
        assertThat(StompHeaderAccessor.getCommand(result.getHeaders())).isEqualTo(StompCommand.ERROR);
    }

    @Test
    void preservesTheAllowedExpiredTokenCodeWithoutItsExceptionMessage() throws Exception {
        Message<byte[]> result = handler.handleClientMessageProcessingError(null,
                new ApiException(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_EXPIRED", "sensitive detail"));

        JsonNode body = objectMapper.readTree(result.getPayload());
        assertThat(body.path("code").asText()).isEqualTo("ACCESS_TOKEN_EXPIRED");
        assertThat(body.path("message").asText()).isEqualTo("Access token has expired.");
        assertThat(new String(result.getPayload())).doesNotContain("sensitive detail");
    }

    private void assertThatCodeIsUuid(String value) {
        assertThat(UUID.fromString(value)).isNotNull();
    }
}
