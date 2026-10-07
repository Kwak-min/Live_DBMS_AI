package com.example.monitoring.realtime.stomp;

import com.example.monitoring.auth.service.StompPrincipal;
import com.example.monitoring.database.port.TargetProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
final class StompSecurityChannelInterceptor implements ChannelInterceptor {
    private static final int MAX_SUBSCRIPTION_ID_LENGTH = 128;
    private final StompSessionRegistry sessions;
    private final TargetProvider targetProvider;
    private final StompSubscriptionErrorSender errorSender;

    StompSecurityChannelInterceptor(StompSessionRegistry sessions, TargetProvider targetProvider,
                                    StompSubscriptionErrorSender errorSender) {
        this.sessions = sessions;
        this.targetProvider = targetProvider;
        this.errorSender = errorSender;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        try {
            return switch (accessor.getCommand()) {
                case CONNECT, STOMP -> connect(message, accessor);
                case SUBSCRIBE -> subscribe(message, accessor);
                case UNSUBSCRIBE -> unsubscribe(message, accessor);
                case DISCONNECT -> disconnect(message, accessor);
                case SEND -> send(message, accessor);
                default -> throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
            };
        } catch (StompTransportException failure) {
            String sessionId = accessor.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                throw failure;
            }
            sessions.closeWithError(sessionId, failure.failure());
            return null;
        }
    }

    private Message<?> connect(Message<?> message, StompHeaderAccessor accessor) {
        String sessionId = requiredSessionId(accessor);
        String authorization = resolveAuthorization(accessor);
        rejectHeader(accessor, "login");
        rejectHeader(accessor, "passcode");
        if (!"1.2".equals(singleHeader(accessor, "accept-version"))
                || !"10000,10000".equals(singleHeader(accessor, "heart-beat"))) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
        if (authorization == null || authorization.isBlank()) {
            throw new StompTransportException(StompFailure.of("AUTH_REQUIRED"));
        }
        StompPrincipal principal = sessions.connected(sessionId, authorization);
        accessor.setUser(principal);
        return message;
    }

    private Message<?> send(Message<?> message, StompHeaderAccessor accessor) {
        String sessionId = requiredSessionId(accessor);
        sessions.requireCurrent(sessionId);
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/app")) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
        return message;
    }

    private Message<?> subscribe(Message<?> message, StompHeaderAccessor accessor) {
        String sessionId = requiredSessionId(accessor);
        String subscriptionId = null;
        String destinationValue = null;
        try {
            sessions.requireCurrent(sessionId);
            subscriptionId = safeSubscriptionId(singleHeader(accessor, "id"));
            if (!"auto".equals(singleHeader(accessor, "ack"))) {
                throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
            }
            destinationValue = singleHeader(accessor, "destination");
            StompDestination destination = StompDestination.parse(destinationValue);
            if (destination.databaseConfigId() != null) {
                requireTarget(destination.databaseConfigId());
            }
            sessions.addSubscription(sessionId, subscriptionId, destination);
            return message;
        } catch (StompTransportException failure) {
            if (isAuthenticationFailure(failure.failure())) {
                throw failure;
            }
            if (!sessions.hasErrorsSubscription(sessionId)) {
                throw failure;
            }
            Long databaseConfigId = parseDatabaseIdWithoutFailure(destinationValue);
            errorSender.send(sessionId, databaseConfigId, subscriptionId, failure.failure());
            return null;
        }
    }

    private Message<?> unsubscribe(Message<?> message, StompHeaderAccessor accessor) {
        String sessionId = requiredSessionId(accessor);
        sessions.requireCurrent(sessionId);
        sessions.removeSubscription(sessionId, safeSubscriptionId(singleHeader(accessor, "id")));
        return message;
    }

    private Message<?> disconnect(Message<?> message, StompHeaderAccessor accessor) {
        if (accessor.getSessionId() != null) {
            sessions.disconnected(accessor.getSessionId());
        }
        return message;
    }

    private void requireTarget(long databaseConfigId) {
        try {
            if (targetProvider.getMetadata(databaseConfigId).isEmpty()) {
                throw new StompTransportException(StompFailure.of("DATABASE_NOT_FOUND"));
            }
        } catch (StompTransportException failure) {
            throw failure;
        } catch (RuntimeException dependencyFailure) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
    }

    private String requiredSessionId(StompHeaderAccessor accessor) {
        if (accessor.getSessionId() == null || accessor.getSessionId().isBlank()) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
        return accessor.getSessionId();
    }

    private String safeSubscriptionId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_SUBSCRIPTION_ID_LENGTH) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
        return value;
    }

    private String singleHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
        return values.get(0);
    }

    private void rejectHeader(StompHeaderAccessor accessor, String name) {
        if (accessor.getNativeHeader(name) != null) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
    }

    private String resolveAuthorization(StompHeaderAccessor accessor) {
        String authHeader = removeSingleHeader(accessor, "Authorization");
        if (authHeader != null && !authHeader.isBlank()) {
            return authHeader.startsWith("Bearer ") ? authHeader : "Bearer " + authHeader;
        }

        String tokenHeader = removeSingleHeader(accessor, "token");
        if (tokenHeader != null && !tokenHeader.isBlank()) {
            return tokenHeader.startsWith("Bearer ") ? tokenHeader : "Bearer " + tokenHeader;
        }

        String accessTokenHeader = removeSingleHeader(accessor, "access_token");
        if (accessTokenHeader != null && !accessTokenHeader.isBlank()) {
            return accessTokenHeader.startsWith("Bearer ") ? accessTokenHeader : "Bearer " + accessTokenHeader;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            Object tokenAttr = sessionAttributes.get("token");
            if (tokenAttr == null) {
                tokenAttr = sessionAttributes.get("access_token");
            }
            if (tokenAttr == null) {
                tokenAttr = sessionAttributes.get("authorization");
            }
            if (tokenAttr instanceof String tokenStr && !tokenStr.isBlank()) {
                return tokenStr.startsWith("Bearer ") ? tokenStr : "Bearer " + tokenStr;
            }
        }

        return null;
    }

    private String removeSingleHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        accessor.removeNativeHeader(name);
        if (values.size() != 1) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
        return values.get(0);
    }

    private boolean isAuthenticationFailure(StompFailure failure) {
        return switch (failure.code()) {
            case "AUTH_REQUIRED", "ACCESS_TOKEN_EXPIRED", "INVALID_TOKEN", "SESSION_REVOKED" -> true;
            default -> false;
        };
    }

    private Long parseDatabaseIdWithoutFailure(String destination) {
        try {
            return StompDestination.parse(destination).databaseConfigId();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
