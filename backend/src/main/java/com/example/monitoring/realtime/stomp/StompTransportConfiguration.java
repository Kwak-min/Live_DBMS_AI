package com.example.monitoring.realtime.stomp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
class StompTransportConfiguration implements WebSocketMessageBrokerConfigurer {
    static final int MAX_FRAME_BYTES = 64 * 1024;
    static final int TIME_TO_FIRST_MESSAGE_MILLIS = 6_000;
    static final long[] HEARTBEAT = {10_000L, 10_000L};

    private final StrictOriginHandshakeInterceptor originInterceptor;
    private final StompSecurityChannelInterceptor inboundSecurity;
    private final StompOutboundSecurityInterceptor outboundSecurity;
    private final StompProtocolErrorHandler errorHandler;
    private final StompSessionRegistry sessions;
    private final TaskScheduler scheduler;
    private final ObjectMapper objectMapper;

    StompTransportConfiguration(StrictOriginHandshakeInterceptor originInterceptor,
                                StompSecurityChannelInterceptor inboundSecurity,
                                StompOutboundSecurityInterceptor outboundSecurity,
                                StompProtocolErrorHandler errorHandler,
                                StompSessionRegistry sessions,
                                @Qualifier("stompTaskScheduler") TaskScheduler scheduler,
                                ObjectMapper objectMapper) {
        this.originInterceptor = originInterceptor;
        this.inboundSecurity = inboundSecurity;
        this.outboundSecurity = outboundSecurity;
        this.errorHandler = errorHandler;
        this.sessions = sessions;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(scheduler)
                .setHeartbeatValue(HEARTBEAT.clone());
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(errorHandler);
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/ws")
                .addInterceptors(originInterceptor)
                .setAllowedOrigins(originInterceptor.publicOrigin());
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(MAX_FRAME_BYTES)
                .setSendBufferSizeLimit(MAX_FRAME_BYTES)
                .setSendTimeLimit(10_000)
                .setTimeToFirstMessage(TIME_TO_FIRST_MESSAGE_MILLIS)
                .addDecoratorFactory(this::decorate);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(2).maxPoolSize(8).queueCapacity(1_000);
        registration.interceptors(inboundSecurity);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(2).maxPoolSize(8).queueCapacity(1_000);
        registration.interceptors(outboundSecurity);
    }

    @Override
    public boolean configureMessageConverters(List<MessageConverter> messageConverters) {
        MappingJackson2MessageConverter json = new MappingJackson2MessageConverter();
        json.setObjectMapper(objectMapper);
        messageConverters.add(json);
        return false;
    }

    WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.setTextMessageSizeLimit(MAX_FRAME_BYTES);
                session.setBinaryMessageSizeLimit(MAX_FRAME_BYTES);
                StompSerializedWebSocketSession serialized = new StompSerializedWebSocketSession(session);
                sessions.opened(serialized);
                try {
                    super.afterConnectionEstablished(serialized);
                } catch (Exception failure) {
                    sessions.disconnected(session.getId());
                    throw failure;
                }
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
                sessions.disconnected(session.getId());
                super.handleTransportError(session, exception);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                try {
                    super.afterConnectionClosed(session, closeStatus);
                } finally {
                    sessions.disconnected(session.getId());
                }
            }
        };
    }
}
