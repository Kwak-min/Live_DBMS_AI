package com.example.monitoring.realtime.incident;

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
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RealtimeIncidentTransactionTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;

    private IncidentBroadcastPort broadcastPort;
    private RealtimeIncidentTransaction transaction;
    private IncidentStreamEvent event;

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
        jdbc.execute("DROP TABLE IF EXISTS incidents");
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
                CREATE TABLE incidents (
                    incident_id uuid PRIMARY KEY,
                    database_config_id bigint NOT NULL,
                    incident_version bigint NOT NULL,
                    last_observed_at timestamptz NOT NULL
                )
                """);
        jdbc.update(
                "INSERT INTO incidents VALUES (?::uuid, 12, 2, ?)",
                "984b0ae3-37e5-46b1-a709-87bfb95b9a1a",
                Timestamp.from(Instant.parse("2026-09-28T03:00:20.000Z")));
        broadcastPort = mock(IncidentBroadcastPort.class);
        transaction = new RealtimeIncidentTransaction(jdbc, transactionManager, broadcastPort);
        event = new IncidentStreamEventParser(new com.fasterxml.jackson.databind.ObjectMapper())
                .parse(IncidentStreamEventParserTest.validUpdatedPayload());
    }

    @Test
    void currentEventCommitsOnceEvenWhenRestHasNewerSameVersionObservation() {
        assertThat(event.incident().lastObservedAt())
                .isEqualTo(Instant.parse("2026-09-28T03:00:15.000Z"));

        assertThat(transaction.process("stream:incidents", event))
                .isEqualTo(RealtimeIncidentTransaction.Outcome.PUBLISHED);
        assertThat(transaction.process("stream:incidents", event))
                .isEqualTo(RealtimeIncidentTransaction.Outcome.DUPLICATE);

        verify(broadcastPort, times(1)).publish(any());
        assertThat(processedCount()).isOne();
    }

    @Test
    void brokerFailureRollsBackMarkerForReplay() {
        doThrow(new IllegalStateException("broker unavailable")).when(broadcastPort).publish(any());

        assertThatThrownBy(() -> transaction.process("stream:incidents", event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker unavailable");
        assertThat(processedCount()).isZero();

        reset(broadcastPort);
        assertThat(transaction.process("stream:incidents", event))
                .isEqualTo(RealtimeIncidentTransaction.Outcome.PUBLISHED);
    }

    @Test
    void olderVersionCommitsMarkerWithoutBroadcast() {
        jdbc.update("UPDATE incidents SET incident_version = 3 WHERE incident_id = ?::uuid",
                "984b0ae3-37e5-46b1-a709-87bfb95b9a1a");

        assertThat(transaction.process("stream:incidents", event))
                .isEqualTo(RealtimeIncidentTransaction.Outcome.SKIPPED);
        verifyNoInteractions(broadcastPort);
        assertThat(processedCount()).isOne();
    }

    @Test
    void futureVersionIsInvariantFailureAndDoesNotCommitMarker() {
        jdbc.update("UPDATE incidents SET incident_version = 1 WHERE incident_id = ?::uuid",
                "984b0ae3-37e5-46b1-a709-87bfb95b9a1a");

        assertThatThrownBy(() -> transaction.process("stream:incidents", event))
                .isInstanceOfSatisfying(InvariantStreamRecordException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo("FUTURE_INCIDENT_VERSION"));
        assertThat(processedCount()).isZero();
        verifyNoInteractions(broadcastPort);
    }

    @Test
    void enabledWorkerFailsBeforeConsumptionWhenV4IncidentTableIsAbsent() {
        jdbc.execute("DROP TABLE incidents");

        assertThatThrownBy(transaction::verifyPrerequisite)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incidents")
                .hasMessageContaining("V4");
    }

    private int processedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class);
    }
}
