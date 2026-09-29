# C lifecycle contract handoff

This handoff describes the C lifecycle implementation that is available for a future integration on the merged Actual A V3 baseline. `JdbcMonitoringLifecyclePort` is a Spring component with JDBC persistence and transactional outbox behavior, but B CRUD is not wired to it. The implementation performs no startup schema access and remains inactive until the migration and retained-target gates below pass. The activation order is B V2, verified real A V3, C V4, explicit existing-target backfill/validation, and only then B caller wiring.

## Canonical surface

The canonical interface is `MonitoringLifecyclePort` in the C-owned package `com.example.monitoring.lifecycle.port`. Team shorthand may call it `LifecyclePort`, but callers must import the canonical name.

```java
import com.example.monitoring.lifecycle.port.MonitoringLifecyclePort;
import com.example.monitoring.lifecycle.port.TargetChange;
import com.example.monitoring.lifecycle.port.TargetChangeType;

import java.time.Instant;
import java.util.UUID;

final class DatabaseWriter {
    private final MonitoringLifecyclePort lifecyclePort;

    DatabaseWriter(MonitoringLifecyclePort lifecyclePort) {
        this.lifecyclePort = lifecyclePort;
    }

    void afterDatabaseMutation(long id, long version, boolean enabled, String name,
                               TargetChangeType action, Long actorId, UUID requestId) {
        lifecyclePort.applyChange(new TargetChange(
                id, version, action, enabled, name, Instant.now(), actorId, requestId));
    }
}
```

The exact method is `void applyChange(TargetChange change)`. It is synchronous and has no checked error type. The implementation requires an already active, writable caller transaction and uses mandatory propagation; it never creates a new transaction. Any runtime failure must reach the B transaction boundary so the B mutation, B audit, C lifecycle change, incident closures, notification cancellation, and outbox work roll back together.

## TargetChange fields

`TargetChange` is a Java 17 immutable record. It uses the primitive/wrapper and time/UUID types already used by the B branch at this boundary, without importing B's common package.

| Field | Java type | Nullability and meaning |
| --- | --- | --- |
| `databaseConfigId` | `long` | Required positive B `ApiId` value. The target identifier is never reused after a soft delete. |
| `configVersion` | `long` | Required positive resulting version after the B mutation. Create starts at `1`; update, pause, resume, and delete carry the incremented version. |
| `changeType` | `TargetChangeType` | Required. One of `CREATED`, `UPDATED`, `PAUSED`, `RESUMED`, or `DELETED`. |
| `enabled` | `boolean` | Required resulting enabled state. A delete carries `false`. |
| `name` | `String` | Required display name after B trimming and validation. No host or secret is carried. |
| `occurredAt` | `Instant` | Required UTC timestamp for the change. Do not use B's legacy `LocalDateTime` entity fields at this boundary. |
| `actorId` | `Long` | Nullable. It is the authenticated B actor ID, or `null` for a scheduled/system operation. Zero is not a system sentinel. |
| `requestId` | `UUID` | Required correlation ID. HTTP calls use B's request context ID; scheduled work creates a fresh ID. |

The record rejects null `changeType`, `name`, `occurredAt`, and `requestId`. B remains responsible for validating the positive ID/version ranges through its common API rules before calling C.

## Action mapping

B emits exactly one action for each successful configuration mutation, using the resulting `configVersion` and `enabled` value:

| B operation | `changeType` | Notes |
| --- | --- | --- |
| Create | `CREATED` | Use even when a caller creates the target disabled; `enabled=false` represents its initial paused state. |
| Update without an enabled transition | `UPDATED` | Includes name, endpoint, credential, database name, or other setting changes with the same enabled state. |
| Update enabled `true` to `false` | `PAUSED` | If other fields change in the same request, the resulting name/version/state still travel in this change. |
| Update enabled `false` to `true` | `RESUMED` | The resulting version is sent after the row update. |
| Soft delete | `DELETED` | Send `enabled=false`; retain the display name for C tombstone and incident history. |

## Transaction and context rules

B calls `applyChange` from the existing row-locked database write transaction. The call must stay synchronous. C updates lifecycle state, the default policy on create, state versions, administrative incident closures, pending-delivery cancellation, and transactional outbox rows in that same transaction. C does not call Redis, HTTP, Web Push, Slack, or another external service from this method. Any C runtime error propagates to B and causes rollback.

`AuditRequestContext.Details` on the B branch supplies `Long actorId` and `UUID requestId`; C receives those values in `TargetChange` and does not resolve authentication or client IP. B keeps `clientIp` in its own audit input. Scheduled work uses `actorId=null` and a fresh `requestId`; its separate audit record uses the documented `SYSTEM` client-IP value.

For unit tests only, place a fixed test double under `backend/src/test/java`, for example:

```java
final class FixedLifecyclePort implements MonitoringLifecyclePort {
    private TargetChange received;

    @Override
    public void applyChange(TargetChange change) {
        received = change;
    }

    TargetChange received() {
        return received;
    }
}
```

Do not register this fake as a production Spring bean and do not use it to claim CRUD integration.

## Implementation status and caller handoff

The production implementation is `com.example.monitoring.lifecycle.adapter.JdbcMonitoringLifecyclePort`. It locks and validates the B target before reading or changing C state, then performs all C writes through the caller-bound JDBC transaction. The C rows are `monitoring_states`, `risk_policies`, incidents, rule states, notification deliveries, and outbox inserts. The lock order is fixed: lock the B `database_configs` row first with `FOR UPDATE`, verify its resulting `config_version`, `enabled`, `name`, and deleted state, then lock the C `monitoring_states` row and the target's OPEN incidents in stable `incident_id` order. This ordering lets a future B `saveAndFlush` become visible to C and avoids acquiring the C lock before the B row lock.

The future B caller sequence is:

1. Start the existing writable transaction. For `CREATED`, use B's existing quota/advisory-lock path and insert the new target; there is no pre-existing row to lock. For `UPDATED`, `PAUSED`, `RESUMED`, and `DELETED`, acquire the target row lock first. The delete path must also hold the row lock; a missing or already deleted target follows B's existing API behavior and does not call C.
2. Compute exactly one action from the mutation: `CREATED`, `UPDATED`, `PAUSED`, `RESUMED`, or `DELETED`.
3. Mutate the B entity, including `configVersion`, `enabled`, `name`, and the soft-delete fields where applicable.
4. Call `saveAndFlush` and use the flushed row's resulting ID, version, name, enabled flag, and deleted state. For `CREATED`, this flush inserts the row and C then locks the inserted row. The delete path must use `saveAndFlush` as well before calling C; the current B service is intentionally not changed or wired by this work.
5. Record the B audit event and obtain `AuditRequestContext.Details.actorId` and `requestId`. Pass `actorId=null` only for a documented system operation and always pass a correlation UUID.
6. Call `applyChange` once with the flushed values and the UTC `occurredAt` instant. Let every exception escape the caller transaction and return only after the call succeeds.

The current B production callers were not edited, injected, or registered with this component. In particular, this document is a handoff sequence for the future integration; it is not a claim that B CRUD and C lifecycle writes are atomic in the running application today.

## Lifecycle write behavior

`CREATED` is accepted only with `configVersion=1` and no existing C state. It creates `stateVersion=1`, `connection_status=UNKNOWN`, and `risk_level`, metric, and attempt/success fields set to null. An enabled target starts with `data_freshness=NO_DATA` and `activation_at=occurredAt`; a disabled target starts with `data_freshness=PAUSED` and a null activation. The occurrence time is normalized to UTC milliseconds.

Create also creates policy version `1` with `stale_after_seconds=30`, `notification_cooldown_seconds=300`, and exactly the two enabled rules from the lifecycle contract: `CONNECTION_RATIO`/`activeConnectionsRatio` with GTE warning `0.80`, critical `0.90`, fatal `0.95`, and `SLOW_QUERY_RATE`/`slowQueriesPerSecond` with GTE warning `1.0`, critical `5.0`, and no fatal threshold. Both rules use 15-second sustain and recovery windows. A `MonitoringStatusChangedEvent` is written after the state and policy rows.

For `UPDATED`, `PAUSED`, `RESUMED`, and `DELETED`, C requires an existing nondeleted state and the resulting B version to be exactly the prior C version plus one. It increments `stateVersion` once, preserves the existing customized risk policy, resets connection/risk/metric/attempt/success fields, deletes all rule-state candidates, and writes the new state. Enabled `UPDATED` and `RESUMED` transitions set `activation_at=occurredAt`; `PAUSED` and `DELETED` set `data_freshness=PAUSED` and a null activation. `DELETED` retains the C row as a deleted tombstone and must match the flushed B soft-delete row.

Every OPEN incident for the target is locked and closed, including system-rule incidents. The resolution reason is `CONFIG_CHANGED` for `UPDATED` and `RESUMED`, `MONITORING_PAUSED` for `PAUSED`, and `TARGET_DELETED` for `DELETED`. C increments each `incidentVersion`, retains the incident name and evidence fields, sets `resolved_at` to the normalized occurrence time, and sets `source_event_id=NULL`. Every `PENDING` notification delivery for a closed incident is changed to `CANCELLED` with no next attempt. C creates no new notification or recovery delivery and performs no external delivery call.

Incident-resolved outbox rows are appended after each incident close and cancellation. C inserts those resolution rows before the final status row within the same transaction. The production publisher's delivery ordering remains an A integration validation concern. All writes are in the same transaction and a failed append rolls back the entire caller transaction.

## Transactional outbox contract

The lifecycle adapter uses A's common `OutboxWriter` and `OutboxEventType` against the Actual A V3 11-column `event_outbox`; C does not add, duplicate, or activate that production table. The adapter supplies a body-only JSON object, the `database:<id>` ordering key, and one append followed by an explicit flush for each event. The common writer chooses the status or incident stream, creates the envelope (`schemaVersion`, `eventId`, `eventType`, and UTC-millisecond `publishedAt`), enforces the 64 KiB UTF-8 limit, and persists the row in the caller transaction. C's `occurredAt` remains the lifecycle occurrence time in `updatedAt`, `timestamp`, and `resolvedAt` body fields; it is distinct from the writer's envelope creation time.

| Column | Contract |
| --- | --- |
| `event_id` | UUID primary key and the payload `eventId`. |
| `seq` | A-owned `BIGSERIAL` creation sequence and unique publication-order cursor. |
| `event_type` | `MonitoringStatusChangedEvent` or `IncidentResolvedEvent`, routed by `OutboxEventType`. |
| `stream_key` | A-owned Redis stream selected from the event type (`statuses` or `incidents`). |
| `ordering_key` | `database:<databaseConfigId>` for lifecycle events. |
| `payload` | UTF-8 JSONB envelope built from the C body, at most 65,536 bytes. |
| `created_at` | UTC millisecond writer creation time. |
| `published_at` | NULL until a later publisher succeeds. |
| `attempts` | Zero on insertion. |
| `next_attempt_at` | UTC millisecond timestamp initialized to the writer creation time. |
| `last_error` | Nullable A-owned publisher failure text. |

Every payload includes the common envelope fields above. Status payloads include the target ID, config/state versions, enabled/deleted flags, `UNKNOWN` connection status, data freshness, null risk and metric fields, an empty OPEN-incident list after administrative closure, and `updatedAt`. Incident payloads retain the incident ID, target/name, rule and severity, opened/observed/resolved times, resolution reason, metric evidence, null `sourceEventId`, and the incremented incident version. Actor and request identifiers are not embedded in these payloads. C inserts each incident-resolution row before the final status row in the same transaction; the A publisher's cross-stream delivery ordering remains an integration validation concern. JSON serialization uses UTF-8 and rejects payloads above the byte cap before an outbox insert.

## Validation and rollback boundary

The adapter validates `databaseConfigId`, `configVersion`, `actorId` when present, `stateVersion`, and generated `incidentVersion` values as positive JavaScript-safe integers (`1..9007199254740991`). Incident IDs are UUIDs, and `sourceMetricId` is preserved and constrained by the schema rather than explicitly range-validated by this adapter. It rejects duplicate CREATE, same/lower/gapped versions, a missing C state, a flushed B-row mismatch, an action/enabled mismatch, a post-tombstone call, unsafe increments, an occurrence time before an incident's last observation, read-only or absent transactions, and an oversized event payload. It also rejects a failed state, incident, policy, or outbox row count. These failures propagate; they never become a no-op or an unreported success.

The PostgreSQL integration proves both creation and existing-target rollback when an outbox trigger fails. The failure is caught inside the outer test transaction only to assert rollback behavior; the transaction still becomes rollback-only and completes with `UnexpectedRollbackException`. The resulting database snapshot proves that the B target, B audit, C state/policy/incidents/deliveries, and outbox rows return to their pre-call values.

## Activation and existing-target gate

Production activation is blocked until the following sequence is completed:

1. B V2 is present and its target/session prerequisites are verified. The current B V2 does not provide the required `UNIQUE (sid, user_id)` key.
2. The real A V3 is applied and verified. The current active inventory is V1, V2, and V3; V3 owns `event_outbox` and `processed_events` and converts metric times to `Instant`/`TIMESTAMPTZ`, but it still lacks `UNIQUE (id, database_config_id)` on `metric_data`.
3. C V4 is applied only after those prerequisites are present. The staged file is `backend/schema/part-c/V4__part_c_monitoring.sql`; C V4 is not registered as an active production migration in this handoff.
4. An integration owner inventories every retained B target and performs an explicit backfill and validation decision. For a target that can be initialized consistently, create its state/policy once with the resulting retained configuration version and an integration-owner activation time, idempotently. For a target whose history cannot be reconstructed safely, refuse activation and remediate it explicitly. Do not route an existing target with `configVersion>1` through the C `CREATED` action, because `CREATED` is version-1-only. Reject duplicates, gaps, deleted tombstones, and any target lacking exactly one state and one policy.
5. Only after the inventory, backfill/refusal decisions, and one-state/one-policy validation succeed may B wire the caller sequence above.

There is no C startup backfill, automatic activation flag, fallback schema creation, implicit omission, or production activation routine. The lifecycle integration fixture uses Actual A V3's `event_outbox` and `processed_events`, adding only the missing B/A composite keys and the C schema. The schema-only fixture under `backend/schema/part-c/test-fixtures` is now a disposable mirror of the Actual A 11-column outbox and `processed_events` shape; the schema receipt verifies that mirror separately from real Flyway V1/V2/V3 databases. It is not B V2, not A V3, and must not be treated as a production migration.

## Current Actual-A core verification

The current common-outbox implementation is verified by the focused receipt at
`.omo/evidence/c-lifecycle-next/actual-a/core-lifecycle-reconciliation.md` for
commit `3ae41db5b3a2e329119b36e2d17ad8714596c253`. Its five selected test
classes executed 28 tests with 0 failures, 0 errors, and 0 skips, including six
real-PostgreSQL lifecycle scenarios on V1/V2/V3. The receipt records actual
status and incident stream routing, `database:<id>` ordering keys, common
envelope timestamps, deterministic sequence/order assertions, all five actions,
and caught CREATE/UPDATE rollback snapshots. This is focused core evidence;
Normal, Redis, native, final source-manifest, B caller wiring, V4 activation,
and retained-target backfill remain separate gates.

## Historical verification boundary

Task 4's pre-Actual-A PostgreSQL receipt is bound to commit `13e0f09e8eb6d8bd83b639ccffe15e681505fe0e` and records six scenarios with six tests, zero failures, zero errors, and zero skips: V1/V2 startup with missing-V4 rollback; provisional prerequisites and enabled/disabled CREATE defaults; all five actions with policy preservation, resets, four rule closures, pending cancellation, and insertion order; invalid/version/type/target/post-delete guards; safe-integer and payload-overflow guards; and forced outbox rollback for both CREATE and an existing-target UPDATE. The receipt is `.omo/evidence/c-lifecycle-next/task-4-part-c-lifecycle-implementation.xml` in the ignored attempt directory; the teammate-facing selector is `com.example.monitoring.lifecycle.integration.MonitoringLifecyclePostgresIntegrationTest`. This historical receipt is preserved for traceability and is not current Actual-A green evidence; current reconciliation tests must be recorded separately.

The implementation and historical integration tests are evidence for the C boundary only. They do not prove B production CRUD wiring, production V4 activation, or a completed existing-target backfill. Those remain explicit integration-owner gates.
