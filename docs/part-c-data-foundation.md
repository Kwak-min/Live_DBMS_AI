# Part C data foundation

This document defines the Stage 2 storage boundary for Part C. It stages schema
only; it does not activate Flyway V4 or implement policy evaluation, incident
services, notification workers, repositories, or APIs.

## Current candidate status

This remains the Stage 2 foundation record on the merged Actual A baseline. The
active Flyway inventory is A V1, B V2, and A V3. V3 owns the production
`event_outbox` and `processed_events` tables and migrates metric timestamps to
`TIMESTAMPTZ`, represented by `Instant` in the A model. The checked B V2
migration still does not expose `UNIQUE (sid, user_id)`, and V3 still does not
expose `UNIQUE (id, database_config_id)` on `metric_data`; those two composite
keys are the remaining prerequisites for staged V4. Staged V4 persists nullable
`activation_at` and enforces the enabled/nondeleted and disabled/deleted
lifecycle rules around that value. Production V4 registration, upgrade
verification, and activation backfill remain blocked until both keys and the
retained-target decisions are delivered and verified. See [the Stage 3 realtime
handoff](part-c-realtime.md) for the current refs, commands, and verification
boundaries.

## Ownership and prerequisites

| Area | Owner | Stage 2 boundary |
| --- | --- | --- |
| Accounts and sessions | B | `users(id)` and `auth_sessions(sid,user_id)`; V2 must expose `UNIQUE (sid,user_id)` for session-owner integrity |
| Monitored targets | B | `database_configs(id)`; targets are soft-deleted and their IDs are not reused |
| Metrics | A | `metric_data(id,database_config_id)`; Actual A V3 supplies the table but still needs `UNIQUE (id,database_config_id)` for target-scoped metric references |
| Reliable events | A common | Actual A V3 owns `event_outbox` and `processed_events`; C writes/reads them through the common interfaces and does not duplicate them. The lifecycle fixture adds only the missing composite keys and C schema. |
| Status, risk, incidents, recipients, deliveries | C | The seven tables in the staged V4 |
| Recipient encryption service and key ring | B security boundary | C persists only key version, 12-byte nonce, and ciphertext-with-tag returned by the shared encryption boundary |

The prerequisite SQL under `backend/schema/part-c/test-fixtures` is deliberately
test-only. Production V4 has foreign keys to the real V2/V3 tables and contains no
fallback or fake account, session, target, metric, outbox, or dedup tables.

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

V4 remains at `backend/schema/part-c/V4__part_c_monitoring.sql` until the two
missing V2/V3 composite keys are integrated. The A migration owner controls
version registration and must move the unchanged file into Flyway's active
migration directory only after checking the stable prerequisite keys, an empty
V1-to-V4 apply, and a V3-to-V4 upgrade. The activation backfill must also be
planned against the Actual A V3 state before V4 is registered. Part C must not
patch V2/V3 from this staged migration.

The integrated application keeps the Stage 3 realtime beans disabled by default with
`monitoring.realtime.enabled: ${REALTIME_ENABLED:false}`. Set `REALTIME_ENABLED=true`
only after Actual A V3's `processed_events` table exists; the consumer fails closed
when that table is unavailable rather than creating a C-owned substitute.

The schema deliberately leaves event publication and consumption deduplication in
A's `event_outbox` and `processed_events`. State/incident/delivery changes will join
those common tables transactionally when the later services are implemented.

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

Docker was unavailable for this Stage 2 check, so the Compose command above is a
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
traceability; it is not current Actual-A green evidence. The schema-only fixture
now mirrors the Actual A V3 11-column outbox and `processed_events` shape, while
the current schema receipt separately verifies real Flyway V1/V2/V3 databases
with only the missing composite keys and staged C schema. The lifecycle
integration uses the real A tables and adds only those missing keys plus C
schema.
