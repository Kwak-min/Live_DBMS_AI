package com.example.monitoring.realtime.integration;

import com.example.monitoring.MonitoringApplication;
import com.example.monitoring.auth.domain.AuthSession;
import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.repository.AuthSessionRepository;
import com.example.monitoring.auth.repository.UserAccountRepository;
import com.example.monitoring.auth.service.AccessTokenService;
import com.example.monitoring.auth.service.JwtKeySet;
import com.example.monitoring.auth.service.PasswordHashingService;
import com.example.monitoring.database.security.DatabaseCredentialCrypto;
import com.example.monitoring.database.security.EncryptedValue;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.realtime.event.MetricBroadcastPort;
import com.example.monitoring.realtime.event.RealtimeMetricMessage;
import com.example.monitoring.realtime.redis.RedisMetricConsumer;
import com.example.monitoring.realtime.stomp.StompSessionRegistry;
import com.example.monitoring.repository.DatabaseConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.lettuce.core.Consumer;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@EnabledIfEnvironmentVariable(named = "STAGE3_INTEGRATION_ENABLED", matches = "true")
@SpringBootTest(
        classes = MonitoringApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.address=127.0.0.1",
                "server.port=${STAGE3_APP_PORT:18093}",
                "app.collector.enabled=false",
                "app.metrics.retention-cleanup-enabled=false",
                "app.part-b.retention-cleanup-enabled=false",
                "app.outbox.publisher-enabled=false",
                "app.outbox.retention-cleanup-enabled=false",
                "monitoring.realtime.enabled=true",
                "monitoring.realtime.reclaim-min-idle=1ms",
                "monitoring.realtime.reclaim-interval=100ms",
                "app.redis.stream-key=${STAGE3_STREAM_KEY:stream:stage3-integration}",
                "monitoring.realtime.dead-letter-stream=${STAGE3_DEAD_LETTER_STREAM:stream:stage3-integration:dead-letter}"
        })
@Import(RealtimeStage3IntegrationTest.ObserverConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealtimeStage3IntegrationTest {

    private static final String STREAM = System.getenv().getOrDefault(
            "STAGE3_STREAM_KEY", "stream:stage3-integration");
    private static final String GROUP = "cg:realtime";
    private static final String DLQ = System.getenv().getOrDefault(
            "STAGE3_DEAD_LETTER_STREAM", "stream:stage3-integration:dead-letter");
    private static final String ORIGIN = "http://localhost:5173";
    private static final String EMAIL = "stage3.integration@example.test";
    private static final String PASSWORD = System.getenv().getOrDefault(
            "STAGE3_TEST_PASSWORD", "stage3-local-fixture-password");
    private static final String DLQ_SECRET = System.getenv().getOrDefault(
            "STAGE3_DLQ_SECRET", "stage3-dlq-sentinel");
    private static final long ACTIVE_TARGET_ID = 12L;
    private static final long DISABLED_TARGET_ID = 13L;
    private static final long DELETED_TARGET_ID = 14L;
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration NO_EVENT_WINDOW = Duration.ofMillis(500);

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LettuceConnectionFactory redisConnectionFactory;

    @Autowired
    private RedisMetricConsumer consumer;

    @Autowired
    private StompSessionRegistry sessionRegistry;

    @Autowired
    private SubscriptionObserver subscriptionObserver;

    @Autowired
    private UserAccountRepository userAccounts;

    @Autowired
    private AuthSessionRepository authSessions;

    @Autowired
    private PasswordHashingService passwordHashingService;

    @Autowired
    private DatabaseConfigRepository databaseConfigs;

    @Autowired
    private DatabaseCredentialCrypto credentialCrypto;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private JwtKeySet jwtKeySet;

    @SpyBean
    private MetricBroadcastPort broadcastPort;

    private StatefulRedisConnection<String, String> redisConnection;
    private RedisCommands<String, String> redis;
    private Stage3StompClient stompClient;
    private Stage3RawWebSocketClient rawClient;
    private JsonNode contractFixtures;
    private JsonNode canonicalInput;
    private JsonNode canonicalOutput;
    private final List<AutoCloseable> openConnections = new ArrayList<>();

    @BeforeAll
    void initializeSuite() throws Exception {
        consumer.stop();
        RedisClient nativeClient = (RedisClient) redisConnectionFactory.getRequiredNativeClient();
        redisConnection = nativeClient.connect(StringCodec.UTF8);
        redis = redisConnection.sync();
        clearRedisKeys();

        contractFixtures = readContractFixtures().path("fixtures");
        canonicalInput = readResource("realtime/metric-collected-v1.json");
        canonicalOutput = readResource("realtime/metric-updated-v1.json");

        seedTargets();
        seedUser();
        stompClient = new Stage3StompClient();
        rawClient = new Stage3RawWebSocketClient();
    }

    @AfterAll
    void closeSuite() throws Exception {
        consumer.stop();
        closeConnections();
        if (stompClient != null) {
            stompClient.close();
        }
        if (redis != null) {
            clearRedisKeys();
        }
        if (redisConnection != null) {
            redisConnection.close();
        }
    }

    @BeforeEach
    void isolateScenario() throws Exception {
        consumer.stop();
        closeConnections();
        restoreProcessedEventsTable();
        await("STOMP session cleanup", Duration.ofSeconds(7),
                () -> sessionRegistry.authenticatedSessionCount() == 0
                        && sessionRegistry.pendingSessionCount() == 0);
        clearRedisKeys();
        jdbc.update("DELETE FROM processed_events");
        reset(broadcastPort);
        subscriptionObserver.clear();
    }

    @AfterEach
    void cleanScenario() throws Exception {
        consumer.stop();
        closeConnections();
        restoreProcessedEventsTable();
        await("STOMP session cleanup", Duration.ofSeconds(7),
                () -> sessionRegistry.authenticatedSessionCount() == 0
                        && sessionRegistry.pendingSessionCount() == 0);
        subscriptionObserver.clear();
    }

    @Test
    @Order(1)
    void fixtureAndSchemaPrerequisiteAreExplicit() {
        List<String> versions = jdbc.queryForList("""
                SELECT version FROM flyway_schema_history
                WHERE success = true AND version IS NOT NULL
                ORDER BY installed_rank
                """, String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");

        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'processed_events'
                ORDER BY ordinal_position
                """);
        assertThat(columns).extracting(row -> row.get("column_name"))
                .containsExactly("stream", "consumer_group", "event_id", "processed_at");
        assertThat(columns).extracting(row -> row.get("data_type"))
                .containsExactly("character varying", "character varying", "uuid", "timestamp with time zone");
        assertThat(columns).extracting(row -> row.get("is_nullable"))
                .containsOnly("NO");
        assertThat(columns).extracting(row -> row.get("character_maximum_length"))
                .containsExactly(128, 128, null, null);
        Integer primaryKeyColumns = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.key_column_usage usage
                JOIN information_schema.table_constraints constraint_info
                  ON constraint_info.constraint_name = usage.constraint_name
                 AND constraint_info.constraint_schema = usage.constraint_schema
                WHERE usage.table_schema = current_schema()
                  AND usage.table_name = 'processed_events'
                  AND constraint_info.constraint_type = 'PRIMARY KEY'
                  AND usage.column_name IN ('stream', 'consumer_group', 'event_id')
                """, Integer.class);
        assertThat(primaryKeyColumns).isEqualTo(3);

        List<String> outboxColumns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'event_outbox'
                ORDER BY ordinal_position
                """, String.class);
        assertThat(outboxColumns).containsExactly(
                "event_id", "seq", "event_type", "stream_key", "ordering_key", "payload",
                "created_at", "published_at", "attempts", "next_attempt_at", "last_error");

        assertThat(canonicalInput).isEqualTo(contractFixtures.path("metricCollectedEvent"));
        assertThat(canonicalOutput).isEqualTo(expectedMetricUpdated(canonicalInput));
    }

    @Test
    @Order(2)
    void duplicateRedisIdsPublishOneExactFrameAndAckBoth() throws Exception {
        LoginSession login = login();
        Stage3StompClient.Connection connection = connect(login.accessToken());
        RegisteredFrames metrics = subscribe(connection, "metrics-duplicate",
                "/topic/databases/12/metrics");

        startConsumer();
        String firstRedisId = addPayload(canonicalInput);
        String secondRedisId = addPayload(canonicalInput);
        assertThat(secondRedisId).isNotEqualTo(firstRedisId);

        JsonNode actual = readFrame(metrics.frames().poll(EVENT_TIMEOUT));
        assertThat(actual).isEqualTo(canonicalOutput);
        assertThat(actual.path("eventId")).isEqualTo(canonicalInput.path("eventId"));
        assertThat(actual.path("publishedAt")).isEqualTo(canonicalInput.path("publishedAt"));
        assertThat(actual.at("/data/id")).isEqualTo(canonicalInput.path("metricId"));
        await("duplicate ACK and dedupe row", EVENT_TIMEOUT,
                () -> processedRows() == 1 && pendingCount() == 0);

        assertThat(redis.xlen(STREAM)).isEqualTo(2);
        assertThat(metrics.frames().poll(NO_EVENT_WINDOW)).isNull();
        verify(broadcastPort, times(1)).publish(any(RealtimeMetricMessage.class));
    }

    @Test
    @Order(3)
    void measuredZeroAndPartialFailurePreserveStatusNullsAndSourceTimes() throws Exception {
        LoginSession login = login();
        Stage3StompClient.Connection connection = connect(login.accessToken());
        RegisteredFrames metrics = subscribe(connection, "metrics-values",
                "/topic/databases/12/metrics");
        ObjectNode zero = (ObjectNode) contractFixtures.path("metricMeasuredZeroEvent").deepCopy();
        ObjectNode partial = (ObjectNode) contractFixtures.path("metricPartialFailureEvent").deepCopy();
        partial.putNull("lastSuccessAt");

        startConsumer();
        addPayload(zero);
        addPayload(partial);

        JsonNode actualZero = readFrame(metrics.frames().poll(EVENT_TIMEOUT));
        JsonNode actualPartial = readFrame(metrics.frames().poll(EVENT_TIMEOUT));
        assertThat(actualZero).isEqualTo(expectedMetricUpdated(zero));
        assertThat(actualPartial).isEqualTo(expectedMetricUpdated(partial));
        assertThat(actualZero.at("/data/activeConnections").longValue()).isZero();
        assertThat(actualZero.at("/data/qps").doubleValue()).isZero();
        assertThat(actualZero.at("/data/cpuUsage").isNull()).isTrue();
        assertThat(actualZero.at("/data/errorCode").isNull()).isTrue();
        assertThat(actualPartial.at("/data/lastSuccessAt").isNull()).isTrue();
        assertThat(actualPartial.at("/data/qps").isNull()).isTrue();
        assertThat(actualPartial.at("/data/collectionStatus").textValue())
                .isEqualTo("PARTIAL_FAILURE");
        assertThat(actualPartial.at("/data/timestamp").textValue())
                .isEqualTo("2026-09-28T03:00:25.000Z");
        await("zero/partial ACKs", EVENT_TIMEOUT,
                () -> processedRows() == 2 && pendingCount() == 0);
    }

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void restartSuppressesReplayAndDeadConsumerEntryIsAutoClaimed() throws Exception {
        LoginSession login = login();
        Stage3StompClient.Connection connection = connect(login.accessToken());
        RegisteredFrames metrics = subscribe(connection, "metrics-replay",
                "/topic/databases/12/metrics");

        startConsumer();
        addPayload(canonicalInput);
        assertThat(readFrame(metrics.frames().poll(EVENT_TIMEOUT))).isEqualTo(canonicalOutput);
        await("first ACK", EVENT_TIMEOUT, () -> pendingCount() == 0 && processedRows() == 1);
        consumer.stop();

        addPayload(canonicalInput);
        addPayload("{restart-boundary");
        startConsumer();
        await("restart duplicate ACK", EVENT_TIMEOUT,
                () -> pendingCount() == 0 && redis.xlen(DLQ) == 1);
        assertThat(metrics.frames().poll(NO_EVENT_WINDOW)).isNull();
        assertThat(processedRows()).isOne();
        consumer.stop();

        JsonNode claimedPayload = contractFixtures.path("metricMeasuredZeroEvent");
        addPayload(claimedPayload);
        List<StreamMessage<String, String>> delivered = redis.xreadgroup(
                Consumer.from(GROUP, "dead-stage3-consumer"),
                XReadArgs.StreamOffset.lastConsumed(STREAM));
        assertThat(delivered).hasSize(1);
        long deliveredAt = System.nanoTime();
        await("pending entry reaches reclaim idle", Duration.ofSeconds(1),
                () -> pendingCount() == 1
                        && System.nanoTime() - deliveredAt >= Duration.ofMillis(5).toNanos());

        startConsumer();
        assertThat(readFrame(metrics.frames().poll(EVENT_TIMEOUT)))
                .isEqualTo(expectedMetricUpdated(claimedPayload));
        await("XAUTOCLAIM ACK", EVENT_TIMEOUT,
                () -> pendingCount() == 0 && processedRows() == 2);
        assertThat(redis.xlen(STREAM)).isEqualTo(4);
        verify(broadcastPort, times(2)).publish(any(RealtimeMetricMessage.class));
    }

    @Test
    @Order(5)
    void malformedInputsAreSanitizedInDlqAndValidInputContinues() throws Exception {
        LoginSession login = login();
        Stage3StompClient.Connection connection = connect(login.accessToken());
        RegisteredFrames metrics = subscribe(connection, "metrics-continuity",
                "/topic/databases/12/metrics");
        ObjectNode version = (ObjectNode) canonicalInput.deepCopy();
        version.put("schemaVersion", 2);
        ObjectNode missing = (ObjectNode) canonicalInput.deepCopy();
        missing.remove("publishedAt");
        ObjectNode invalidEnum = (ObjectNode) canonicalInput.deepCopy();
        invalidEnum.put("collectionStatus", "BROKEN");

        startConsumer();
        addPayload("{\"password\":\"" + DLQ_SECRET + "\",\"event\":");
        addPayload(version);
        addPayload(missing);
        addPayload(invalidEnum);
        addPayload(canonicalInput);

        assertThat(readFrame(metrics.frames().poll(EVENT_TIMEOUT))).isEqualTo(canonicalOutput);
        await("DLQ continuity", EVENT_TIMEOUT,
                () -> redis.xlen(DLQ) == 4 && pendingCount() == 0 && processedRows() == 1);

        Set<String> reasonCodes = new HashSet<>();
        List<StreamMessage<String, String>> deadLetters = redis.xrange(DLQ, Range.unbounded());
        assertThat(deadLetters).hasSize(4);
        for (StreamMessage<String, String> deadLetter : deadLetters) {
            JsonNode body = objectMapper.readTree(deadLetter.getBody().get("payload"));
            reasonCodes.add(body.path("reasonCode").textValue());
            assertThat(body.path("attemptCount").intValue()).isOne();
            assertThat(body.path("payload").textValue()).doesNotContain(DLQ_SECRET);
        }
        assertThat(reasonCodes).containsExactlyInAnyOrder(
                "INVALID_JSON", "UNSUPPORTED_SCHEMA_VERSION",
                "MISSING_REQUIRED_FIELD", "INVALID_ENUM_VALUE");
        assertThat(deadLetters.get(0).getBody().get("payload"))
                .contains("[UNPARSEABLE_JSON]")
                .doesNotContain(DLQ_SECRET);
    }

    @Test
    @Order(6)
    void brokerDatabaseAndDlqFailuresDoNotAckBeforeHandoff() throws Exception {
        LoginSession login = login();
        Stage3StompClient.Connection connection = connect(login.accessToken());
        RegisteredFrames metrics = subscribe(connection, "metrics-failure",
                "/topic/databases/12/metrics");

        doThrow(new IllegalStateException("stage3 broker probe"))
                .doCallRealMethod()
                .when(broadcastPort).publish(any(RealtimeMetricMessage.class));
        startConsumer();
        addPayload(canonicalInput);
        await("broker failure remains pending", Duration.ofMillis(900),
                () -> pendingCount() == 1 && processedRows() == 0);
        assertThat(readFrame(metrics.frames().poll(EVENT_TIMEOUT))).isEqualTo(canonicalOutput);
        await("broker retry ACK", EVENT_TIMEOUT,
                () -> pendingCount() == 0 && processedRows() == 1);
        verify(broadcastPort, times(2)).publish(any(RealtimeMetricMessage.class));

        resetRuntimeData();
        redis.set(DLQ, "wrong-type");
        startConsumer();
        addPayload("{not-json");
        await("DLQ failure remains pending", EVENT_TIMEOUT, () -> pendingCount() == 1);
        assertThat(processedRows()).isZero();
        verifyNoInteractions(broadcastPort);
        consumer.stop();

        resetRuntimeData();
        startConsumer();
        jdbc.execute("ALTER TABLE processed_events RENAME TO processed_events_stage3_missing");
        try {
            addPayload(canonicalInput);
            await("database failure remains pending", EVENT_TIMEOUT, () -> pendingCount() == 1);
            assertThat(rowCount("processed_events_stage3_missing")).isZero();
            verifyNoInteractions(broadcastPort);
            assertThatThrownBy(() -> {
                consumer.stop();
                consumer.start();
            }).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires A V3 table processed_events");
        } finally {
            consumer.stop();
            restoreProcessedEventsTable();
        }
        startConsumer();
        assertThat(readFrame(metrics.frames().poll(EVENT_TIMEOUT))).isEqualTo(canonicalOutput);
        await("database recovery ACK", EVENT_TIMEOUT,
                () -> pendingCount() == 0 && processedRows() == 1);
    }

    @Test
    @Order(7)
    void handshakeAndConnectRejectUntrustedOrAlternateCredentials() throws Exception {
        LoginSession login = login();

        URI endpoint = URI.create("ws://127.0.0.1:" + port + "/ws");
        assertHandshakeRejected(stompClient.begin(endpoint, null, login.accessToken(), headers -> { }));
        assertHandshakeRejected(stompClient.begin(endpoint, "http://evil.example", login.accessToken(), headers -> { }));
        assertHandshakeRejected(stompClient.begin(
                URI.create(endpoint + "?access_token=forbidden"), ORIGIN, login.accessToken(), headers -> { }));

        assertProtocolError(stompClient.begin(endpoint, ORIGIN, null, headers -> { }), "AUTH_REQUIRED");
        assertProtocolError(stompClient.begin(endpoint, ORIGIN, null,
                handshake -> handshake.add(HttpHeaders.COOKIE, "accessToken=" + login.accessToken()),
                headers -> { }), "AUTH_REQUIRED");
        assertRawConnectError("TAMPERED_CONNECT", tamper(login.accessToken()), "INVALID_TOKEN");
        assertProtocolError(stompClient.begin(endpoint, ORIGIN,
                signedToken(login.accessToken(), Instant.now().minusSeconds(1)).value(), headers -> { }),
                "ACCESS_TOKEN_EXPIRED");
        assertProtocolError(stompClient.begin(endpoint, ORIGIN, login.accessToken(),
                headers -> headers.add("login", "forbidden")), "VALIDATION_ERROR");
        assertProtocolError(stompClient.begin(endpoint, ORIGIN, login.accessToken(),
                headers -> headers.add("passcode", "forbidden")), "VALIDATION_ERROR");

        Instant openedAt = Instant.now();
        Stage3RawWebSocketClient.Connection noConnect = track(rawClient.open(port, ORIGIN));
        assertThat(noConnect.closed(Duration.ofSeconds(7))).isNotNull();
        long connectDeadlineMillis = Duration.between(openedAt, Instant.now()).toMillis();
        assertThat(connectDeadlineMillis).isBetween(4_000L, 6_500L);
        String deadlineError = noConnect.message(Duration.ofMillis(100));
        assertThat(deadlineError).startsWith("ERROR").contains("AUTH_REQUIRED");
        System.out.println("STAGE3_CONNECT_DEADLINE_MS=" + connectDeadlineMillis);

        await("rejected connections close", Duration.ofSeconds(7),
                () -> sessionRegistry.authenticatedSessionCount() == 0
                        && sessionRegistry.pendingSessionCount() == 0);
    }

    @Test
    @Order(8)
    void subscriptionErrorsStayOnTheCurrentSocketAndRespectTargetLifecycle() throws Exception {
        LoginSession login = login();
        Stage3StompClient.Connection first = connect(login.accessToken());
        Stage3StompClient.Connection second = connect(login.accessToken());
        RegisteredFrames firstErrors = subscribe(first, "errors-first", "/user/queue/errors");
        RegisteredFrames secondErrors = subscribe(second, "errors-second", "/user/queue/errors");

        first.subscribe("wildcard", "/topic/databases/*/metrics");
        assertSubscriptionError(firstErrors.frames(), "VALIDATION_ERROR", "wildcard");
        assertThat(secondErrors.frames().poll(NO_EVENT_WINDOW)).isNull();

        first.subscribe("missing-target", "/topic/databases/9999/metrics");
        assertSubscriptionError(firstErrors.frames(), "DATABASE_NOT_FOUND", "missing-target");
        assertThat(secondErrors.frames().poll(NO_EVENT_WINDOW)).isNull();

        RegisteredFrames disabled = subscribe(first, "disabled-target",
                "/topic/databases/" + DISABLED_TARGET_ID + "/metrics");
        assertThat(disabled.registration().destination())
                .isEqualTo("/topic/databases/13/metrics");
        first.subscribe("deleted-target", "/topic/databases/" + DELETED_TARGET_ID + "/metrics");
        assertSubscriptionError(firstErrors.frames(), "DATABASE_NOT_FOUND", "deleted-target");

        Stage3RawWebSocketClient.Connection withoutErrors =
                track(rawClient.connect(port, ORIGIN, login.accessToken()));
        long invalidSubscribeStarted = System.nanoTime();
        withoutErrors.subscribe("no-errors", "/topic/databases/*/metrics");
        assertRawErrorThenClose("INVALID_SUBSCRIBE", withoutErrors,
                invalidSubscribeStarted, "VALIDATION_ERROR");
        assertThat(first.connected()).isTrue();
        assertThat(second.connected()).isTrue();
        assertThat(sessionRegistry.subscriptionCount(firstErrors.registration().sessionId())).isEqualTo(2);
    }

    @Test
    @Order(9)
    void socketSubscriptionSendAndFrameLimitsAreEnforced() throws Exception {
        LoginSession login = login();
        List<Stage3StompClient.Connection> five = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            five.add(connect(login.accessToken()));
        }
        assertThat(sessionRegistry.authenticatedSessionCount()).isEqualTo(5);
        assertRawConnectError("SIXTH_CONNECT", login.accessToken(), "FORBIDDEN");

        five.get(0).close();
        await("socket slot release", Duration.ofSeconds(5),
                () -> sessionRegistry.authenticatedSessionCount() == 4);
        Stage3StompClient.Connection replacement = connect(login.accessToken());
        RegisteredFrames errors = subscribe(replacement, "limit-errors", "/user/queue/errors");
        for (int index = 1; index <= 60; index++) {
            subscribe(replacement, "limit-" + index, "/topic/databases/12/metrics");
        }
        await("subscription limit registrations", EVENT_TIMEOUT,
                () -> sessionRegistry.subscriptionCount(errors.registration().sessionId()) == 61);
        assertThat(sessionRegistry.subscriptionCount(errors.registration().sessionId())).isEqualTo(61);
        replacement.subscribe("limit-61", "/topic/databases/12/metrics");
        assertSubscriptionError(errors.frames(), "VALIDATION_ERROR", "limit-61");
        assertThat(replacement.connected()).isTrue();
        assertThat(sessionRegistry.subscriptionCount(errors.registration().sessionId())).isEqualTo(61);

        closeConnections();
        await("limit sockets close", Duration.ofSeconds(7),
                () -> sessionRegistry.authenticatedSessionCount() == 0);

        Stage3StompClient.Connection sendProbe = connect(login.accessToken());
        sendProbe.send("/topic/databases/12/metrics", "{}".getBytes(StandardCharsets.UTF_8));
        assertErrorBody(sendProbe.protocolError(EVENT_TIMEOUT), "VALIDATION_ERROR");
        assertThat(sendProbe.terminal(EVENT_TIMEOUT)).isNotNull();
        await("SEND probe closes", Duration.ofSeconds(5),
                () -> sessionRegistry.authenticatedSessionCount() == 0);

        Stage3RawWebSocketClient.Connection exact = track(rawClient.connect(port, ORIGIN, login.accessToken()));
        byte[] exactFrame = Stage3RawWebSocketClient.forbiddenSendFrame(Stage3RawWebSocketClient.limit());
        assertThat(exactFrame).hasSize(64 * 1024);
        exact.send(exactFrame);
        Stage3RawWebSocketClient.ObservedMessage exactError = exact.observedMessage(EVENT_TIMEOUT);
        assertThat(exactError).isNotNull();
        assertThat(exactError.payload()).startsWith("ERROR").contains("VALIDATION_ERROR");
        Stage3RawWebSocketClient.ObservedClose exactClose = exact.observedClose(EVENT_TIMEOUT);
        assertThat(exactClose).isNotNull();
        assertThat(exactClose.observedAtNanos()).isGreaterThanOrEqualTo(exactError.observedAtNanos());
        System.out.println("STAGE3_EXACT_FRAME_BYTES=" + exactFrame.length
                + " FRAME_TYPE=" + exactError.transportType()
                + " ERROR_BEFORE_CLOSE=true CLOSE_CODE=" + exactClose.status().getCode()
                + " CLOSE_REASON=" + exactClose.status().getReason());
        await("exact frame socket closes", Duration.ofSeconds(5),
                () -> sessionRegistry.authenticatedSessionCount() == 0);

        Stage3RawWebSocketClient.Connection oversized = track(rawClient.connect(port, ORIGIN, login.accessToken()));
        byte[] oversizedFrame = Stage3RawWebSocketClient.forbiddenSendFrame(Stage3RawWebSocketClient.limit() + 1);
        assertThat(oversizedFrame).hasSize(64 * 1024 + 1);
        Exception oversizedSendFailure = null;
        try {
            oversized.send(oversizedFrame);
        }
        catch (Exception exception) {
            oversizedSendFailure = exception;
        }
        Stage3RawWebSocketClient.ObservedClose oversizedClose = oversized.observedClose(EVENT_TIMEOUT);
        assertThat(oversizedClose).isNotNull();
        assertThat(oversizedClose.status().getCode()).isEqualTo(1009);
        System.out.println("STAGE3_OVERSIZED_FRAME_BYTES=" + oversizedFrame.length
                + " SEND_FAILED=" + (oversizedSendFailure != null)
                + " SEND_FAILURE_TYPE=" + (oversizedSendFailure == null ? "none"
                : oversizedSendFailure.getClass().getSimpleName())
                + " CLOSE_CODE=" + oversizedClose.status().getCode()
                + " CLOSE_REASON=" + oversizedClose.status().getReason());
        await("oversized frame socket closes", Duration.ofSeconds(5),
                () -> sessionRegistry.authenticatedSessionCount() == 0);
    }

    @Test
    @Order(10)
    void accessTokenExpiryClosesAtDeadlineAndPreventsLaterMetricDelivery() throws Exception {
        LoginSession login = login();
        SignedToken expiring = signedToken(login.accessToken(), Instant.now().plusSeconds(4));
        Stage3StompClient.Connection connection = connect(expiring.value());
        RegisteredFrames metrics = subscribe(connection, "metrics-expiry",
                "/topic/databases/12/metrics");
        startConsumer();

        JsonNode error = assertErrorBody(connection.protocolError(Duration.ofSeconds(7)),
                "ACCESS_TOKEN_EXPIRED");
        assertThat(error.path("requestId").textValue()).isNotBlank();
        assertThat(connection.terminal(Duration.ofSeconds(5))).isNotNull();
        long expiryLagMillis = Duration.between(expiring.expiresAt(), Instant.now()).toMillis();
        assertThat(expiryLagMillis).isBetween(-250L, 2_000L);
        System.out.println("STAGE3_ACCESS_EXPIRY_LAG_MS=" + expiryLagMillis);

        addPayload(canonicalInput);
        await("post-expiry event ACK", EVENT_TIMEOUT,
                () -> processedRows() == 1 && pendingCount() == 0);
        assertThat(metrics.frames().poll(NO_EVENT_WINDOW)).isNull();
    }

    @Test
    @Order(11)
    void realHttpLogoutIsObservedWithinRevalidationWindowAndBlocksLaterDelivery() throws Exception {
        LoginSession login = login();
        AccessTokenService.VerifiedAccessToken claims = accessTokenService.verify(login.accessToken());
        Stage3StompClient.Connection connection = connect(login.accessToken());
        RegisteredFrames metrics = subscribe(connection, "metrics-logout",
                "/topic/databases/12/metrics");
        startConsumer();

        Instant logoutCompleted = logout(login);
        assertErrorBody(connection.protocolError(Duration.ofSeconds(12)), "SESSION_REVOKED");
        assertThat(connection.terminal(Duration.ofSeconds(5))).isNotNull();
        long revalidationMillis = Duration.between(logoutCompleted, Instant.now()).toMillis();
        assertThat(revalidationMillis).isLessThanOrEqualTo(10_500L);
        System.out.println("STAGE3_LOGOUT_REVALIDATION_MS=" + revalidationMillis);

        AuthSession revoked = authSessions.findById(claims.sessionId()).orElseThrow();
        assertThat(revoked.getRevokedAt()).isNotNull();
        addPayload(canonicalInput);
        await("post-logout event ACK", EVENT_TIMEOUT,
                () -> processedRows() == 1 && pendingCount() == 0);
        assertThat(metrics.frames().poll(NO_EVENT_WINDOW)).isNull();
    }

    private void seedTargets() {
        databaseConfigs.deleteAll();
        jdbc.execute("SELECT setval(pg_get_serial_sequence('database_configs', 'id'), 11, true)");
        DatabaseConfig active = databaseConfigs.saveAndFlush(target(ACTIVE_TARGET_ID, true, null));
        DatabaseConfig disabled = databaseConfigs.saveAndFlush(target(DISABLED_TARGET_ID, false, null));
        DatabaseConfig deleted = databaseConfigs.saveAndFlush(target(DELETED_TARGET_ID, true, LocalDateTime.now()));
        assertThat(active.getId()).isEqualTo(ACTIVE_TARGET_ID);
        assertThat(disabled.getId()).isEqualTo(DISABLED_TARGET_ID);
        assertThat(deleted.getId()).isEqualTo(DELETED_TARGET_ID);
    }

    private DatabaseConfig target(long id, boolean enabled, LocalDateTime deletedAt) {
        DatabaseConfig target = DatabaseConfig.builder()
                .name("stage3-target")
                .host("127.0.0.1")
                .port(3306)
                .databaseName("stage3")
                .status(TargetDbStatus.UNKNOWN)
                .collectionIntervalSeconds(5)
                .enabled(enabled)
                .configVersion(2L)
                .deletedAt(deletedAt)
                .build();
        EncryptedValue username = credentialCrypto.encrypt(id, "username", "native-fixture-user");
        EncryptedValue password = credentialCrypto.encrypt(id, "password", "native-fixture-password");
        target.storeEncryptedUsername(username.keyVersion(), username.nonce(), username.ciphertext());
        target.storeEncryptedPassword(password.keyVersion(), password.nonce(), password.ciphertext());
        return target;
    }

    private void seedUser() {
        if (userAccounts.findByEmail(EMAIL).isPresent()) {
            return;
        }
        userAccounts.saveAndFlush(UserAccount.builder()
                .email(EMAIL)
                .displayName("Stage 3 Integration")
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
        HttpRequest request = HttpRequest.newBuilder(httpUri("/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header("X-CSRF-Token", csrf)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("email", EMAIL, "password", PASSWORD))))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String token = objectMapper.readTree(response.body()).path("accessToken").textValue();
        assertThat(token).isNotBlank();
        accessTokenService.verify(token);
        return new LoginSession(client, token);
    }

    private String csrf(HttpClient client) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(httpUri("/api/v1/auth/csrf"))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String token = objectMapper.readTree(response.body()).path("csrfToken").textValue();
        assertThat(token).isNotBlank();
        return token;
    }

    private Instant logout(LoginSession login) throws Exception {
        String csrf = csrf(login.client());
        HttpRequest request = HttpRequest.newBuilder(httpUri("/api/v1/auth/logout"))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header("X-CSRF-Token", csrf)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = login.client().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(204);
        return Instant.now();
    }

    private URI httpUri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Stage3StompClient.Connection connect(String accessToken) throws Exception {
        Stage3StompClient.Connection connection = stompClient.begin(port, ORIGIN, accessToken).connected();
        return track(connection);
    }

    private RegisteredFrames subscribe(Stage3StompClient.Connection connection,
                                       String id, String destination) throws Exception {
        CompletableFuture<SubscriptionRegistration> expected = subscriptionObserver.expect(id);
        Stage3StompClient.FrameQueue frames = connection.subscribe(id, destination);
        SubscriptionRegistration registration = expected.get(EVENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(registration.destination()).isEqualTo(destination);
        return new RegisteredFrames(registration, frames);
    }

    private void startConsumer() {
        consumer.start();
        await("consumer group creation", EVENT_TIMEOUT, () -> pendingCount() >= 0);
    }

    private String addPayload(JsonNode payload) throws Exception {
        return addPayload(objectMapper.writeValueAsString(payload));
    }

    private String addPayload(String payload) {
        return redis.xadd(STREAM, Map.of("payload", payload));
    }

    private long pendingCount() {
        try {
            return redis.xpending(STREAM, GROUP).getCount();
        } catch (RuntimeException noGroupYet) {
            return -1;
        }
    }

    private int processedRows() {
        return rowCount("processed_events");
    }

    private int rowCount(String table) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private void clearRedisKeys() {
        redis.del(STREAM, DLQ);
    }

    private void resetRuntimeData() {
        consumer.stop();
        clearRedisKeys();
        jdbc.update("DELETE FROM processed_events");
        reset(broadcastPort);
    }

    private void restoreProcessedEventsTable() {
        Boolean originalExists = jdbc.queryForObject(
                "SELECT to_regclass('processed_events') IS NOT NULL", Boolean.class);
        Boolean renamedExists = jdbc.queryForObject(
                "SELECT to_regclass('processed_events_stage3_missing') IS NOT NULL", Boolean.class);
        if (Boolean.FALSE.equals(originalExists) && Boolean.TRUE.equals(renamedExists)) {
            jdbc.execute("ALTER TABLE processed_events_stage3_missing RENAME TO processed_events");
        }
    }

    private JsonNode readFrame(byte[] payload) throws IOException {
        assertThat(payload).isNotNull();
        return objectMapper.readTree(payload);
    }

    private JsonNode assertRawConnectError(String label, String token, String code) throws Exception {
        long started = System.nanoTime();
        Stage3RawWebSocketClient.Connection connection =
                track(rawClient.beginConnect(port, ORIGIN, token));
        return assertRawErrorThenClose(label, connection, started, code);
    }

    private JsonNode assertRawErrorThenClose(String label,
                                             Stage3RawWebSocketClient.Connection connection,
                                             long startedAtNanos,
                                             String code) throws Exception {
        Stage3RawWebSocketClient.ObservedMessage error = connection.observedMessage(EVENT_TIMEOUT);
        assertThat(error).as(label + " first frame").isNotNull();
        String frame = error.payload().replace("\r\n", "\n");
        assertThat(frame).startsWith("ERROR\n");
        int bodyStart = frame.indexOf("\n\n");
        int bodyEnd = frame.indexOf('\0', bodyStart + 2);
        assertThat(bodyStart).isGreaterThanOrEqualTo(0);
        if (bodyEnd < 0) {
            bodyEnd = frame.length();
        }
        JsonNode body = objectMapper.readTree(frame.substring(bodyStart + 2, bodyEnd));
        Stage3RawWebSocketClient.ObservedClose closed = connection.observedClose(EVENT_TIMEOUT);
        long errorMillis = Duration.ofNanos(error.observedAtNanos() - startedAtNanos).toMillis();
        long closeMillis = Duration.ofNanos(closed.observedAtNanos() - startedAtNanos).toMillis();
        System.out.println("STAGE3_RAW_" + label + "_CODE=" + body.path("code").textValue()
                + " FRAME_TYPE=" + error.transportType()
                + " ERROR_MS=" + errorMillis + " CLOSE_MS=" + closeMillis);
        assertThat(body.path("code").textValue()).isEqualTo(code);
        assertThat(body.path("message").textValue()).isNotBlank();
        assertThat(body.path("requestId").textValue()).isNotBlank();
        assertThat(errorMillis).isLessThan(4_000L);
        assertThat(closed.observedAtNanos()).isGreaterThanOrEqualTo(error.observedAtNanos());
        return body;
    }

    private JsonNode assertSubscriptionError(Stage3StompClient.FrameQueue errors,
                                             String code, String subscriptionId) throws Exception {
        JsonNode envelope = readFrame(errors.poll(EVENT_TIMEOUT));
        assertThat(envelope.path("eventType").textValue()).isEqualTo("Error");
        assertThat(envelope.at("/data/code").textValue()).isEqualTo(code);
        assertThat(envelope.at("/data/subscriptionId").textValue()).isEqualTo(subscriptionId);
        assertThat(envelope.at("/data/requestId").textValue()).isNotBlank();
        return envelope;
    }

    private JsonNode assertProtocolError(Stage3StompClient.ConnectAttempt attempt,
                                         String code) throws Exception {
        JsonNode error = assertErrorBody(attempt.protocolError(EVENT_TIMEOUT), code);
        assertThat(attempt.terminal(Duration.ofSeconds(7))).isNotNull();
        return error;
    }

    private JsonNode assertErrorBody(byte[] payload, String code) throws IOException {
        JsonNode body = readFrame(payload);
        assertThat(body.path("code").textValue()).isEqualTo(code);
        assertThat(body.path("message").textValue()).isNotBlank();
        assertThat(body.path("requestId").textValue()).isNotBlank();
        return body;
    }

    private void assertHandshakeRejected(Stage3StompClient.ConnectAttempt attempt) {
        assertThatThrownBy(attempt::connected).isInstanceOf(Exception.class);
    }

    private SignedToken signedToken(String actualToken, Instant expiresAt) throws Exception {
        AccessTokenService.VerifiedAccessToken verified = accessTokenService.verify(actualToken);
        expiresAt = expiresAt.truncatedTo(ChronoUnit.SECONDS);
        Instant issuedAt = expiresAt.minus(Duration.ofMinutes(15));
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("live-dbms-ai")
                .audience("live-dbms-web")
                .subject(Long.toString(verified.userId()))
                .claim("role", verified.role())
                .claim("sid", verified.sessionId().toString())
                .claim("ver", verified.authVersion())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(jwtKeySet.activeKeyId()).build(), claims);
        jwt.sign(new MACSigner(jwtKeySet.activeKey()));
        return new SignedToken(jwt.serialize(), expiresAt);
    }

    private String tamper(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char firstSignatureCharacter = token.charAt(signatureStart);
        char replacement = firstSignatureCharacter == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
    }

    private ObjectNode expectedMetricUpdated(JsonNode event) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", 1);
        envelope.set("eventId", event.path("eventId"));
        envelope.put("eventType", "MetricUpdated");
        envelope.set("databaseConfigId", event.path("databaseConfigId"));
        envelope.set("publishedAt", event.path("publishedAt"));

        ObjectNode data = envelope.putObject("data");
        data.set("id", event.path("metricId"));
        data.set("databaseConfigId", event.path("databaseConfigId"));
        data.set("configVersion", event.path("configVersion"));
        for (String field : List.of("timestamp", "collectionAttemptTime", "lastSuccessAt")) {
            data.set(field, event.path(field));
        }
        for (String field : List.of("cpuUsage", "memoryUsage", "qps",
                "slowQueriesPerSecond", "metricWindowSeconds")) {
            JsonNode value = event.path(field);
            if (value.isNull()) {
                data.putNull(field);
            } else {
                data.put(field, value.doubleValue());
            }
        }
        for (String field : List.of("activeConnections", "maxConnections", "slowQueries",
                "slowQueriesDelta", "threadsRunning", "storageBytes", "responseTimeMs")) {
            data.set(field, event.path(field));
        }
        for (String field : List.of("collectionStatus", "errorCode", "errorMessage",
                "unavailableMetrics")) {
            data.set(field, event.path(field));
        }
        return envelope;
    }

    private JsonNode readResource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing classpath test resource: " + name);
            }
            return objectMapper.readTree(input);
        }
    }

    private JsonNode readContractFixtures() throws IOException {
        Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate : List.of(
                working.resolve("docs/contract-examples.json"),
                working.resolve("../docs/contract-examples.json").normalize())) {
            if (Files.isRegularFile(candidate)) {
                return objectMapper.readTree(candidate.toFile());
            }
        }
        throw new IOException("docs/contract-examples.json is not reachable from " + working);
    }

    private void await(String scenario, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting " + scenario, exception);
            }
        }
        throw new AssertionError("Timed out awaiting " + scenario);
    }

    private <T extends AutoCloseable> T track(T connection) {
        openConnections.add(connection);
        return connection;
    }

    private void closeConnections() throws Exception {
        Exception firstFailure = null;
        for (int index = openConnections.size() - 1; index >= 0; index--) {
            try {
                openConnections.get(index).close();
            } catch (Exception failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            }
        }
        openConnections.clear();
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private record LoginSession(HttpClient client, String accessToken) { }

    private record SignedToken(String value, Instant expiresAt) { }

    private record RegisteredFrames(
            SubscriptionRegistration registration,
            Stage3StompClient.FrameQueue frames
    ) { }

    private record SubscriptionRegistration(String subscriptionId, String sessionId, String destination) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObserverConfiguration {
        @Bean
        SubscriptionObserver stage3SubscriptionObserver() {
            return new SubscriptionObserver();
        }
    }

    static final class SubscriptionObserver implements ApplicationListener<SessionSubscribeEvent> {
        private final Map<String, CompletableFuture<SubscriptionRegistration>> expectations =
                new ConcurrentHashMap<>();

        CompletableFuture<SubscriptionRegistration> expect(String subscriptionId) {
            CompletableFuture<SubscriptionRegistration> expected = new CompletableFuture<>();
            if (expectations.putIfAbsent(subscriptionId, expected) != null) {
                throw new IllegalStateException("Duplicate subscription expectation: " + subscriptionId);
            }
            return expected;
        }

        @Override
        public void onApplicationEvent(SessionSubscribeEvent event) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
            String subscriptionId = accessor.getSubscriptionId();
            CompletableFuture<SubscriptionRegistration> expected = expectations.remove(subscriptionId);
            if (expected != null) {
                expected.complete(new SubscriptionRegistration(
                        subscriptionId, accessor.getSessionId(), accessor.getDestination()));
            }
        }

        void clear() {
            expectations.values().forEach(future ->
                    future.completeExceptionally(new IllegalStateException("Scenario ended before SUBSCRIBE")));
            expectations.clear();
        }
    }
}
