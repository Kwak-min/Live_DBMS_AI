package com.example.monitoring.realtime.status;

import com.example.monitoring.common.stream.InvariantStreamRecordException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RealtimeStatusTransactionTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;

    private StatusBroadcastPort broadcastPort;
    private RealtimeStatusTransaction transaction;
    private StatusStreamEvent event;

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
        jdbc.execute("DROP TABLE IF EXISTS monitoring_states");
        jdbc.execute("""
                CREATE TABLE processed_events (
                    stream varchar(128) NOT NULL,
                    consumer_group varchar(128) NOT NULL,
                    event_id uuid NOT NULL,
                    processed_at timestamptz NOT NULL,
                    PRIMARY KEY (stream, consumer_group, event_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE monitoring_states (
                    database_config_id bigint PRIMARY KEY,
                    config_version bigint NOT NULL,
                    state_version bigint NOT NULL
                )
                """);
        jdbc.update("INSERT INTO monitoring_states VALUES (12, 2, 8)");
        broadcastPort = mock(StatusBroadcastPort.class);
        transaction = new RealtimeStatusTransaction(jdbc, transactionManager, broadcastPort);
        event = new StatusStreamEventParser(new com.fasterxml.jackson.databind.ObjectMapper())
                .parse(StatusStreamEventParserTest.validPayload());
    }

    @Test
    void currentEventCommitsOnceAndDuplicatePublishesOnce() {
        assertThat(transaction.process("stream:statuses", event))
                .isEqualTo(RealtimeStatusTransaction.Outcome.PUBLISHED);
        assertThat(transaction.process("stream:statuses", event))
                .isEqualTo(RealtimeStatusTransaction.Outcome.DUPLICATE);

        verify(broadcastPort, times(1)).publish(any());
        assertThat(processedCount()).isOne();
    }

    @Test
    void brokerFailureRollsBackMarkerForReplay() {
        doThrow(new IllegalStateException("broker unavailable")).when(broadcastPort).publish(any());

        assertThatThrownBy(() -> transaction.process("stream:statuses", event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker unavailable");
        assertThat(processedCount()).isZero();

        reset(broadcastPort);
        assertThat(transaction.process("stream:statuses", event))
                .isEqualTo(RealtimeStatusTransaction.Outcome.PUBLISHED);
    }

    @Test
    void olderVersionCommitsMarkerWithoutBroadcast() {
        StatusStreamEvent older = parse(replace(
                replace(payload(), "\"eventId\":\"20412297-1a93-44bc-9944-a8d8a03765aa\"",
                        "\"eventId\":\"40412297-1a93-44bc-9944-a8d8a03765aa\""),
                "\"stateVersion\":8", "\"stateVersion\":7"));

        assertThat(transaction.process("stream:statuses", older))
                .isEqualTo(RealtimeStatusTransaction.Outcome.SKIPPED);
        verifyNoInteractions(broadcastPort);
        assertThat(processedCount()).isOne();
    }

    @Test
    void futureVersionIsInvariantFailureAndDoesNotCommitMarker() {
        StatusStreamEvent future = parse(replace(payload(), "\"stateVersion\":8", "\"stateVersion\":9"));

        assertThatThrownBy(() -> transaction.process("stream:statuses", future))
                .isInstanceOfSatisfying(InvariantStreamRecordException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo("FUTURE_STATUS_VERSION"));
        assertThat(processedCount()).isZero();
        verifyNoInteractions(broadcastPort);
    }

    @Test
    void enabledWorkerFailsBeforeConsumptionWhenV4StateTableIsAbsent() {
        jdbc.execute("DROP TABLE monitoring_states");

        assertThatThrownBy(transaction::verifyPrerequisite)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("monitoring_states")
                .hasMessageContaining("V4");
    }

    private int processedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class);
    }

    private StatusStreamEvent parse(String json) {
        return new StatusStreamEventParser(new com.fasterxml.jackson.databind.ObjectMapper())
                .parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private String payload() {
        return new String(StatusStreamEventParserTest.validPayload(), StandardCharsets.UTF_8);
    }

    private String replace(String source, String target, String replacement) {
        return source.replace(target, replacement);
    }
}
