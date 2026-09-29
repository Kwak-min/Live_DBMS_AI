# C lifecycle contract handoff

This handoff delivers the dependency-free C boundary only. B can import, inject, and test it now with a test-only double. It does not provide a Spring bean, persistence implementation, migration, event publisher, or runtime B CRUD integration. Activation of real C persistence follows B V2, A V3, and then C V4; V4 remains staged until then.

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

The exact method is `void applyChange(TargetChange change)`. It is synchronous and has no checked error type. A C implementation may throw a runtime failure; B must allow that failure to reach the existing transaction boundary so the database mutation, C lifecycle change, audit, and outbox work roll back together.

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

B calls `applyChange` from the existing row-locked database write transaction. The call must stay synchronous. C must update its lifecycle state, default policy/state version, administrative incident closures, and any transactional outbox rows in that same transaction. C must not call Redis, HTTP, Web Push, or Slack from this method. Any C runtime error propagates to B and causes rollback.

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

## Remaining real implementation

C still needs a production `MonitoringLifecyclePort` implementation, lifecycle persistence for `monitoring_states` and `risk_policies`, state-version handling, deletion tombstones, administrative incident resolution reasons, and transaction/outbox coverage. B can compile and test-wire this port now; production persistence and V4 migration remain staged until B V2 and A V3. B still needs to inject the port and call it at each mapped mutation while holding its row lock. This contract alone does not make B CRUD and C monitoring atomic in the running application.
