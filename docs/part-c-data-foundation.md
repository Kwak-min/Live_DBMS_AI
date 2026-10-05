# Part C data foundation

This document defines the activated V4 storage boundary for Part C. It records
the durable state, policy, incident, recipient, and delivery schema used by the
current integration. V4 does not claim a risk-evaluation engine, notification
delivery worker, or a live-status read switch.

## Current candidate status

The active Flyway inventory is A V1, B V2, A V3, and C V4. V3 owns the
production `event_outbox` and `processed_events` tables and migrates metric
timestamps to `TIMESTAMPTZ`, represented by `Instant` in the A model. V4 adds
the named `UNIQUE (sid, user_id)` and `UNIQUE (id, database_config_id)` keys
forward-only, then creates the seven C tables and initializes every retained
target transactionally. It stores one state and one version-1 default policy per
target at the target's actual `config_version`.

V4 uses one `date_trunc('milliseconds', transaction_timestamp())` activation
epoch for enabled, nondeleted targets. Disabled and deleted targets are
initialized as `PAUSED` without an activation time. Historical A metrics remain
stored but never seed C's attempt, success, or latest-metric fields, and the
initialization emits no synthetic lifecycle outbox events. A retained row with
an unsafe ID/version or an enabled soft-deleted state aborts the migration
without normalization. See [the realtime handoff](part-c-realtime.md) for the
live-state boundary and runtime handoff.

## Ownership and prerequisites

| Area | Owner | Active integration boundary |
| --- | --- | --- |
| Accounts and sessions | B | `users(id)` and `auth_sessions(sid,user_id)`; active V4 adds the named composite key for session-owner integrity |
| Monitored targets | B | `database_configs(id)`; targets are soft-deleted and their IDs are not reused |
| Metrics | A | `metric_data(id,database_config_id)`; V4 adds the named target-scoped composite key before C foreign keys |
| Reliable events | A common | Actual A V3 owns `event_outbox` and `processed_events`; C uses the common interfaces and does not duplicate them. Migration initialization emits no lifecycle events. |
| Status, risk, incidents, recipients, deliveries | C | The seven tables in active V4 plus the backend risk, incident, realtime, and notification consumers |
| Recipient encryption service and key ring | B security boundary | C persists only key version, 12-byte nonce, and ciphertext-with-tag returned by the shared encryption boundary |

The SQL under `backend/schema/part-c/probes` is inspection-only. Production V4
has foreign keys to the real V2/V3 tables and contains no fallback or fake
account, session, target, metric, outbox, or dedup tables.

## Seven-table model

| Table | Durable responsibility |
| --- | --- |
| `monitoring_states` | One current status row per target, with config/state versions, lifecycle flags, nullable activation time, freshness/risk, attempt/success times, and a target-scoped latest metric pointer |
| `risk_policies` | One versioned policy per target; PostgreSQL checks ranges and that `rules` is a JSON array, while the API layer owns the two-rule semantic validation |
| `incidents` | UUID incident history, target/name snapshot, rule/severity/status, resolution evidence, metric evidence snapshot, source event/metric, and incident version |
| `risk_rule_states` | Restart-safe observation cursor plus independent WARNING, CRITICAL, and FATAL candidate clocks and the recovery clock for each target/rule |
| `push_subscriptions` | User/session-owned endpoint hash and encrypted endpoint/key payload, expiration, active state, and tombstone |
| `notification_webhooks` | Slack recipient metadata and encrypted URL structure, active state, and tombstone |
| `notification_deliveries` | Durable incident-version job/result with notification type, typed recipient foreign key, generated API recipient ID, attempts, due/expiry times, error, and sent time |

All external numeric IDs and versions are positive and bounded by
`9007199254740991`. Attempt counts are nonnegative. Times are `TIMESTAMPTZ`, incident
and event IDs are UUID, policy rules are `JSONB`, and contract enums use
`VARCHAR` plus checks.

There can be only one OPEN incident for a target/rule. OPEN rows have no resolution
fields; RESOLVED rows have both `resolved_at` and `resolution_reason`. Deleting a
retained metric sets C metric pointers to null. Incident name, metric, threshold,
message, and timing evidence remain in the incident row.

Push uniqueness applies only to active, nondeleted endpoint hashes. Deactivation
or deletion leaves a tombstone, so the same endpoint can later receive a new ID
without transferring old ownership or delivery history. Active subscriptions must
retain their session. Session cleanup must first deactivate the subscription; the
session foreign key can then clear `sid` while retaining the user tombstone.

A delivery chooses exactly one recipient foreign key: push subscription for
`WEB_PUSH`, or webhook for `SLACK`. `recipient_id` is generated from that selected
key, allowing the contract uniqueness key
`(incident_id, incident_version, channel, recipient_id)` while retaining database
referential integrity. `notification_type`, `next_attempt_at`, `expires_at`, and
prior `SENT` rows provide the durable state needed for incident-version staleness,
cooldown/recovery eligibility, retry scheduling, and the ten-minute validity window.

Endpoint/key bundles and Slack URLs have no plaintext columns. AES-GCM storage is
represented by a positive key version, a 12-byte nonce, and ciphertext including
the authentication tag. Endpoint hashes are 32 bytes and exist only for active
identity/deduplication; they are not reversible endpoint storage.

## Activation and migration ownership

`backend/src/main/resources/db/migration/V4__part_c_monitoring.sql` is the sole
production V4 path. It is forward-only and does not edit V1, V2, or V3. Before
creating C foreign keys, it acquires `SHARE ROW EXCLUSIVE` on
`database_configs`, verifies the retained rows, and declares both required
composite keys. The first invalid retained target raises a target-identifying
diagnostic; the transaction rolls back all V4 DDL and backfill rows.

The backfill validates postconditions for target/state/policy counts, exact
target IDs and versions, state coherence, and policy defaults. It uses one
millisecond transaction epoch for all applicable activations. It does not call
the lifecycle `CREATED` action, seed from historical metrics, or write
migration outbox events. Any later `cg:risk` consumer must reject metrics from
before the activation epoch when deciding current C state.

The integrated application keeps the Stage 3 realtime beans disabled by default with
`monitoring.realtime.enabled: ${REALTIME_ENABLED:false}`. Set `REALTIME_ENABLED=true`
only after Actual A V3's `processed_events` table exists; the consumer fails closed
when that table is unavailable rather than creating a C-owned substitute.

The schema deliberately leaves event publication and consumption deduplication in
A's `event_outbox` and `processed_events`. Current state, incident, and delivery
changes join those common tables transactionally through the C services.

## Stage 3 auth and publish handoff

Stage 3 adapters use B's existing
[`AuthService.authenticate(token)` contract](integration-operations.md#2-파트-간-내부-서비스-계약).
The B candidate does not expose a separate `validateSession(sid)` method; its
`StompAccessTokenAuthenticator` delegates both CONNECT and periodic revalidation
to `authenticate(token)`, which checks the current user and session state. HTTP and
STOMP entry points authorize with the returned `AuthPrincipal.userId`, `role`,
`sessionId`, and `expiresAt`; push delivery uses the same authentication seam before
sending. Tests replace this port with fixed `AuthPrincipal` results. They do not parse
or mint JWTs, create sessions, or otherwise implement B authentication in C.

`userId` and `sessionId` remain B identifiers, and `databaseConfigId` is the shared
B target identifier exposed by `TargetProvider`; C does not introduce a separate
user, target, or `projectId` namespace. This follows the
[`databaseConfigId` identifier rule](integration-contract-draft.md#2-공통-규칙) and
lets authorization fixtures use the same IDs as the target/session fixtures.

From the repository root, this PowerShell sample publishes the actual
`fixtures.metricCollectedEvent` object as one Redis field named `payload`. It uses a
dedicated probe key and removes both the entry and key afterward:

The sample requires PowerShell 7.5 or newer because `-DateKind String` preserves the
fixture's timestamp text during JSON conversion.

```powershell
$probeStream = 'stream:stage2:part-c-probe'
$contract = Get-Content -Raw .\docs\contract-examples.json | ConvertFrom-Json -DateKind String
$payload = $contract.fixtures.metricCollectedEvent | ConvertTo-Json -Depth 20 -Compress
$entryId = $payload | docker compose exec -T redis redis-cli -x XADD $probeStream '*' payload
docker compose exec -T redis redis-cli --raw XRANGE $probeStream $entryId $entryId
docker compose exec -T redis redis-cli XDEL $probeStream $entryId
docker compose exec -T redis redis-cli DEL $probeStream
```

`-x` supplies stdin as the final XADD argument, so the arguments after the entry ID
are exactly one field/value pair: `payload` and the fixture JSON. Keep the fixture's
`databaseConfigId=12` and fixed `2026-09-28T03:00:00.000Z` timestamp unchanged. This
sample proves serialization and stream transport only; its historical timestamp
must not drive current freshness or risk evaluation, and it must never be published
to a shared real `stream:metrics` key. The legacy v0 publisher has a different JSON
shape and must not share a stream or consumer group with the v1 fixture.

Docker was unavailable for this handoff check, so the Compose command above is a
handoff rather than a Docker execution claim. The same fixture completed an object
equal roundtrip through the official Redis 7.4.11 binary on isolated loopback port
6398: XRANGE returned one `payload` field, XDEL returned 1, the dedicated key was
absent after cleanup, and the bounded harness stopped its server.

## Historical verification record

The earlier embedded PostgreSQL run used a test-only fixture, captured the
expected pre-V4 `42P01` failure, rejected V4 with `42830` when the B composite
key was removed, then applied V4 and recorded 21 scenarios. Its probes covered
exact table count, activation type and lifecycle coherence, a provisional
outbox shape, separate severity clocks, OPEN uniqueness, resolution coherence,
recipient encryption shape and session ownership, active endpoint uniqueness
with tombstone reuse, delivery deduplication, enum/version/count/safe-ID checks,
and metric-retention evidence. Each test removed the probe schema in its cleanup
path, the embedded server closed in suite teardown, and cleanup SQL confirmed
that `part_c_probe` was gone. This historical receipt is preserved for
traceability; it is not current Actual-A green evidence. The current constraint
probe verifies the real common outbox shape, active V4 tables, and cross-target
metric references. Those receipts are historical probe evidence; the active
integration uses the real V1/V2/V3 tables, active V4, strict retained-row
validation, and no historical metric or outbox seeding.

### Notification timing and session ownership

Active V4 stores `notification_deliveries.expires_at` and
`next_attempt_at`. The immutable logical eligibility instant is derived as
`eligibleAt = expiresAt - 600 seconds`; do not describe `eligible_at` as a
physical V4 column unless a later migration explicitly adds it. Merges,
restarts, and retry backoff may move only `next_attempt_at`. At or after
`expiresAt`, the worker records `CANCELLED`.

Push rows are session-bound through `(sid,user_id)` and are tombstoned before
logout/revocation/retention clears the session. Push DELETE also cancels pending
deliveries synchronously. The worker performs the final session and recipient
check immediately before an external attempt. Delivery lease ownership is one
process-lifetime PostgreSQL session advisory lease with JVM non-overlap; no
business row lock is held across HTTP.

The existing A `database_configs` display writer and B status reads remain the
owners until the metric-driven C state consumer is activated. V1-V4 remain
immutable. The backend uses one additive private V5
`notification_success_receipts` compact success receipt; it does not add a
physical `eligible_at` column or another schema change. The receipt is internal
and does not claim a frontend or provider acceptance implementation.

### Durable success receipt

Delivery rows may still be purged after 30 days, so recovery proof for an OPEN
incident is stored in the private receipt. Backfill only `SENT`
`INCIDENT_OPENED`/`SEVERITY_INCREASED` rows and keeps the maximum timestamp per
incident/channel/recipient. Existing OPEN and RESOLVED incidents, including
inactive or tombstoned recipients, are eligible; recovery and non-SENT rows are
not. History already purged cannot be reconstructed. A resolved incident's
180-day cascade removes its receipt. Target-environment activation of the
migration and new binary requires writer drain/fence and the backfill lock; this
is a rollout prerequisite rather than a pre-publication action.
