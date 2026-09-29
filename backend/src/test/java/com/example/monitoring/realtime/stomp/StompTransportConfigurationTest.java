package com.example.monitoring.realtime.stomp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.SimpleBrokerRegistration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompTransportConfigurationTest {
    private StrictOriginHandshakeInterceptor origin;
    private StompProtocolErrorHandler errorHandler;
    private TaskScheduler scheduler;
    private ObjectMapper objectMapper;
    private StompSessionRegistry sessions;
    private StompTransportConfiguration configuration;

    @BeforeEach
    void setUp() {
        origin = new StrictOriginHandshakeInterceptor("https://monitor.example");
        errorHandler = new StompProtocolErrorHandler(new ObjectMapper());
        scheduler = mock(TaskScheduler.class);
        objectMapper = new ObjectMapper();
        sessions = mock(StompSessionRegistry.class);
        configuration = new StompTransportConfiguration(
                origin,
                mock(StompSecurityChannelInterceptor.class),
                mock(StompOutboundSecurityInterceptor.class),
                errorHandler,
                sessions,
                scheduler,
                objectMapper);
    }

    @Test
    void configuresOrderedBrokerWithTenSecondHeartbeats() {
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        SimpleBrokerRegistration simpleBroker = mock(SimpleBrokerRegistration.class);
        when(registry.enableSimpleBroker("/topic", "/queue")).thenReturn(simpleBroker);
        when(simpleBroker.setTaskScheduler(scheduler)).thenReturn(simpleBroker);
        when(simpleBroker.setHeartbeatValue(any(long[].class))).thenReturn(simpleBroker);

        configuration.configureMessageBroker(registry);

        verify(simpleBroker).setHeartbeatValue(aryEq(new long[]{10_000L, 10_000L}));
        verify(registry).setUserDestinationPrefix("/user");
        verify(registry).setPreservePublishOrder(true);
    }

    @Test
    void configuresExactEndpointOriginAndReceiveOrdering() {
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpoint = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint("/ws")).thenReturn(endpoint);
        when(endpoint.addInterceptors(origin)).thenReturn(endpoint);

        configuration.registerStompEndpoints(registry);

        verify(registry).setErrorHandler(errorHandler);
        verify(registry).setPreserveReceiveOrder(true);
        verify(endpoint).setAllowedOrigins("https://monitor.example");
    }

    @Test
    void configuresFrameAndUnauthenticatedConnectionBounds() {
        WebSocketTransportRegistration transport = mock(WebSocketTransportRegistration.class);
        when(transport.setMessageSizeLimit(any(Integer.class))).thenReturn(transport);
        when(transport.setSendBufferSizeLimit(any(Integer.class))).thenReturn(transport);
        when(transport.setSendTimeLimit(any(Integer.class))).thenReturn(transport);
        when(transport.setTimeToFirstMessage(any(Integer.class))).thenReturn(transport);
        when(transport.addDecoratorFactory(any())).thenReturn(transport);

        configuration.configureWebSocketTransport(transport);

        verify(transport).setMessageSizeLimit(64 * 1024);
        verify(transport).setSendBufferSizeLimit(64 * 1024);
        verify(transport).setTimeToFirstMessage(6_000);
    }

    @Test
    void givesTheRegistryAndBrokerTheSameSerializedSession() throws Exception {
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketSession raw = StompTestSupport.rawSocket("shared-session");
        WebSocketHandler decorated = configuration.decorate(delegate);

        decorated.afterConnectionEstablished(raw);

        verify(raw).setTextMessageSizeLimit(StompTransportConfiguration.MAX_FRAME_BYTES);
        verify(raw).setBinaryMessageSizeLimit(StompTransportConfiguration.MAX_FRAME_BYTES);
        org.mockito.ArgumentCaptor<StompSerializedWebSocketSession> captured =
                org.mockito.ArgumentCaptor.forClass(StompSerializedWebSocketSession.class);
        verify(sessions).opened(captured.capture());
        verify(delegate).afterConnectionEstablished(same(captured.getValue()));
        assertThat(captured.getValue().getDelegate()).isSameAs(raw);
    }

    @Test
    void usesTheBootManagedObjectMapperForBrokerJson() {
        List<MessageConverter> converters = new ArrayList<>();

        boolean suppressDefaults = configuration.configureMessageConverters(converters);

        assertThat(suppressDefaults).isFalse();
        assertThat(converters).singleElement()
                .isInstanceOfSatisfying(MappingJackson2MessageConverter.class,
                        converter -> assertThat(converter.getObjectMapper()).isSameAs(objectMapper));
    }
}
