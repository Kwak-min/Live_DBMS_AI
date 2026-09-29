package com.example.monitoring.realtime.redis;

import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.realtime.event.MetricBroadcastPort;
import com.example.monitoring.realtime.event.MetricCollectedPayloadV1;
import com.example.monitoring.realtime.event.MetricPayloadParserTest;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RealtimeMetricTransactionTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;

    private MetricBroadcastPort broadcastPort;
    private TargetProvider targetProvider;
    private RealtimeMetricTransaction transaction;
    private MetricCollectedPayloadV1 payload;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.start();
        DataSource dataSource = postgres.getPostgresDatabase();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DROP TABLE IF EXISTS processed_events");
        jdbc.execute("""
                CREATE TABLE processed_events (
                    stream varchar(100) NOT NULL,
                    consumer_group varchar(100) NOT NULL,
                    event_id uuid NOT NULL,
                    processed_at timestamptz NOT NULL,
                    PRIMARY KEY (stream, consumer_group, event_id)
                )
                """);
        broadcastPort = mock(MetricBroadcastPort.class);
        targetProvider = mock(TargetProvider.class);
        when(targetProvider.getMetadata(12L))
                .thenReturn(Optional.of(new TargetMetadata(12, 2, "db", "127.0.0.1", 3306, null, true)));
        transaction = new RealtimeMetricTransaction(jdbc, transactionManager, broadcastPort, targetProvider);
        payload = new MetricPayloadParser(new com.fasterxml.jackson.databind.ObjectMapper())
                .parse(MetricPayloadParserTest.validPayload().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void duplicateEventCommitsOneRowAndPublishesOnlyOnce() {
        assertThat(transaction.process("stream:metrics", payload))
                .isEqualTo(RealtimeMetricTransaction.Outcome.PUBLISHED);
        assertThat(transaction.process("stream:metrics", payload))
                .isEqualTo(RealtimeMetricTransaction.Outcome.DUPLICATE);

        verify(broadcastPort, times(1)).publish(
                org.mockito.ArgumentMatchers.argThat(message -> message.eventId().equals(payload.eventId())));
        assertThat(rowCount()).isOne();
    }

    @Test
    void brokerFailureRollsBackMarkerSoReplayCanPublish() {
        doThrow(new IllegalStateException("broker unavailable")).when(broadcastPort)
                .publish(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> transaction.process("stream:metrics", payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker unavailable");
        assertThat(rowCount()).isZero();

        reset(broadcastPort);
        assertThat(transaction.process("stream:metrics", payload))
                .isEqualTo(RealtimeMetricTransaction.Outcome.PUBLISHED);
        assertThat(rowCount()).isOne();
    }

    @Test
    void oldDisabledAndDeletedTargetsAreMarkedWithoutBroadcast() {
        MetricCollectedPayloadV1 oldPayload = parsedPayload(
                "\"configVersion\":2",
                "\"configVersion\":1");
        assertThat(transaction.process("stream:metrics", oldPayload))
                .isEqualTo(RealtimeMetricTransaction.Outcome.SKIPPED);

        when(targetProvider.getMetadata(12L))
                .thenReturn(Optional.of(new TargetMetadata(12, 3, "db", "127.0.0.1", 3306, null, false)));
        MetricCollectedPayloadV1 disabledPayload = parsedPayload(
                "7f6a1c08-9ef4-45bd-bc2e-5b2d1c1a2f11",
                "4c9209e9-0a19-4857-a392-6f3679870e7e");
        assertThat(transaction.process("stream:metrics", disabledPayload))
                .isEqualTo(RealtimeMetricTransaction.Outcome.SKIPPED);

        when(targetProvider.getMetadata(12L)).thenReturn(Optional.empty());
        MetricCollectedPayloadV1 deletedPayload = parsedPayload(
                "7f6a1c08-9ef4-45bd-bc2e-5b2d1c1a2f11",
                "a7ddb67a-a0fc-4f20-b5db-b1918b363144");
        assertThat(transaction.process("stream:metrics", deletedPayload))
                .isEqualTo(RealtimeMetricTransaction.Outcome.SKIPPED);

        verifyNoInteractions(broadcastPort);
        assertThat(rowCount()).isEqualTo(3);
    }

    @Test
    void futureConfigVersionIsRetryableDomainInvariantAndDoesNotCommitMarker() {
        when(targetProvider.getMetadata(12L))
                .thenReturn(Optional.of(new TargetMetadata(12, 1, "db", "127.0.0.1", 3306, null, true)));

        assertThatThrownBy(() -> transaction.process("stream:metrics", payload))
                .isInstanceOf(FutureConfigVersionException.class);
        assertThat(rowCount()).isZero();
        verifyNoInteractions(broadcastPort);
    }

    @Test
    void enabledConsumerFailsClearlyWhenProcessedEventsIsAbsent() {
        jdbc.execute("DROP TABLE processed_events");

        assertThatThrownBy(transaction::verifyPrerequisite)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("processed_events")
                .hasMessageContaining("A V3");
    }

    private int rowCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class);
        return count == null ? 0 : count;
    }

    private MetricCollectedPayloadV1 parsedPayload(String target, String replacement) {
        String json = MetricPayloadParserTest.validPayload().replace(target, replacement);
        return new MetricPayloadParser(new com.fasterxml.jackson.databind.ObjectMapper())
                .parse(json.getBytes(StandardCharsets.UTF_8));
    }
}
