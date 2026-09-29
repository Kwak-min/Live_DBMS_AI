package com.example.monitoring.realtime.stomp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
final class StompProtocolErrorHandler extends StompSubProtocolErrorHandler {
    private final ObjectMapper objectMapper;

    StompProtocolErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable exception) {
        StompFailure failure = StompFailure.from(exception);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new ErrorBody(
                    failure.code(), failure.message(), failure.requestId()));
        } catch (JsonProcessingException serializationFailure) {
            body = ("{\"code\":\"VALIDATION_ERROR\",\"message\":\"STOMP frame is invalid.\","
                    + "\"requestId\":\"" + failure.requestId() + "\"}").getBytes();
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        accessor.setContentLength(body.length);
        accessor.setMessage(failure.message());
        return MessageBuilder.createMessage(body, accessor.getMessageHeaders());
    }

    private record ErrorBody(String code, String message, String requestId) { }
}
