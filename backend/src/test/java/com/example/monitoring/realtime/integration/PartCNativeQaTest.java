package com.example.monitoring.realtime.integration;

import com.example.monitoring.MonitoringApplication;
import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.repository.UserAccountRepository;
import com.example.monitoring.auth.service.AccessTokenService;
import com.example.monitoring.auth.service.PasswordHashingService;
import com.example.monitoring.database.security.EncryptedValue;
import com.example.monitoring.integration.PartCNativeQaEvidence;
import com.example.monitoring.notification.delivery.NotificationDeliveryTransaction;
import com.example.monitoring.notification.delivery.NotificationDeliveryWorker;
import com.example.monitoring.notification.delivery.PostgresDeliveryLease;
import com.example.monitoring.notification.delivery.SlackAttemptPacer;
import com.example.monitoring.notification.integration.LocalTlsProviderFixture;
import com.example.monitoring.notification.security.NotificationSecretCodec;
import com.example.monitoring.notification.security.PublicAddressPolicy;
import com.example.monitoring.notification.security.PushEndpointPolicy;
import com.example.monitoring.notification.security.SlackWebhookPolicy;
import com.example.monitoring.notification.slack.SlackIncidentLinkFactory;
import com.example.monitoring.notification.slack.SlackPayloadRenderer;
import com.example.monitoring.notification.slack.SlackSender;
import com.example.monitoring.notification.transport.ApachePinnedHttpExecutor;
import com.example.monitoring.notification.transport.DeliveryOutcomeKind;
import com.example.monitoring.notification.transport.HostResolutionExecutor;
import com.example.monitoring.notification.transport.HostResolver;
import com.example.monitoring.notification.transport.PinnedHttpsTransport;
import com.example.monitoring.notification.webpush.WebPushSender;
import com.example.monitoring.retention.PartCRetentionService;
import com.example.monitoring.risk.contract.MonitoringContracts;
import com.example.monitoring.risk.heartbeat.HeartbeatHealthRegistry;
import com.example.monitoring.realtime.event.MetricCollectedPayloadV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "PART_C_NATIVE_QA_ENABLED", matches = "true")
@SpringBootTest(
        classes = MonitoringApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.address=127.0.0.1",
                "server.port=${PART_C_NATIVE_QA_APP_PORT:18103}",
                "app.collector.enabled=false",
                "app.metrics.retention-cleanup-enabled=false",
                "app.part-b.retention-cleanup-enabled=false",
                "app.outbox.publisher-enabled=true",
                "app.outbox.publish-interval-ms=100",
                "app.outbox.retention-cleanup-enabled=false",
                "monitoring.risk.enabled=true",
                "monitoring.realtime.enabled=true",
                "monitoring.realtime.reclaim-min-idle=100ms",
                "monitoring.realtime.reclaim-interval=100ms",
                "monitoring.notifications.enabled=true",
                "monitoring.notifications.delivery-interval-ms=60000"
        })
@Import(PartCNativeQaTest.ObserverConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartCNativeQaTest {

    private static final long PIPELINE_TARGET_ID = 201L;
    private static final long CREDENTIAL_TARGET_ID = 202L;
    private static final long RESTART_TARGET_ID = 203L;
    private static final long STALE_TARGET_ID = 204L;
    private static final long ROLLBACK_TARGET_ID = 205L;
    private static final UUID STARTUP_PROBE_EVENT_ID =
            UUID.fromString("19e9e59e-603d-4a6f-8734-e4212258a2a2");
    private static final UUID RETRY_INCIDENT_ID =
            UUID.fromString("d568792c-b68c-4a74-b7ec-445716731c57");
    private static final UUID LEASE_INCIDENT_ID =
            UUID.fromString("96dd8b27-3b7e-4d0d-8437-cf2805493f92");
    private static final String METRIC_STREAM = "stream:metrics";
    private static final String HEARTBEAT_STREAM = "stream:collector-heartbeats";
    private static final String DEAD_LETTER_STREAM = "stream:dead-letter";
    private static final String RISK_GROUP = "cg:risk";
    private static final String ORIGIN = "http://localhost:5173";
    private static final String SLACK_URL = "https://hooks.slack.com/services/T000/B000/native";
    private static final String EMAIL = "part-c-native@example.test";
    private static final String PASSWORD = "part-c-native-fixture-password";
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(20);

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LettuceConnectionFactory redisConnectionFactory;

    @Autowired
    private UserAccountRepository userAccounts;

    @Autowired
    private PasswordHashingService passwordHashingService;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private PartCRetentionService retentionService;

    @Autowired
    private HeartbeatHealthRegistry heartbeatHealthRegistry;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Clock clock;

    @Autowired
    private NotificationDeliveryTransaction deliveryTransaction;

    @Autowired
    private NotificationSecretCodec notificationSecrets;

    @Autowired
    private SlackWebhookPolicy slackWebhookPolicy;

    @Autowired
    private SubscriptionObserver subscriptionObserver;

    @MockBean
    private WebPushSender webPushSender;

    @MockBean
    private SlackSender slackSender;

    @MockBean
    private NotificationDeliveryWorker backgroundDeliveryWorker;

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private StatefulRedisConnection<String, String> redisConnection;
    private RedisCommands<String, String> redis;
    private Stage3StompClient stompClient;

    @BeforeEach
    void openClients() {
        stopWorker("notificationDeliveryLoop");
        RedisClient client = (RedisClient) redisConnectionFactory.getRequiredNativeClient();
        redisConnection = client.connect(StringCodec.UTF8);
        redis = redisConnection.sync();
        stompClient = new Stage3StompClient();
    }

    @AfterEach
    void closeClients() throws Exception {
        subscriptionObserver.clear();
        Exception first = null;
        for (int index = closeables.size() - 1; index >= 0; index--) {
            try {
                closeables.get(index).close();
            } catch (Exception failure) {
                if (first == null) {
                    first = failure;
                }
            }
        }
        closeables.clear();
        if (stompClient != null) {
            stompClient.close();
        }
        if (redisConnection != null) {
            redisConnection.close();
        }
        if (first != null) {
            throw first;
        }
    }

    @Test
    @Order(1)
    void actualMetricsDriveRiskIncidentsStatusAndStomp() throws Exception {
        resetBusinessData();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        seedTarget(PIPELINE_TARGET_ID, "native-pipeline", now.minusSeconds(20));
        seedTarget(CREDENTIAL_TARGET_ID, "native-credential", now.minusSeconds(20));
        long webhookId = insertSlackWebhook(now.minusSeconds(1));
        seedUser();

        LoginSession login = login();
        Stage3StompClient.Connection socket = track(stompClient.begin(port, ORIGIN, login.accessToken())
                .connected());
        Stage3StompClient.FrameQueue statusFrames = subscribe(
                socket, "native-status", "/topic/databases/" + PIPELINE_TARGET_ID + "/status");
        Stage3StompClient.FrameQueue incidentFrames = subscribe(
                socket, "native-incident", "/topic/databases/" + PIPELINE_TARGET_ID + "/incidents");

        UUID incidentId;
        installNotificationDeliveryFailureTrigger();
        try {
            Instant firstObservation = now.minusSeconds(15);
            for (int offset : new int[]{0, 5, 10, 15}) {
                publishSuccessMetric(PIPELINE_TARGET_ID, "native-pipeline",
                        firstObservation.plusSeconds(offset), 80L, 100L, 0.0);
            }
            incidentId = awaitOpenIncident(PIPELINE_TARGET_ID, "CONNECTION_RATIO");
            UUID failedIncidentId = incidentId;
            await("notification transaction rollback remains pending", EVENT_TIMEOUT, () ->
                    pendingNotificationRecords() >= 1L
                            && jdbc.queryForObject("""
                            SELECT count(*) FROM processed_events
                            WHERE stream='stream:incidents' AND consumer_group='cg:notification'
                            """, Long.class) == 0L
                            && jdbc.queryForObject("""
                            SELECT count(*) FROM notification_deliveries WHERE incident_id=?
                            """, Long.class, failedIncidentId) == 0L);
            Thread.sleep(1_200L);
            assertThat(pendingNotificationRecords()).isGreaterThanOrEqualTo(1L);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM processed_events
                    WHERE stream='stream:incidents' AND consumer_group='cg:notification'
                    """, Long.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM notification_deliveries WHERE incident_id=?
                    """, Long.class, incidentId)).isZero();
        } finally {
            dropNotificationDeliveryFailureTrigger();
        }

        assertThat(incidentId).isNotNull();
        long deliveryId = awaitScheduledSlackDelivery(incidentId, webhookId);
        assertNotificationSharedDeadLetter(incidentId);
        JsonNode statusFrame = pollFrame(statusFrames, EVENT_TIMEOUT,
                frame -> frame.path("databaseConfigId").asLong() == PIPELINE_TARGET_ID
                        && "WARNING".equals(frame.at("/data/riskLevel").asText()));
        JsonNode incidentFrame = pollFrame(incidentFrames, EVENT_TIMEOUT,
                frame -> incidentId.toString().equals(frame.at("/data/incidentId").asText()));

        assertThat(statusFrame.path("eventType").asText()).isEqualTo("MonitoringStatusChanged");
        assertThat(statusFrame.at("/data/openIncidentIds").toString())
                .contains(incidentId.toString());
        assertThat(incidentFrame.path("eventType").asText()).isEqualTo("IncidentCreatedEvent");
        assertThat(incidentFrame.at("/data/ruleId").asText()).isEqualTo("CONNECTION_RATIO");
        assertThat(incidentFrame.path("data").has("severityTransition")).isFalse();
        assertThat(incidentFrame.path("data").has("sourceEventId")).isFalse();
        assertThat(incidentFrame.path("data").has("timestamp")).isFalse();

        JsonNode statusHttp = authenticatedGet(
                login, "/api/v1/databases/" + PIPELINE_TARGET_ID + "/status");
        assertThat(statusHttp.path("riskLevel").asText()).isEqualTo("WARNING");
        assertThat(statusHttp.path("openIncidentIds").toString()).contains(incidentId.toString());
        JsonNode incidentsHttp = authenticatedGet(
                login, "/api/v1/incidents?databaseConfigId=" + PIPELINE_TARGET_ID + "&status=OPEN");
        assertThat(incidentsHttp.path("items").size()).isEqualTo(1);
        assertThat(incidentsHttp.at("/items/0/incidentId").asText()).isEqualTo(incidentId.toString());

        int slackPort = requiredPort("PART_C_NATIVE_QA_SLACK_PORT");
        try (LocalTlsProviderFixture fixture = LocalTlsProviderFixture.start(
                "hooks.slack.com",
                slackPort,
                ignored -> LocalTlsProviderFixture.Response.immediate(200, "ok"));
             LocalSlackClient local = localSlackClient(fixture, slackPort)) {
            NotificationDeliveryWorker worker = nativeDeliveryWorker(
                    local.sender(), local.policy(), new SlackAttemptPacer());
            assertThat(worker.runOnce()).isEqualTo(1);
            fixture.awaitCaptureCount(1, Duration.ofSeconds(5));
            assertThat(deliveryState(deliveryId))
                    .containsEntry("status", "SENT")
                    .containsEntry("attempt_count", 1);
            Map<String, Object> sentDelivery = deliveryState(deliveryId);
            Map<String, Object> receipt = jdbc.queryForMap("""
                    SELECT channel, push_subscription_id, notification_webhook_id,
                           last_successful_open_or_increase_at
                    FROM notification_success_receipts
                    WHERE incident_id=? AND channel='SLACK' AND recipient_id=?
                    """, incidentId, webhookId);
            assertThat(receipt)
                    .containsEntry("channel", "SLACK")
                    .containsEntry("push_subscription_id", null)
                    .containsEntry("notification_webhook_id", webhookId);
            assertThat(((Timestamp) receipt.get("last_successful_open_or_increase_at")).toInstant())
                    .isEqualTo(sentDelivery.get("sent_at"));
            assertThat(fixture.captures().get(0).path())
                    .isEqualTo("/services/T000/B000/native");
        }
        PartCNativeQaEvidence.write("notification-scheduling.json", Map.of(
                "actualIncidentStream", true,
                "durableScheduling", true,
                "schedulingRollbackRetried", true,
                "notificationSharedDeadLetter", true,
                "localTlsDelivery", true,
                "successReceiptLinked", true,
                "committedBeforeAck", true));

        long credentialMetricId = publishConnectionFailedMetric(
                CREDENTIAL_TARGET_ID, "native-credential", Instant.now().truncatedTo(ChronoUnit.MILLIS));
        await("credential failure state", EVENT_TIMEOUT, () -> credentialMetricId == nullableLong("""
                SELECT latest_metric_id FROM monitoring_states WHERE database_config_id=?
                """, CREDENTIAL_TARGET_ID)
                && "DOWN".equals(singleString("""
                SELECT connection_status FROM monitoring_states WHERE database_config_id=?
                """, CREDENTIAL_TARGET_ID)));
        assertThat(singleString("""
                SELECT rule_id FROM risk_rule_states
                WHERE database_config_id=? AND fatal_candidate_since IS NOT NULL
                """, CREDENTIAL_TARGET_ID)).isEqualTo("CONNECTION_FAILURE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE database_config_id=? AND rule_id='CONNECTION_FAILURE'
                """, Long.class, CREDENTIAL_TARGET_ID)).isZero();

        PartCNativeQaEvidence.write("risk-realtime.json", Map.of(
                "actualPostgres", true,
                "actualRedis", true,
                "actualMariaDbCollector", false,
                "collectorIngress", "persisted canonical metric_data row plus Redis payload",
                "http", true,
                "stomp", true,
                "metricRiskIncidentStatus", true,
                "notificationScheduledDelivered", true,
                "updatedCredentialFailureSemantics", true));
    }

    @Test
    @Order(2)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void prepareCandidateForFullApplicationRestart() throws Exception {
        resetBusinessData();
        Instant observation = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(5);
        seedTarget(RESTART_TARGET_ID, "native-restart", observation.minusSeconds(5));
        publishSuccessMetric(RESTART_TARGET_ID, "native-restart", observation, 80L, 100L, 0.0);
        await("pre-restart candidate", EVENT_TIMEOUT, () -> jdbc.queryForObject("""
                SELECT warning_candidate_since IS NOT NULL FROM risk_rule_states
                WHERE database_config_id=? AND rule_id='CONNECTION_RATIO'
                """, Boolean.class, RESTART_TARGET_ID));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM incidents WHERE database_config_id=?
                """, Long.class, RESTART_TARGET_ID)).isZero();

        SmartLifecycle riskWorker = context.getBean("riskMetricStreamWorker", SmartLifecycle.class);
        riskWorker.stop();
        PublishedMetric startupProbe = prepareSuccessMetric(
                STARTUP_PROBE_EVENT_ID,
                RESTART_TARGET_ID,
                "native-restart",
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                80L,
                100L,
                0.0);
        redis.xadd(METRIC_STREAM, Map.of("payload", startupProbe.json()));

        Instant retryCreated = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        long retryWebhookId = insertSlackWebhook(retryCreated.minusSeconds(1));
        insertOpenIncident(
                RETRY_INCIDENT_ID,
                RESTART_TARGET_ID,
                "native-restart",
                "SLOW_QUERY_RATE",
                "SLOW_QUERIES_HIGH",
                "CRITICAL",
                retryCreated);
        long retryDeliveryId = insertPendingSlackDelivery(
                RETRY_INCIDENT_ID, retryWebhookId, retryCreated);
        int slackPort = requiredPort("PART_C_NATIVE_QA_SLACK_PORT");
        try (LocalTlsProviderFixture fixture = LocalTlsProviderFixture.start(
                "hooks.slack.com",
                slackPort,
                ignored -> LocalTlsProviderFixture.Response.immediate(503, "busy"));
             LocalSlackClient local = localSlackClient(fixture, slackPort)) {
            NotificationDeliveryWorker worker = nativeDeliveryWorker(
                    local.sender(), local.policy(), new SlackAttemptPacer());
            assertThat(worker.runOnce()).isEqualTo(1);
            fixture.awaitCaptureCount(1, Duration.ofSeconds(5));
        }
        Map<String, Object> retryState = deliveryState(retryDeliveryId);
        assertThat(retryState)
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 1)
                .containsEntry("last_error_code", DeliveryOutcomeKind.PROVIDER_ERROR.name());
        assertThat((Instant) retryState.get("expires_at"))
                .isEqualTo(retryCreated.plusSeconds(600));
        assertThat((Instant) retryState.get("next_attempt_at"))
                .isBefore((Instant) retryState.get("expires_at"));
        jdbc.update("""
                UPDATE risk_policies SET stale_after_seconds=3600
                WHERE database_config_id=?
                """, RESTART_TARGET_ID);
        PartCNativeQaEvidence.write("notification-retry-pre-restart.json", Map.of(
                "providerFailurePersisted", true,
                "absoluteRetryDuePersisted", true,
                "originalExpiryPersisted", true));
    }

    @Test
    @Order(3)
    void exactStaleRestartReplaySessionRevocationAndRetentionFailSafe() throws Exception {
        await("startup reset before queued metric consumption", EVENT_TIMEOUT, () ->
                jdbc.queryForObject("""
                        SELECT count(*) FROM processed_events
                        WHERE stream=? AND consumer_group=? AND event_id=?
                        """, Long.class, METRIC_STREAM, RISK_GROUP, STARTUP_PROBE_EVENT_ID) == 1L
                        && pendingRiskRecords() == 0L
                        && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT rules.warning_candidate_since = metric.collection_attempt_time
                FROM risk_rule_states rules
                JOIN monitoring_states state
                  ON state.database_config_id=rules.database_config_id
                JOIN metric_data metric ON metric.id=state.latest_metric_id
                WHERE rules.database_config_id=? AND rules.rule_id='CONNECTION_RATIO'
                """, Boolean.class, RESTART_TARGET_ID)));
        completePersistedRetryAfterRestart();

        publishSuccessMetric(
                RESTART_TARGET_ID,
                "native-restart",
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                10L,
                100L,
                0.0);
        await("clear startup proof candidate", EVENT_TIMEOUT, () ->
                Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT warning_candidate_since IS NULL
                FROM risk_rule_states
                WHERE database_config_id=? AND rule_id='CONNECTION_RATIO'
                """, Boolean.class, RESTART_TARGET_ID)));

        Instant postRestart = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        PublishedMetric accepted = publishSuccessMetric(
                RESTART_TARGET_ID, "native-restart", postRestart, 80L, 100L, 0.0);
        await("new post-restart candidate", EVENT_TIMEOUT, () -> postRestart.equals(singleInstant("""
                SELECT warning_candidate_since FROM risk_rule_states
                WHERE database_config_id=? AND rule_id='CONNECTION_RATIO'
                """, RESTART_TARGET_ID)));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE database_config_id=? AND rule_id='CONNECTION_RATIO'
                """, Long.class, RESTART_TARGET_ID)).isZero();

        long versionBeforeReplay = jdbc.queryForObject("""
                SELECT state_version FROM monitoring_states WHERE database_config_id=?
                """, Long.class, RESTART_TARGET_ID);
        SmartLifecycle riskWorker = context.getBean("riskMetricStreamWorker", SmartLifecycle.class);
        riskWorker.stop();
        redis.xadd(METRIC_STREAM, Map.of("payload", accepted.json()));
        redis.xadd(METRIC_STREAM, Map.of("payload", accepted.json()));
        UUID replayProbeId = UUID.randomUUID();
        ObjectNode replayProbe = (ObjectNode) objectMapper.readTree(accepted.json());
        replayProbe.put("eventId", replayProbeId.toString());
        redis.xadd(METRIC_STREAM, Map.of("payload", objectMapper.writeValueAsString(replayProbe)));
        riskWorker.start();
        await("replayed records ACKed", EVENT_TIMEOUT, () -> jdbc.queryForObject("""
                SELECT count(*) FROM processed_events
                WHERE stream=? AND consumer_group=? AND event_id=?
                """, Long.class, METRIC_STREAM, RISK_GROUP, replayProbeId) == 1L
                && pendingRiskRecords() == 0);
        assertThat(jdbc.queryForObject("""
                SELECT state_version FROM monitoring_states WHERE database_config_id=?
                """, Long.class, RESTART_TARGET_ID)).isEqualTo(versionBeforeReplay);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM processed_events
                WHERE stream=? AND consumer_group=? AND event_id=?
                """, Long.class, METRIC_STREAM, RISK_GROUP, accepted.eventId())).isOne();

        assertRiskTransactionRollbackRetriesPendingRecord();
        assertHeartbeatAckAndOldHeartbeatIsolation();

        assertLeaseLossStopsBeforeHttpAndReplacementCompletes();
        PartCNativeQaEvidence.write("notification-retry-lease.json", Map.of(
                "retryWindowSurvivedRestart", true,
                "originalExpiryPreserved", true,
                "leaseLossStoppedBeforeHttp", true,
                "replacementLeaseCompleted", true));
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM notification_webhooks");

        SmartLifecycle staleScheduler = context.getBean("riskStaleScheduler", SmartLifecycle.class);
        staleScheduler.stop();
        seedTarget(STALE_TARGET_ID, "native-stale",
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        Instant logicalDue = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusMillis(750);
        Instant activation = logicalDue.minusSeconds(30);
        jdbc.update("""
                UPDATE monitoring_states SET activation_at=?, updated_at=?
                WHERE database_config_id=?
                """, Timestamp.from(activation), Timestamp.from(activation), STALE_TARGET_ID);
        staleScheduler.start();
        await("exact stale transition", EVENT_TIMEOUT, () -> jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE database_config_id=? AND rule_id='COLLECTION_STALE' AND status='OPEN'
                """, Long.class, STALE_TARGET_ID) == 1L);
        assertThat(singleInstant("""
                SELECT opened_at FROM incidents
                WHERE database_config_id=? AND rule_id='COLLECTION_STALE' AND status='OPEN'
                """, STALE_TARGET_ID)).isEqualTo(logicalDue);
        assertThat(singleString("""
                SELECT data_freshness FROM monitoring_states WHERE database_config_id=?
                """, STALE_TARGET_ID)).isEqualTo("STALE");
        Duration stalePollLag = Duration.between(logicalDue, Instant.now());
        assertThat(stalePollLag.isNegative()).isFalse();
        assertThat(stalePollLag).isLessThan(Duration.ofSeconds(1));

        seedUser();
        LoginSession login = login();
        AccessTokenService.VerifiedAccessToken verified = accessTokenService.verify(login.accessToken());
        Stage3StompClient.Connection socket = track(stompClient.begin(port, ORIGIN, login.accessToken())
                .connected());
        long pushId = insertPushSubscription(verified.userId(), verified.sessionId());
        UUID revokedIncident = insertOpenIncident(
                RESTART_TARGET_ID, "native-restart", "CONNECTION_FAILURE", "CONNECTION_FAILURE", "FATAL",
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        long revokedDelivery = insertPendingPushDelivery(revokedIncident, pushId,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        logout(login);
        byte[] sessionErrorPayload = socket.protocolError(EVENT_TIMEOUT);
        assertThat(sessionErrorPayload).isNotNull();
        JsonNode sessionError = objectMapper.readTree(sessionErrorPayload);
        assertThat(sessionError.path("code").asText()).isEqualTo("SESSION_REVOKED");
        assertThat(socket.terminal(EVENT_TIMEOUT)).isNotNull();
        assertThat(jdbc.queryForMap("""
                SELECT enabled, deleted_at IS NOT NULL AS deleted
                FROM push_subscriptions WHERE id=?
                """, pushId)).containsEntry("enabled", false).containsEntry("deleted", true);
        assertThat(singleString("SELECT status FROM notification_deliveries WHERE id=?", revokedDelivery))
                .isEqualTo("PENDING");

        NotificationDeliveryWorker revokedWorker = nativeDeliveryWorker(
                slackSender, slackWebhookPolicy, new SlackAttemptPacer());
        int handled = revokedWorker.runOnce();
        assertThat(handled).isZero();
        assertThat(singleString("SELECT status FROM notification_deliveries WHERE id=?", revokedDelivery))
                .isEqualTo("CANCELLED");
        verifyNoInteractions(webPushSender, slackSender);

        assertRetentionBoundaries(pushId, RESTART_TARGET_ID);
        PartCNativeQaEvidence.write("timing-restart-replay.json", Map.ofEntries(
                Map.entry("exactThreshold", true),
                Map.entry("restartClockReset", true),
                Map.entry("startupBeforeConsumption", true),
                Map.entry("replaySuppressed", true),
                Map.entry("transactionRollbackRetried", true),
                Map.entry("heartbeatAcked", true),
                Map.entry("oldHeartbeatIsolated", true),
                Map.entry("sharedDeadLetter", true),
                Map.entry("stalePollUnderOneSecond", true),
                Map.entry("stalePollConfiguredMs", 1_000),
                Map.entry("stalePollObservedLagMs", stalePollLag.toMillis())));
        PartCNativeQaEvidence.write("session-retention.json", Map.of(
                "sessionRevocation", true,
                "retentionBoundary", true));
    }

    private void completePersistedRetryAfterRestart() throws Exception {
        long deliveryId = jdbc.queryForObject("""
                SELECT id FROM notification_deliveries
                WHERE incident_id=? AND channel='SLACK'
                """, Long.class, RETRY_INCIDENT_ID);
        Map<String, Object> before = deliveryState(deliveryId);
        assertThat(before)
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 1)
                .containsEntry("last_error_code", DeliveryOutcomeKind.PROVIDER_ERROR.name());
        Instant due = (Instant) before.get("next_attempt_at");
        Instant expiresAt = (Instant) before.get("expires_at");
        assertThat(due).isNotNull().isBefore(expiresAt);
        await("persisted delivery retry due", EVENT_TIMEOUT,
                () -> !clock.instant().truncatedTo(ChronoUnit.MILLIS).isBefore(due));

        int slackPort = requiredPort("PART_C_NATIVE_QA_SLACK_PORT");
        try (LocalTlsProviderFixture fixture = LocalTlsProviderFixture.start(
                "hooks.slack.com",
                slackPort,
                ignored -> LocalTlsProviderFixture.Response.immediate(200, "ok"));
             LocalSlackClient local = localSlackClient(fixture, slackPort)) {
            NotificationDeliveryWorker replacement = nativeDeliveryWorker(
                    local.sender(), local.policy(), new SlackAttemptPacer());
            assertThat(replacement.runOnce()).isEqualTo(1);
            fixture.awaitCaptureCount(1, Duration.ofSeconds(5));
        }
        Map<String, Object> after = deliveryState(deliveryId);
        assertThat(after)
                .containsEntry("status", "SENT")
                .containsEntry("attempt_count", 2)
                .containsEntry("expires_at", expiresAt);
        assertThat(after.get("next_attempt_at")).isNull();
    }

    private void assertLeaseLossStopsBeforeHttpAndReplacementCompletes() throws Exception {
        long webhookId = jdbc.queryForObject("""
                SELECT id FROM notification_webhooks
                WHERE enabled AND deleted_at IS NULL ORDER BY id LIMIT 1
                """, Long.class);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        insertOpenIncident(
                LEASE_INCIDENT_ID,
                RESTART_TARGET_ID,
                "native-restart",
                "CONNECTION_FAILURE",
                "CONNECTION_FAILURE",
                "FATAL",
                createdAt);
        long deliveryId = insertPendingSlackDelivery(LEASE_INCIDENT_ID, webhookId, createdAt);
        int slackPort = requiredPort("PART_C_NATIVE_QA_SLACK_PORT");
        try (LocalTlsProviderFixture fixture = LocalTlsProviderFixture.start(
                "hooks.slack.com",
                slackPort,
                ignored -> LocalTlsProviderFixture.Response.immediate(200, "ok"));
             LocalSlackClient local = localSlackClient(fixture, slackPort)) {
            PostgresDeliveryLease lostLease = track(new PostgresDeliveryLease(dataSource));
            NotificationDeliveryWorker first = nativeDeliveryWorker(
                    lostLease, local.sender(), local.policy(), new SlackAttemptPacer());
            CompletableFuture<Integer> firstRun = CompletableFuture.supplyAsync(first::runOnce);
            await("delivery claimed before lease termination", EVENT_TIMEOUT,
                    () -> lostLease.backendPid() > 0
                            && ((Number) deliveryState(deliveryId).get("attempt_count")).intValue() == 1);
            assertThat(jdbc.queryForObject(
                    "SELECT pg_terminate_backend(?)",
                    Boolean.class,
                    lostLease.backendPid())).isTrue();
            assertThat(firstRun.get(5, TimeUnit.SECONDS)).isZero();
            fixture.awaitIdle(Duration.ofSeconds(2));
            assertThat(fixture.captures()).isEmpty();

            Map<String, Object> pending = deliveryState(deliveryId);
            assertThat(pending)
                    .containsEntry("status", "PENDING")
                    .containsEntry("attempt_count", 1);
            Instant due = (Instant) pending.get("next_attempt_at");
            assertThat(due).isNotNull().isBefore((Instant) pending.get("expires_at"));
            await("lease-loss retry due", EVENT_TIMEOUT,
                    () -> !clock.instant().truncatedTo(ChronoUnit.MILLIS).isBefore(due));

            NotificationDeliveryWorker replacement = nativeDeliveryWorker(
                    local.sender(), local.policy(), new SlackAttemptPacer());
            assertThat(replacement.runOnce()).isEqualTo(1);
            fixture.awaitCaptureCount(1, Duration.ofSeconds(5));
            assertThat(deliveryState(deliveryId))
                    .containsEntry("status", "SENT")
                    .containsEntry("attempt_count", 2);
        }
    }

    private void assertRiskTransactionRollbackRetriesPendingRecord() throws Exception {
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        seedTarget(ROLLBACK_TARGET_ID, "native-rollback", observedAt.minusSeconds(5));
        jdbc.update("""
                UPDATE risk_policies SET stale_after_seconds=3600
                WHERE database_config_id=?
                """, ROLLBACK_TARGET_ID);
        PublishedMetric metric = prepareSuccessMetric(
                UUID.randomUUID(), ROLLBACK_TARGET_ID, "native-rollback",
                observedAt, 80L, 100L, 0.0);
        String before = riskBusinessSnapshot(ROLLBACK_TARGET_ID);
        installRiskOutboxFailureTrigger();
        try {
            redis.xadd(METRIC_STREAM, Map.of("payload", metric.json()));
            await("failed risk record remains pending", EVENT_TIMEOUT, () ->
                    pendingRiskRecords() >= 1L
                            && jdbc.queryForObject("""
                            SELECT count(*) FROM processed_events
                            WHERE stream=? AND consumer_group=? AND event_id=?
                            """, Long.class, METRIC_STREAM, RISK_GROUP, metric.eventId()) == 0L);
            Thread.sleep(1_200L);
            assertThat(pendingRiskRecords()).isGreaterThanOrEqualTo(1L);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM processed_events
                    WHERE stream=? AND consumer_group=? AND event_id=?
                    """, Long.class, METRIC_STREAM, RISK_GROUP, metric.eventId())).isZero();
            assertThat(riskBusinessSnapshot(ROLLBACK_TARGET_ID)).isEqualTo(before);
        } finally {
            dropRiskOutboxFailureTrigger();
        }
        await("rolled-back risk record retries and ACKs", EVENT_TIMEOUT, () ->
                jdbc.queryForObject("""
                        SELECT count(*) FROM processed_events
                        WHERE stream=? AND consumer_group=? AND event_id=?
                        """, Long.class, METRIC_STREAM, RISK_GROUP, metric.eventId()) == 1L
                        && pendingRiskRecords() == 0L
                        && nullableLong("""
                        SELECT latest_metric_id FROM monitoring_states WHERE database_config_id=?
                        """, ROLLBACK_TARGET_ID) == metric.metricId());
    }

    private void assertHeartbeatAckAndOldHeartbeatIsolation() throws Exception {
        String before = riskBusinessSnapshot(ROLLBACK_TARGET_ID);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID liveEventId = UUID.randomUUID();
        redis.xadd(HEARTBEAT_STREAM, Map.of(
                "payload", heartbeatJson(liveEventId, "native-live", now)));
        await("live heartbeat ACK", EVENT_TIMEOUT, () ->
                heartbeatHealthRegistry.isLive("native-live")
                        && pendingHeartbeatRecords() == 0L);

        UUID staleEventId = UUID.randomUUID();
        redis.xadd(HEARTBEAT_STREAM, Map.of(
                "payload", heartbeatJson(staleEventId, "native-old", now.minusSeconds(31))));
        await("old heartbeat DLQ then ACK", EVENT_TIMEOUT, () ->
                "STALE_HEARTBEAT".equals(deadLetterReason(staleEventId))
                        && pendingHeartbeatRecords() == 0L);
        assertThat(heartbeatHealthRegistry.health("native-old")).isEmpty();
        assertThat(riskBusinessSnapshot(ROLLBACK_TARGET_ID)).isEqualTo(before);
    }

    private void assertNotificationSharedDeadLetter(UUID incidentId) throws Exception {
        UUID rejectedEventId = UUID.randomUUID();
        Instant timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ObjectNode event = objectMapper.createObjectNode();
        event.put("schemaVersion", 1);
        event.put("eventId", rejectedEventId.toString());
        event.put("eventType", "UnsupportedIncidentEvent");
        event.put("publishedAt", timestamp.toString());
        event.put("timestamp", timestamp.toString());
        event.putNull("sourceEventId");
        event.put("incidentId", incidentId.toString());
        event.put("databaseConfigId", PIPELINE_TARGET_ID);
        event.put("databaseName", "native-pipeline");
        event.put("ruleId", "CONNECTION_RATIO");
        event.put("ruleType", "CONNECTION_RATIO_EXCEEDED");
        event.put("severity", "CRITICAL");
        event.put("status", "OPEN");
        event.put("openedAt", timestamp.minusSeconds(15).toString());
        event.put("lastObservedAt", timestamp.toString());
        event.putNull("resolvedAt");
        event.putNull("resolutionReason");
        event.put("metricName", "activeConnectionsRatio");
        event.put("metricValue", 0.8);
        event.put("thresholdValue", 0.8);
        event.putNull("sourceMetricId");
        event.put("message", "Native QA notification DLQ probe");
        event.put("incidentVersion", 1);

        stopWorker("incidentStreamWorker");
        try {
            redis.xadd("stream:incidents", Map.of(
                    "payload", objectMapper.writeValueAsString(event)));
            await("notification invalid event DLQ then ACK", EVENT_TIMEOUT, () ->
                    "INVALID_EVENT_TYPE".equals(deadLetterReason(rejectedEventId))
                            && pendingNotificationRecords() == 0L);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM processed_events
                    WHERE stream='stream:incidents' AND consumer_group='cg:notification'
                      AND event_id=?
                    """, Long.class, rejectedEventId)).isZero();
        } finally {
            startWorker("incidentStreamWorker");
        }
    }

    private String heartbeatJson(UUID eventId, String collectorId, Instant timestamp)
            throws Exception {
        ObjectNode heartbeat = objectMapper.createObjectNode();
        heartbeat.put("schemaVersion", 1);
        heartbeat.put("eventId", eventId.toString());
        heartbeat.put("eventType", "CollectorHeartbeatEvent");
        heartbeat.put("publishedAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        heartbeat.put("collectorId", collectorId);
        heartbeat.put("timestamp", timestamp.toString());
        heartbeat.put("lastCycleStartedAt", timestamp.minusSeconds(1).toString());
        heartbeat.put("lastCycleCompletedAt", timestamp.toString());
        heartbeat.put("cycleInProgress", false);
        return objectMapper.writeValueAsString(heartbeat);
    }

    private String deadLetterReason(UUID eventId) {
        List<StreamMessage<String, String>> records =
                redis.xrange(DEAD_LETTER_STREAM, Range.unbounded());
        for (StreamMessage<String, String> record : records) {
            String payload = record.getBody().get("payload");
            if (payload == null) {
                continue;
            }
            try {
                JsonNode body = objectMapper.readTree(payload);
                if (eventId.toString().equals(body.path("eventId").asText())) {
                    return body.path("reasonCode").asText();
                }
            } catch (IOException invalidDeadLetter) {
                throw new IllegalStateException("Native dead-letter JSON is invalid", invalidDeadLetter);
            }
        }
        return null;
    }

    private String riskBusinessSnapshot(long targetId) {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'state', (SELECT to_jsonb(s) FROM monitoring_states s
                              WHERE database_config_id=?),
                    'rules', (SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY rule_id), '[]'::jsonb)
                              FROM risk_rule_states r WHERE database_config_id=?),
                    'incidents', (SELECT COALESCE(jsonb_agg(to_jsonb(i) ORDER BY incident_id), '[]'::jsonb)
                                  FROM incidents i WHERE database_config_id=?))::text
                """, String.class, targetId, targetId, targetId);
    }

    private void installRiskOutboxFailureTrigger() {
        dropRiskOutboxFailureTrigger();
        jdbc.execute("""
                CREATE FUNCTION part_c_native_fail_status_outbox() RETURNS trigger AS $$
                BEGIN
                  IF NEW.event_type = 'MonitoringStatusChangedEvent' THEN
                    RAISE EXCEPTION 'forced native risk status outbox failure';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER part_c_native_fail_status_outbox_trigger
                BEFORE INSERT ON event_outbox
                FOR EACH ROW EXECUTE FUNCTION part_c_native_fail_status_outbox()
                """);
    }

    private void dropRiskOutboxFailureTrigger() {
        jdbc.execute("""
                DROP TRIGGER IF EXISTS part_c_native_fail_status_outbox_trigger ON event_outbox
                """);
        jdbc.execute("DROP FUNCTION IF EXISTS part_c_native_fail_status_outbox()");
    }

    private void installNotificationDeliveryFailureTrigger() {
        dropNotificationDeliveryFailureTrigger();
        jdbc.execute("""
                CREATE FUNCTION part_c_native_fail_delivery_insert() RETURNS trigger AS $$
                BEGIN
                  RAISE EXCEPTION 'forced native notification delivery failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER part_c_native_fail_delivery_insert_trigger
                BEFORE INSERT ON notification_deliveries
                FOR EACH ROW EXECUTE FUNCTION part_c_native_fail_delivery_insert()
                """);
    }

    private void dropNotificationDeliveryFailureTrigger() {
        jdbc.execute("""
                DROP TRIGGER IF EXISTS part_c_native_fail_delivery_insert_trigger
                ON notification_deliveries
                """);
        jdbc.execute("DROP FUNCTION IF EXISTS part_c_native_fail_delivery_insert()");
    }

    private void resetBusinessData() {
        dropRiskOutboxFailureTrigger();
        dropNotificationDeliveryFailureTrigger();
        stopWorker("riskMetricStreamWorker");
        stopWorker("riskHeartbeatStreamWorker");
        stopWorker("riskStaleScheduler");
        stopWorker("statusStreamWorker");
        stopWorker("incidentStreamWorker");
        stopWorker("notificationIncidentStreamWorker");
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM notification_webhooks");
        jdbc.update("DELETE FROM push_subscriptions");
        jdbc.update("DELETE FROM risk_rule_states");
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM risk_policies");
        jdbc.update("DELETE FROM monitoring_states");
        jdbc.update("DELETE FROM metric_data");
        jdbc.update("DELETE FROM database_configs");
        startWorker("notificationIncidentStreamWorker");
        startWorker("incidentStreamWorker");
        startWorker("statusStreamWorker");
        startWorker("riskStaleScheduler");
        startWorker("riskHeartbeatStreamWorker");
        startWorker("riskMetricStreamWorker");
    }

    private void seedTarget(long id, String name, Instant activationAt) throws Exception {
        jdbc.update("""
                INSERT INTO database_configs
                    (id, collection_interval_seconds, created_at, enabled, host, name, port, status,
                     config_version, deleted_at)
                VALUES (?, 5, ?, true, '127.0.0.1', ?, 13306, 'UNKNOWN', 1, null)
                """, id, Timestamp.from(activationAt), name);
        jdbc.update("""
                INSERT INTO monitoring_states
                    (database_config_id, config_version, state_version, enabled, deleted,
                     connection_status, data_freshness, risk_level, activation_at, updated_at)
                VALUES (?, 1, 1, true, false, 'UNKNOWN', 'NO_DATA', null, ?, ?)
                """, id, Timestamp.from(activationAt), Timestamp.from(activationAt));
        jdbc.update("""
                INSERT INTO risk_policies
                    (database_config_id, version, rules, stale_after_seconds,
                     notification_cooldown_seconds, created_at, updated_at)
                VALUES (?, 1, CAST(? AS jsonb), 30, 300, ?, ?)
                """, id, objectMapper.writeValueAsString(MonitoringContracts.defaultRules()),
                Timestamp.from(activationAt), Timestamp.from(activationAt));
    }

    private PublishedMetric publishSuccessMetric(
            long targetId,
            String databaseName,
            Instant observedAt,
            long activeConnections,
            long maxConnections,
            double slowRate
    ) throws Exception {
        PublishedMetric published = prepareSuccessMetric(
                UUID.randomUUID(), targetId, databaseName, observedAt,
                activeConnections, maxConnections, slowRate);
        redis.xadd(METRIC_STREAM, Map.of("payload", published.json()));
        awaitMetricAccepted(targetId, published.metricId(), published.eventId());
        return published;
    }

    private PublishedMetric prepareSuccessMetric(
            UUID eventId,
            long targetId,
            String databaseName,
            Instant observedAt,
            long activeConnections,
            long maxConnections,
            double slowRate
    ) throws Exception {
        long metricId = jdbc.queryForObject("""
                INSERT INTO metric_data (
                    active_connections, max_connections, slow_queries, slow_queries_delta,
                    slow_queries_per_second, metric_window_seconds, response_time_ms,
                    collection_status, created_at, database_config_id, timestamp,
                    config_version, collection_attempt_time, last_success_at, unavailable_metrics
                ) VALUES (?, ?, 0, 0, ?, 5.0, 1, 'SUCCESS', ?, ?, ?, 1, ?, ?, '{}'::jsonb)
                RETURNING id
                """, Long.class, activeConnections, maxConnections, slowRate,
                Timestamp.from(observedAt), targetId, Timestamp.from(observedAt),
                Timestamp.from(observedAt), Timestamp.from(observedAt));
        MetricCollectedPayloadV1 payload = new MetricCollectedPayloadV1(
                1, eventId, "MetricCollectedEvent", observedAt,
                metricId, targetId, 1L, databaseName, observedAt, observedAt, observedAt,
                null, null, activeConnections, maxConnections, null,
                0L, 0L, slowRate, 5.0, null, null, 1L,
                MetricCollectedPayloadV1.CollectionStatus.SUCCESS, null, null, Map.of());
        String json = objectMapper.writeValueAsString(payload);
        return new PublishedMetric(eventId, json, metricId);
    }

    private long publishConnectionFailedMetric(long targetId, String databaseName, Instant observedAt)
            throws Exception {
        Map<String, MetricCollectedPayloadV1.UnavailableReason> unavailable = new LinkedHashMap<>();
        for (String field : List.of(
                "cpuUsage", "memoryUsage", "activeConnections", "maxConnections", "qps",
                "slowQueries", "slowQueriesDelta", "slowQueriesPerSecond", "metricWindowSeconds",
                "threadsRunning", "storageBytes")) {
            unavailable.put(field, MetricCollectedPayloadV1.UnavailableReason.COLLECTION_FAILED);
        }
        long metricId = jdbc.queryForObject("""
                INSERT INTO metric_data (
                    collection_status, created_at, database_config_id, timestamp,
                    config_version, collection_attempt_time, last_success_at,
                    response_time_ms, error_code, error_message, unavailable_metrics
                ) VALUES ('CONNECTION_FAILED', ?, ?, ?, 1, ?, null,
                          0, 'INTERNAL_ERROR', 'database connection failed', CAST(? AS jsonb))
                RETURNING id
                """, Long.class, Timestamp.from(observedAt), targetId, Timestamp.from(observedAt),
                Timestamp.from(observedAt), objectMapper.writeValueAsString(unavailable));
        UUID eventId = UUID.randomUUID();
        MetricCollectedPayloadV1 payload = new MetricCollectedPayloadV1(
                1, eventId, "MetricCollectedEvent", observedAt,
                metricId, targetId, 1L, databaseName, observedAt, observedAt, null,
                null, null, null, null, null, null, null, null, null, null, null, 0L,
                MetricCollectedPayloadV1.CollectionStatus.CONNECTION_FAILED,
                MetricCollectedPayloadV1.MetricErrorCode.INTERNAL_ERROR,
                "database connection failed", unavailable);
        redis.xadd(METRIC_STREAM, Map.of("payload", objectMapper.writeValueAsString(payload)));
        awaitMetricAccepted(targetId, metricId, eventId);
        return metricId;
    }

    private void awaitMetricAccepted(long targetId, long metricId, UUID eventId) {
        await("risk metric " + metricId, EVENT_TIMEOUT, () -> jdbc.queryForObject("""
                SELECT count(*) FROM processed_events
                WHERE stream=? AND consumer_group=? AND event_id=?
                """, Long.class, METRIC_STREAM, RISK_GROUP, eventId) == 1L
                && nullableLong("""
                SELECT latest_metric_id FROM monitoring_states WHERE database_config_id=?
                """, targetId) == metricId);
    }

    private UUID awaitOpenIncident(long targetId, String ruleId) {
        await("open incident " + ruleId, EVENT_TIMEOUT, () -> jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE database_config_id=? AND rule_id=? AND status='OPEN'
                """, Long.class, targetId, ruleId) == 1L);
        return jdbc.queryForObject("""
                SELECT incident_id FROM incidents
                WHERE database_config_id=? AND rule_id=? AND status='OPEN'
                """, UUID.class, targetId, ruleId);
    }

    private void seedUser() {
        if (userAccounts.findByEmail(EMAIL).isPresent()) {
            return;
        }
        userAccounts.saveAndFlush(UserAccount.builder()
                .email(EMAIL)
                .displayName("Part C Native QA")
                .passwordHash(passwordHashingService.hash(PASSWORD))
                .role(UserRole.USER)
                .enabled(true)
                .authVersion(1L)
                .build());
    }

    private LoginSession login() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        String csrf = csrf(client);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(httpUri("/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header("X-CSRF-Token", csrf)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("email", EMAIL, "password", PASSWORD))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String accessToken = objectMapper.readTree(response.body()).path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        return new LoginSession(client, accessToken);
    }

    private String csrf(HttpClient client) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(httpUri("/api/v1/auth/csrf"))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).path("csrfToken").asText();
    }

    private void logout(LoginSession login) throws Exception {
        String csrf = csrf(login.client());
        HttpResponse<String> response = login.client().send(
                HttpRequest.newBuilder(httpUri("/api/v1/auth/logout"))
                        .timeout(Duration.ofSeconds(10))
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("X-CSRF-Token", csrf)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(204);
    }

    private JsonNode authenticatedGet(LoginSession login, String path) throws Exception {
        HttpResponse<String> response = login.client().send(
                HttpRequest.newBuilder(httpUri(path))
                        .timeout(Duration.ofSeconds(10))
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private URI httpUri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Stage3StompClient.FrameQueue subscribe(
            Stage3StompClient.Connection connection,
            String id,
            String destination
    ) throws Exception {
        CompletableFuture<SubscriptionRegistration> expected = subscriptionObserver.expect(id);
        Stage3StompClient.FrameQueue frames = connection.subscribe(id, destination);
        SubscriptionRegistration actual = expected.get(EVENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(actual.destination()).isEqualTo(destination);
        return frames;
    }

    private JsonNode pollFrame(
            Stage3StompClient.FrameQueue frames,
            Duration timeout,
            Predicate<JsonNode> predicate
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remaining = Math.max(1L, deadline - System.nanoTime());
            byte[] payload = frames.poll(Duration.ofNanos(remaining));
            if (payload == null) {
                break;
            }
            JsonNode frame = objectMapper.readTree(payload);
            if (predicate.test(frame)) {
                return frame;
            }
        }
        throw new AssertionError("Timed out awaiting matching STOMP frame");
    }

    private long insertPushSubscription(long userId, UUID sessionId) {
        byte[] hash = new byte[32];
        byte[] nonce = new byte[12];
        byte[] ciphertext = new byte[32];
        Arrays.fill(hash, (byte) 0x31);
        Arrays.fill(nonce, (byte) 0x32);
        Arrays.fill(ciphertext, (byte) 0x33);
        return jdbc.queryForObject("""
                INSERT INTO push_subscriptions (
                    user_id, sid, endpoint_hash, payload_key_version, payload_nonce,
                    payload_ciphertext, expiration_time, enabled, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?, null, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, userId, sessionId, hash, nonce, ciphertext);
    }

    private long insertSlackWebhook(Instant createdAt) {
        long id = jdbc.queryForObject("""
                INSERT INTO notification_webhooks (
                    name, provider, url_key_version, url_nonce, url_ciphertext,
                    enabled, created_at, updated_at)
                VALUES ('native-qa', 'SLACK', 1,
                        decode(repeat('00', 12), 'hex'),
                        decode(repeat('11', 17), 'hex'),
                        true, ?, ?)
                RETURNING id
                """, Long.class, Timestamp.from(createdAt), Timestamp.from(createdAt));
        EncryptedValue encrypted = notificationSecrets.encryptSlack(id, SLACK_URL);
        jdbc.update("""
                UPDATE notification_webhooks
                SET url_key_version=?, url_nonce=?, url_ciphertext=?, updated_at=?
                WHERE id=?
                """, encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext(),
                Timestamp.from(createdAt), id);
        return id;
    }

    private long awaitScheduledSlackDelivery(UUID incidentId, long webhookId) {
        await("notification stream delivery", EVENT_TIMEOUT, () ->
                jdbc.queryForObject("""
                        SELECT count(*) FROM notification_deliveries
                        WHERE incident_id=? AND notification_webhook_id=?
                          AND channel='SLACK' AND status='PENDING'
                        """, Long.class, incidentId, webhookId) == 1L
                        && jdbc.queryForObject("""
                        SELECT count(*) FROM processed_events
                        WHERE stream='stream:incidents' AND consumer_group='cg:notification'
                        """, Long.class) >= 1L
                        && pendingNotificationRecords() == 0L);
        return jdbc.queryForObject("""
                SELECT id FROM notification_deliveries
                WHERE incident_id=? AND notification_webhook_id=?
                """, Long.class, incidentId, webhookId);
    }

    private UUID insertOpenIncident(
            long targetId,
            String databaseName,
            String ruleId,
            String ruleType,
            String severity,
            Instant openedAt
    ) {
        return insertOpenIncident(
                UUID.randomUUID(), targetId, databaseName, ruleId, ruleType, severity, openedAt);
    }

    private UUID insertOpenIncident(
            UUID incidentId,
            long targetId,
            String databaseName,
            String ruleId,
            String ruleType,
            String severity,
            Instant openedAt
    ) {
        jdbc.update("""
                INSERT INTO incidents (
                    incident_id, database_config_id, database_name, rule_id, rule_type,
                    severity, status, opened_at, last_observed_at, metric_name,
                    metric_value, threshold_value, message, incident_version)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, 'connectionStatus',
                        null, null, 'Native QA incident', 1)
                """, incidentId, targetId, databaseName, ruleId, ruleType, severity,
                Timestamp.from(openedAt), Timestamp.from(openedAt));
        return incidentId;
    }

    private long insertPendingPushDelivery(UUID incidentId, long pushId, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO notification_deliveries (
                    incident_id, incident_version, notification_type, channel,
                    push_subscription_id, status, attempt_count, next_attempt_at,
                    expires_at, created_at)
                VALUES (?, 1, 'INCIDENT_OPENED', 'WEB_PUSH', ?, 'PENDING', 0, ?, ?, ?)
                RETURNING id
                """, Long.class, incidentId, pushId, Timestamp.from(now),
                Timestamp.from(now.plusSeconds(600)), Timestamp.from(now));
    }

    private long insertPendingSlackDelivery(UUID incidentId, long webhookId, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO notification_deliveries (
                    incident_id, incident_version, notification_type, channel,
                    notification_webhook_id, status, attempt_count, next_attempt_at,
                    expires_at, created_at)
                VALUES (?, 1, 'INCIDENT_OPENED', 'SLACK', ?, 'PENDING', 0, ?, ?, ?)
                RETURNING id
                """, Long.class, incidentId, webhookId, Timestamp.from(now),
                Timestamp.from(now.plusSeconds(600)), Timestamp.from(now));
    }

    private void assertRetentionBoundaries(long pushId, long targetId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID oldIncident = insertResolvedIncident(targetId, now.minus(181, ChronoUnit.DAYS));
        UUID deliveryParent = insertOpenIncident(
                targetId, "native-restart", "CONNECTION_RATIO", "CONNECTION_RATIO_EXCEEDED", "FATAL",
                now.minus(40, ChronoUnit.DAYS));
        UUID exactIncident = insertResolvedIncident(targetId, now.minus(180, ChronoUnit.DAYS));
        UUID oldOpenIncident = insertOpenIncident(
                targetId, "native-restart", "SLOW_QUERY_RATE", "SLOW_QUERIES_HIGH", "CRITICAL",
                now.minus(181, ChronoUnit.DAYS));
        long oldDelivery = insertCancelledDelivery(
                oldIncident, pushId, now.minus(30, ChronoUnit.DAYS).minusMillis(1));
        long exactDelivery = insertCancelledDelivery(
                deliveryParent, pushId, now.minus(30, ChronoUnit.DAYS));

        PartCRetentionService.CleanupResult result = retentionService.purge(now);
        assertThat(result.deliveries()).isGreaterThanOrEqualTo(1);
        assertThat(result.incidents()).isGreaterThanOrEqualTo(1);
        assertThat(rowExists("notification_deliveries", "id", oldDelivery)).isFalse();
        assertThat(rowExists("incidents", "incident_id", oldIncident)).isFalse();
        assertThat(rowExists("notification_deliveries", "id", exactDelivery)).isTrue();
        assertThat(rowExists("incidents", "incident_id", exactIncident)).isTrue();
        assertThat(rowExists("incidents", "incident_id", oldOpenIncident)).isTrue();
    }

    private UUID insertResolvedIncident(long targetId, Instant resolvedAt) {
        UUID incidentId = UUID.randomUUID();
        Instant openedAt = resolvedAt.minusSeconds(60);
        jdbc.update("""
                INSERT INTO incidents (
                    incident_id, database_config_id, database_name, rule_id, rule_type,
                    severity, status, opened_at, last_observed_at, resolved_at,
                    resolution_reason, metric_name, metric_value, threshold_value,
                    message, incident_version)
                VALUES (?, ?, 'native-restart', 'CONNECTION_FAILURE', 'CONNECTION_FAILURE',
                        'FATAL', 'RESOLVED', ?, ?, ?, 'RECOVERED', 'connectionStatus',
                        null, null, 'Native QA resolved incident', 2)
                """, incidentId, targetId, Timestamp.from(openedAt), Timestamp.from(resolvedAt),
                Timestamp.from(resolvedAt));
        return incidentId;
    }

    private long insertCancelledDelivery(UUID incidentId, long pushId, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO notification_deliveries (
                    incident_id, incident_version, notification_type, channel,
                    push_subscription_id, status, attempt_count, next_attempt_at,
                    expires_at, created_at)
                VALUES (?, (SELECT incident_version FROM incidents WHERE incident_id=?),
                        'INCIDENT_OPENED', 'WEB_PUSH', ?, 'CANCELLED', 1, null, ?, ?)
                RETURNING id
                """, Long.class, incidentId, incidentId, pushId,
                Timestamp.from(createdAt.plusSeconds(600)), Timestamp.from(createdAt));
    }

    private NotificationDeliveryWorker nativeDeliveryWorker(
            SlackSender sender,
            SlackWebhookPolicy policy,
            SlackAttemptPacer pacer
    ) {
        return nativeDeliveryWorker(
                track(new PostgresDeliveryLease(dataSource)), sender, policy, pacer);
    }

    private NotificationDeliveryWorker nativeDeliveryWorker(
            PostgresDeliveryLease lease,
            SlackSender sender,
            SlackWebhookPolicy policy,
            SlackAttemptPacer pacer
    ) {
        return new NotificationDeliveryWorker(
                lease,
                deliveryTransaction,
                notificationSecrets,
                webPushSender,
                sender,
                policy,
                pacer,
                clock);
    }

    private LocalSlackClient localSlackClient(LocalTlsProviderFixture fixture, int tlsPort)
            throws Exception {
        URI fixtureUri = URI.create(
                "https://hooks.slack.com:" + tlsPort + "/services/T000/B000/native");
        SlackWebhookPolicy policy = mock(SlackWebhookPolicy.class);
        when(policy.validate(anyString())).thenReturn(fixtureUri);
        when(policy.canonicalIdentity(anyString())).thenReturn(SLACK_URL);
        HostResolver resolver = host -> {
            assertThat(host).isEqualToIgnoringCase("hooks.slack.com");
            return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
        };
        PublicAddressPolicy addresses = mock(PublicAddressPolicy.class);
        when(addresses.requirePublic(anyString(), any(InetAddress[].class)))
                .thenAnswer(invocation -> ((InetAddress[]) invocation.getArgument(1)).clone());
        HostResolutionExecutor resolutionExecutor = new HostResolutionExecutor();
        PinnedHttpsTransport transport = new PinnedHttpsTransport(
                mock(PushEndpointPolicy.class),
                policy,
                resolver,
                resolutionExecutor,
                addresses,
                new ApachePinnedHttpExecutor(fixture.clientContext()));
        SlackSender sender = new SlackSender(
                policy,
                new SlackPayloadRenderer(
                        objectMapper,
                        new SlackIncidentLinkFactory(ORIGIN)),
                transport,
                clock);
        return new LocalSlackClient(policy, sender, resolutionExecutor);
    }

    private Map<String, Object> deliveryState(long deliveryId) {
        Map<String, Object> values = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT status, attempt_count, next_attempt_at, expires_at,
                       created_at, sent_at, last_error_code
                FROM notification_deliveries WHERE id=?
                """, deliveryId));
        for (String field : List.of("next_attempt_at", "expires_at", "created_at", "sent_at")) {
            if (values.get(field) instanceof Timestamp timestamp) {
                values.put(field, timestamp.toInstant());
            }
        }
        return values;
    }

    private int requiredPort(String name) {
        String configured = System.getenv(name);
        if (configured == null || !configured.matches("[0-9]{1,5}")) {
            throw new IllegalStateException(name + " must be a TCP port");
        }
        int parsed = Integer.parseInt(configured);
        if (parsed < 1 || parsed > 65_535) {
            throw new IllegalStateException(name + " must be a TCP port");
        }
        return parsed;
    }

    private boolean rowExists(String table, String key, Object value) {
        return jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + table + " WHERE " + key + "=?)",
                Boolean.class,
                value);
    }

    private void stopWorker(String name) {
        if (context.containsBean(name)) {
            context.getBean(name, SmartLifecycle.class).stop();
        }
    }

    private void startWorker(String name) {
        if (context.containsBean(name)) {
            context.getBean(name, SmartLifecycle.class).start();
        }
    }

    private long pendingRiskRecords() {
        try {
            return redis.xpending(METRIC_STREAM, RISK_GROUP).getCount();
        } catch (RedisCommandExecutionException noGroupYet) {
            return -1L;
        }
    }

    private long pendingNotificationRecords() {
        try {
            return redis.xpending("stream:incidents", "cg:notification").getCount();
        } catch (RedisCommandExecutionException noGroupYet) {
            return -1L;
        }
    }

    private long pendingHeartbeatRecords() {
        try {
            return redis.xpending(HEARTBEAT_STREAM, RISK_GROUP).getCount();
        } catch (RedisCommandExecutionException noGroupYet) {
            return -1L;
        }
    }

    private Long nullableLong(String sql, Object argument) {
        List<Long> values = jdbc.query(sql, (row, ignored) -> {
            long value = row.getLong(1);
            return row.wasNull() ? null : value;
        }, argument);
        return values.isEmpty() ? null : values.get(0);
    }

    private String singleString(String sql, Object argument) {
        return jdbc.queryForObject(sql, String.class, argument);
    }

    private Instant singleInstant(String sql, Object argument) {
        Timestamp value = jdbc.queryForObject(sql, Timestamp.class, argument);
        return value == null ? null : value.toInstant();
    }

    private void await(String label, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
                lastFailure = null;
            } catch (RuntimeException notReady) {
                lastFailure = notReady;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting " + label, interrupted);
            }
        }
        throw new AssertionError("Timed out awaiting " + label, lastFailure);
    }

    private <T extends AutoCloseable> T track(T closeable) {
        closeables.add(closeable);
        return closeable;
    }

    private record PublishedMetric(UUID eventId, String json, long metricId) { }

    private record LoginSession(HttpClient client, String accessToken) { }

    private record LocalSlackClient(
            SlackWebhookPolicy policy,
            SlackSender sender,
            HostResolutionExecutor resolutionExecutor
    ) implements AutoCloseable {
        @Override
        public void close() {
            resolutionExecutor.close();
        }
    }

    private record SubscriptionRegistration(String subscriptionId, String sessionId, String destination) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObserverConfiguration {
        @Bean
        SubscriptionObserver partCNativeSubscriptionObserver() {
            return new SubscriptionObserver();
        }
    }

    static final class SubscriptionObserver implements ApplicationListener<SessionSubscribeEvent> {
        private final Map<String, CompletableFuture<SubscriptionRegistration>> expectations =
                new ConcurrentHashMap<>();

        CompletableFuture<SubscriptionRegistration> expect(String subscriptionId) {
            CompletableFuture<SubscriptionRegistration> expected = new CompletableFuture<>();
            if (expectations.putIfAbsent(subscriptionId, expected) != null) {
                throw new IllegalStateException("Duplicate subscription id: " + subscriptionId);
            }
            return expected;
        }

        @Override
        public void onApplicationEvent(SessionSubscribeEvent event) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
            CompletableFuture<SubscriptionRegistration> expected =
                    expectations.remove(accessor.getSubscriptionId());
            if (expected != null) {
                expected.complete(new SubscriptionRegistration(
                        accessor.getSubscriptionId(), accessor.getSessionId(), accessor.getDestination()));
            }
        }

        void clear() {
            expectations.values().forEach(future -> future.completeExceptionally(
                    new IllegalStateException("Scenario ended before SUBSCRIBE")));
            expectations.clear();
        }
    }
}
