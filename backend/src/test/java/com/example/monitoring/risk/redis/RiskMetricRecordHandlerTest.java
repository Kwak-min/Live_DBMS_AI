package com.example.monitoring.risk.redis;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import com.example.monitoring.common.stream.InvariantStreamRecordException;
import com.example.monitoring.common.stream.StreamRecord;
import com.example.monitoring.realtime.event.MetricCollectedPayloadV1;
import com.example.monitoring.realtime.redis.MetricPayloadParser;
import com.example.monitoring.risk.service.RiskMetricTransaction;
import com.example.monitoring.risk.service.RiskStartupCoordinator;
import com.example.monitoring.risk.persistence.RiskPersistenceInvariantException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskMetricRecordHandlerTest {

    @Test
    void unreadableCredentialEventKeepsConnectionFailedInternalErrorAndZeroLatency() {
        RiskMetricTransaction transaction = mock(RiskMetricTransaction.class);
        RiskMetricStreamRecordHandler handler = new RiskMetricStreamRecordHandler(
                new MetricPayloadParser(new ObjectMapper()),
                transaction,
                mock(RiskStartupCoordinator.class));
        org.mockito.ArgumentCaptor<MetricCollectedPayloadV1> payload =
                org.mockito.ArgumentCaptor.forClass(MetricCollectedPayloadV1.class);

        handler.handle(new StreamRecord(
                "stream:metrics", "1-0", unreadableCredentialPayload()));

        verify(transaction).process(eq("stream:metrics"), payload.capture());
        assertThat(payload.getValue().collectionStatus())
                .isEqualTo(MetricCollectedPayloadV1.CollectionStatus.CONNECTION_FAILED);
        assertThat(payload.getValue().errorCode())
                .isEqualTo(MetricCollectedPayloadV1.MetricErrorCode.INTERNAL_ERROR);
        assertThat(payload.getValue().responseTimeMs()).isZero();
    }

    @Test
    void verifiesSharedStartupAndConvertsParserFailureToImmediateDlqSignal() {
        RiskMetricTransaction transaction = mock(RiskMetricTransaction.class);
        RiskStartupCoordinator startup = mock(RiskStartupCoordinator.class);
        RiskMetricStreamRecordHandler handler = new RiskMetricStreamRecordHandler(
                new MetricPayloadParser(new ObjectMapper()), transaction, startup);

        handler.verifyPrerequisite();
        assertThatThrownBy(() -> handler.handle(new StreamRecord(
                "stream:metrics", "1-0", "{bad json".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(InvalidStreamRecordException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo("INVALID_JSON"));

        verify(startup).verifyPrerequisite();
        verify(transaction, never()).process(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deterministicStateInvariantUsesBoundedRetryWhileDependencyFailurePropagates() {
        RiskMetricTransaction invariantTransaction = mock(RiskMetricTransaction.class);
        when(invariantTransaction.process(eq("stream:metrics"), any()))
                .thenThrow(new RiskPersistenceInvariantException("stored state is incoherent"));
        RiskMetricStreamRecordHandler invariantHandler = new RiskMetricStreamRecordHandler(
                new MetricPayloadParser(new ObjectMapper()),
                invariantTransaction,
                mock(RiskStartupCoordinator.class));

        assertThatThrownBy(() -> invariantHandler.handle(new StreamRecord(
                "stream:metrics", "1-0", unreadableCredentialPayload())))
                .isInstanceOfSatisfying(InvariantStreamRecordException.class, exception -> {
                    assertThat(exception.reasonCode()).isEqualTo("RISK_STATE_INVARIANT");
                    assertThat(exception.eventId()).hasToString(
                            "9bfab7ee-221a-4fb4-9507-6fd6f4df7e83");
                });

        RiskMetricTransaction unavailableTransaction = mock(RiskMetricTransaction.class);
        IllegalStateException dependencyFailure = new IllegalStateException("database unavailable");
        when(unavailableTransaction.process(eq("stream:metrics"), any()))
                .thenThrow(dependencyFailure);
        RiskMetricStreamRecordHandler unavailableHandler = new RiskMetricStreamRecordHandler(
                new MetricPayloadParser(new ObjectMapper()),
                unavailableTransaction,
                mock(RiskStartupCoordinator.class));
        assertThatThrownBy(() -> unavailableHandler.handle(new StreamRecord(
                "stream:metrics", "2-0", unreadableCredentialPayload())))
                .isSameAs(dependencyFailure);
    }

    private byte[] unreadableCredentialPayload() {
        return """
                {"schemaVersion":1,
                 "eventId":"9bfab7ee-221a-4fb4-9507-6fd6f4df7e83",
                 "eventType":"MetricCollectedEvent",
                 "publishedAt":"2026-10-02T00:00:00.000Z",
                 "metricId":501,"databaseConfigId":12,"configVersion":1,
                 "databaseName":"production",
                 "timestamp":"2026-10-02T00:00:00.000Z",
                 "collectionAttemptTime":"2026-10-02T00:00:00.000Z",
                 "lastSuccessAt":null,
                 "cpuUsage":null,"memoryUsage":null,
                 "activeConnections":null,"maxConnections":null,
                 "qps":null,"slowQueries":null,"slowQueriesDelta":null,
                 "slowQueriesPerSecond":null,"metricWindowSeconds":null,
                 "threadsRunning":null,"storageBytes":null,"responseTimeMs":0,
                 "collectionStatus":"CONNECTION_FAILED","errorCode":"INTERNAL_ERROR",
                 "errorMessage":"database connection failed",
                 "unavailableMetrics":{
                   "cpuUsage":"COLLECTION_FAILED","memoryUsage":"COLLECTION_FAILED",
                   "activeConnections":"COLLECTION_FAILED","maxConnections":"COLLECTION_FAILED",
                   "qps":"COLLECTION_FAILED","slowQueries":"COLLECTION_FAILED",
                   "slowQueriesDelta":"COLLECTION_FAILED",
                   "slowQueriesPerSecond":"COLLECTION_FAILED",
                   "metricWindowSeconds":"COLLECTION_FAILED",
                   "threadsRunning":"COLLECTION_FAILED","storageBytes":"COLLECTION_FAILED"}}
                """.getBytes(StandardCharsets.UTF_8);
    }
}
