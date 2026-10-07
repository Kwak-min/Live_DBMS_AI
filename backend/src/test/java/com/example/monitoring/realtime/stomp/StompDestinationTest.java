package com.example.monitoring.realtime.stomp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StompDestinationTest {
    @Test
    void acceptsOnlyExactSafePositiveDatabaseTopicsAndSelfErrors() {
        assertThat(StompDestination.parse("/topic/databases/1/metrics"))
                .isEqualTo(new StompDestination(StompDestination.Type.METRICS, 1L));
        assertThat(StompDestination.parse("/topic/metrics/1"))
                .isEqualTo(new StompDestination(StompDestination.Type.METRICS, 1L));
        assertThat(StompDestination.parse("/topic/incidents/1"))
                .isEqualTo(new StompDestination(StompDestination.Type.INCIDENTS, 1L));
        assertThat(StompDestination.parse("/topic/incidents"))
                .isEqualTo(new StompDestination(StompDestination.Type.INCIDENTS, null));
        assertThat(StompDestination.parse("/topic/databases/9007199254740991/incidents"))
                .isEqualTo(new StompDestination(StompDestination.Type.INCIDENTS, 9_007_199_254_740_991L));
        assertThat(StompDestination.parse("/user/queue/errors"))
                .isEqualTo(new StompDestination(StompDestination.Type.ERRORS, null));

        assertThatThrownBy(() -> StompDestination.parse("/topic/databases/0/metrics"))
                .isInstanceOf(StompTransportException.class);
        assertThatThrownBy(() -> StompDestination.parse("/topic/metrics/0"))
                .isInstanceOf(StompTransportException.class);
        assertThatThrownBy(() -> StompDestination.parse("/topic/databases/01/status"))
                .isInstanceOf(StompTransportException.class);
        assertThatThrownBy(() -> StompDestination.parse("/topic/databases/9007199254740992/metrics"))
                .isInstanceOf(StompTransportException.class);
        assertThatThrownBy(() -> StompDestination.parse("/topic/metrics/9007199254740992"))
                .isInstanceOf(StompTransportException.class);
        assertThatThrownBy(() -> StompDestination.parse("/topic/databases/*/metrics"))
                .isInstanceOf(StompTransportException.class);
        assertThatThrownBy(() -> StompDestination.parse("/user/other/queue/errors"))
                .isInstanceOf(StompTransportException.class);
    }
}
