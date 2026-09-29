# Part C realtime integration handoff

This is the checked-source handoff for the Part C Stage 3 realtime slice. It
describes what is present in the local C candidate and the gates that still
belong to the A/B/C integration. It is an implementation handoff, not a release
or deployment approval.

## Candidate and migration status

The C candidate is based on the merged Actual A V3 baseline at
`d3308d10de8b1a439e93c8099c2901ee3c5dc40d` (A source
`b7d54171b029566c9cf2995dbad61117dfdda178`). B production callers and C
status/incident REST producers remain integration work; this checkout does not
change upstream branches.

The migration order is deliberately:

```text
B V2 (auth, sessions, database credential columns)
    -> A V3 (metric and common event infrastructure, including processed_events)
    -> C V4 (staged Part C tables)
```

The application's migration locations now contain the canonical V1 baseline,
B V2, and Actual A V3. A V3 creates the production `event_outbox` and
`processed_events` tables and migrates A metric timestamps to `TIMESTAMPTZ`,
represented by `Instant` in the A model. C V4 remains at
`backend/schema/part-c/V4__part_c_monitoring.sql`, outside the Flyway classpath,
until the two missing composite-key prerequisites are finalized. Do not copy
the staged V4 into `backend/src/main/resources/db/migration` as a realtime
workaround.

The `processed_events` table is A-owned production infrastructure supplied by
V3. The full Stage 3 integration runs against that Actual A V3 table; isolated
consumer tests mirror the required table inline with `VARCHAR(128)` stream and
consumer-group columns. `REALTIME_ENABLED` defaults to `false` through
`monitoring.realtime.enabled: ${REALTIME_ENABLED:false}`. Enabling realtime in
an environment that has no Actual A V3 `processed_events` table fails closed
when the consumer verifies its prerequisite; it does not create a C-owned
table.

The staged V4 also requires a composite key
`UNIQUE (sid, user_id)` on `auth_sessions` for its session-owner foreign key.
The checked B V2 migration currently creates `sid` as a primary key and an
index on `user_id`, but does not create that composite unique constraint. This
is an integration gate for B/A migration reconciliation. The test fixture under
`backend/schema/part-c/test-fixtures/V2_V3_prerequisites.sql` supplies the
required key so that staged-schema probes can be run in isolation; it does not
represent the production B migration. Actual A V3 likewise still lacks
`UNIQUE (id, database_config_id)` on `metric_data`, so both keys remain V4 gates.

Lifecycle events use A's common `OutboxWriter` and `OutboxEventType`: C supplies
body-only JSON, the `database:<id>` ordering key, and flushes each append. The
writer adds the common envelope and routes status and incident events to their
own streams. C's occurrence time remains in the event body and is distinct from
the writer's envelope creation time.

The PR6 interface and DTO contract remains canonical and unchanged. Contract
handoff commit `3586788` is published and merged into `develop`; this checkout
records local integration facts only.

## What the current realtime code does

When enabled, `RedisMetricConsumer` reads the configured `stream:metrics`
Redis stream in consumer group `cg:realtime`. It parses the checked v1
`MetricCollectedEvent`, records `(stream, consumer_group, event_id)` in the
A-owned `processed_events` table, and hands an accepted metric to the STOMP
adapter. Duplicate Redis records with the same event ID are acknowledged after
the database dedup decision and do not publish a second metric frame.
Invalid records are sanitized into `stream:dead-letter`. For valid JSON objects,
the dead-letter payload retains only canonical event metadata: schema/version,
identifiers, times, and recognized status/error enums. Free text including
`databaseName` and `errorMessage`, metric values, unknown fields, arrays, and
nested objects are omitted. Malformed or non-object input uses a constant marker;
all markers and projections are capped at 64 KiB. `MetricUpdated` and subscription
`Error` envelopes use UTC timestamps with exactly three fractional digits
(`yyyy-MM-dd'T'HH:mm:ss.SSSX`); dead-letter `failedAt` uses the same format.

The current adapter publishes:

```text
/topic/databases/{databaseConfigId}/metrics
```

`StompDestination` accepts the contract's `status` and `incidents` topic forms,
but this candidate has no status producer or incident producer yet. Those
topics therefore do not imply that status or incident events are currently
emitted. The `/user/queue/errors` destination is reserved for subscription
errors.

The metric envelope keeps the source event ID:

```json
{
  "schemaVersion": 1,
  "eventId": "b43a63cb-5f28-4f4a-8de1-412c352ba79d",
  "eventType": "MetricUpdated",
  "databaseConfigId": 12,
  "publishedAt": "2026-09-28T03:00:00.050Z",
  "data": { "id": 501, "databaseConfigId": 12 }
}
```

Clients must deduplicate received metric events by `eventId` and compare metric
state by `(configVersion, timestamp, data.id)`. A WebSocket frame is an update
hint, not a durable replay guarantee. The client flow from
`docs/events.md` is to subscribe to the error queue and target topic first,
read the REST latest/status baseline while buffering frames, apply only frames
newer than the baseline, read again after two seconds, and reconcile every 30
seconds. Use the metric history endpoint to fill chart gaps after reconnects;
keep metric, status, and incident ordering cursors separate. The current
branch's A metric controllers expose `/api/v1/metrics/{dbId}/latest`,
`recent`, and `history`; the status and incident REST producers are still C
work and are not claimed as available by this handoff.

## Authentication and native STOMP

Part C uses B's actual authentication seam:
`StompAccessTokenAuthenticator.authenticateAuthorization` delegates to
`AuthService.authenticate(accessToken)`. There is no separate
`validateSession(sid)` method in the B candidate. CONNECT authentication and
periodic revalidation use the returned principal, including its user ID, role,
session ID, and expiry. C does not mint JWTs, parse JWTs, or read B's session
tables directly.

The checked B login flow is:

1. `GET /api/v1/auth/csrf` with the exact public `Origin`.
2. `POST /api/v1/auth/login` with that CSRF token, the same `Origin`, and a
   JSON body containing `email` and `password`.
3. Keep the returned `accessToken` in memory and send it only in the STOMP
   CONNECT header. The refresh token is a cookie used by the HTTP auth flow;
   it is not a STOMP query parameter or a STOMP cookie substitute.

The following PowerShell is the checked endpoint sequence. It requires a
seeded B user and the B environment keys from `backend/.env.example`; the
password shown is a placeholder, not a production secret.

```powershell
$base = 'http://127.0.0.1:8080'
$origin = 'http://localhost:5173'
$email = 'admin@example.test'
$password = '<seeded-password>'
$web = New-Object Microsoft.PowerShell.Commands.WebRequestSession

$csrf = Invoke-RestMethod -Method Get `
    -Uri "$base/api/v1/auth/csrf" `
    -Headers @{ Origin = $origin } `
    -WebSession $web

$login = Invoke-RestMethod -Method Post `
    -Uri "$base/api/v1/auth/login" `
    -Headers @{ Origin = $origin; 'X-CSRF-Token' = $csrf.csrfToken } `
    -ContentType 'application/json' `
    -Body (@{ email = $email; password = $password } | ConvertTo-Json) `
    -WebSession $web

$accessToken = $login.accessToken
```

The WebSocket endpoint is `/ws`. The handshake must carry exactly one
`Origin: http://localhost:5173` header and no query string. After the WebSocket
upgrade, a native STOMP 1.2 client sends these frames; each frame ends with a
NUL byte (`\0`). The error subscription should be registered before a target
subscription.

```text
CONNECT
accept-version:1.2
heart-beat:10000,10000
Authorization:Bearer <accessToken>

\0
SUBSCRIBE
id:errors
destination:/user/queue/errors
ack:auto

\0
SUBSCRIBE
id:metrics-12
destination:/topic/databases/12/metrics
ack:auto

\0
```

The checked Java reference clients are
`backend/src/test/java/com/example/monitoring/realtime/integration/Stage3StompClient.java`
and `Stage3RawWebSocketClient.java`. Application SEND destinations are not
enabled; a client should only CONNECT, SUBSCRIBE, receive, and DISCONNECT.
The server's heartbeat and frame limits are 10 seconds and 64 KiB. CONNECT must
complete within five seconds; the server revalidates authenticated sessions
every ten seconds, limits one account to five sockets and each socket to 61
subscriptions, and waits six seconds for the first frame.
`setPreserveReceiveOrder(true)` keeps inbound security `preSend` failures on
the protocol error path: the original public error code is sent in one STOMP
`ERROR` JSON frame before the protocol close. The serialized session gives that
error write a 250 ms quiescence window; a blocked writer is closed rather than
allowing a late frame to overtake the error. An invalid origin, query string,
missing Bearer header, wrong heartbeat, or unsupported destination is rejected
with the contract error and the connection/subscription is closed as
appropriate.

## Local runbook

The repository's checked scripts are the source of truth for local commands:

```powershell
# From the repository root. Requires Docker Compose; this is a command recipe,
# not a claim that Docker was available during this handoff.
.\scripts\start-local-services.ps1

# From the repository root, in another terminal. Requires JDK 17.
.\backend\scripts\run-local.ps1

# From the repository root, after the local check.
.\scripts\stop-local-services.ps1
```

The local profile uses PostgreSQL `localhost:5432/monitoring_db`, Redis
`localhost:6379`, and `PUBLIC_ORIGIN=http://localhost:5173`. It leaves realtime
disabled unless `REALTIME_ENABLED=true` is explicitly supplied. B's required
environment keys are `JWT_SIGNING_KEYS`, `JWT_ACTIVE_KID`,
`DB_CONFIG_ENCRYPTION_KEYS`, `DB_CONFIG_ACTIVE_KEY_VERSION`, and the database,
Redis, origin, proxy, and target policy keys documented in
`backend/.env.example`. Never place real values in this document.

For a controlled test-only realtime run, the checked integration class enables
the fixture and the realtime property itself. From `backend`, the opt-in
command is:

```powershell
$env:STAGE3_INTEGRATION_ENABLED = 'true'
$env:STAGE3_STREAM_KEY = 'stream:stage3-integration'
$env:STAGE3_DEAD_LETTER_STREAM = 'stream:stage3-integration:dead-letter'
$env:STAGE3_APP_PORT = '18093'
.\gradlew.bat test `
  --tests com.example.monitoring.realtime.integration.RealtimeStage3IntegrationTest `
  --no-daemon --console=plain
```

That class uses the Actual A V3 `processed_events` and `event_outbox` tables,
asserts Flyway versions `1`, `2`, and `3` with no V4, seeds a user and three
target rows, and uses the configured Stage 3 stream (default
`stream:stage3-integration`). Isolated consumer tests mirror `processed_events`
inline with `VARCHAR(128)` stream and consumer-group columns. Run the full
check against an isolated disposable PostgreSQL database because the fixture
seeds target IDs 12, 13, and 14. It deletes only the explicitly configured
Stage 3 stream and dead-letter stream between scenarios; use unique
`STAGE3_STREAM_KEY`,
`STAGE3_DEAD_LETTER_STREAM`, and `STAGE3_APP_PORT` values when sharing a host.

To inspect a checked contract payload without using the realtime stream, the
foundation probe uses a dedicated Redis key. PowerShell 7.5 or newer preserves
the fixture timestamp text with `-DateKind String`:

```powershell
$probeStream = 'stream:stage3:part-c-probe'
$contract = Get-Content -Raw .\docs\contract-examples.json |
    ConvertFrom-Json -DateKind String
$payload = $contract.fixtures.metricCollectedEvent |
    ConvertTo-Json -Depth 20 -Compress
$entryId = $payload |
    docker compose exec -T redis redis-cli -x XADD $probeStream '*' payload
docker compose exec -T redis redis-cli --raw XRANGE $probeStream $entryId $entryId
docker compose exec -T redis redis-cli XDEL $probeStream $entryId
docker compose exec -T redis redis-cli DEL $probeStream
```

The probe is serialization/transport inspection only. A controlled end-to-end
consumer check must publish the same object to `app.redis.stream-key` (the
production default is `stream:metrics`; the opt-in integration test uses its
`STAGE3_STREAM_KEY`) and remove the test record afterward; do not publish
historical fixtures to a shared environment. No Docker execution is claimed
here.

## Verification

From `backend`, the normal service-free checks are:

```powershell
.\gradlew.bat test --no-daemon --console=plain
.\gradlew.bat build --no-daemon --console=plain
```

The live Stage 3 check is explicitly opt-in. Use the guarded command in the
local runbook above with `STAGE3_INTEGRATION_ENABLED=true`, dedicated
`STAGE3_STREAM_KEY` and `STAGE3_DEAD_LETTER_STREAM` values, and an isolated
disposable PostgreSQL database. It exercises B login/CSRF and access-token
authentication, the real Spring application with PostgreSQL and Redis, Redis
stream consume/ack/reclaim and event-ID deduplication, sanitized dead-letter
handoff, native WebSocket/STOMP CONNECT and subscription authorization,
metric delivery, protocol/subscription errors, token expiry, logout
revalidation, and the ordered error-before-close behavior.

The live check uses Actual A V3's production `processed_events` table when run
against the integrated application. Isolated consumer checks mirror the table
inline and must not be read as a migration receipt. C V4 remains staged outside
Flyway until both V2/V3 keys are present:
`UNIQUE (sid, user_id)` on `auth_sessions` and `UNIQUE (id,
database_config_id)` on `metric_data`. The Compose commands above are local
recipes and require Docker Compose; no Docker execution is implied by this
document.
