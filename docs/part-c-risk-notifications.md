# Part C risk and notification backend handoff

This document is the backend contract handoff for the Part C successor. It is
not a deployment approval and does not claim real provider/mobile acceptance.

## Public and internal contracts

The REST surface is the status, policy, and incident sections together with the
notification API in `docs/api.md`. UUID request values are lowercase canonical
strings. `PushInput.expirationTime` is required in JSON and nullable; non-null
values are future epoch milliseconds. Public Push payloads use
`url: "/incidents/<UUID>"`. Endpoint, key, webhook, delivery lease, expiry,
and encryption fields remain internal.

`severityTransition` is required only on an internal
`IncidentUpdatedEvent`, with exactly `INCREASED` or `DECREASED`. Created and
resolved events omit it, and the realtime adapter strips it before STOMP.

## Timing, retries, and lease

For each non-FATAL cooldown job, the immutable logical eligibility is
`eligibleAt = expiresAt - 600 seconds`. V4 physically stores `expires_at` and
`next_attempt_at`; merges, retries, and restarts move only `next_attempt_at`.
At or after `expiresAt`, any send or retry attempt becomes `CANCELLED`.

One process-lifetime PostgreSQL session advisory lease and JVM non-overlap guard
the worker. The worker commits a send snapshot before HTTP, revalidates the
recipient/session immediately before the external attempt, and commits the
outcome afterward. It stops new attempts after lease loss and never holds a
business row lock across HTTP. Provider outcomes are internal retry/cancel
decisions; the backend does not promise exactly-once provider delivery.

## Authentication, retention, and deletion

Push subscriptions are bound to `(sid,user_id)`. Logout, refresh-reuse/expiry,
role/status revocation, and retention cleanup synchronously tombstone them
before the session can be removed. The worker's final check converts a pending
delivery to `CANCELLED` when its session or recipient is no longer usable.
Explicit Push/Slack DELETE synchronously cancels pending deliveries. The API
does not promise an immediate status change for an unrelated in-flight read.

## Configuration and ownership

`RISK_ENABLED`, `REALTIME_ENABLED`, and `NOTIFICATIONS_ENABLED` are independent
false-by-default flags. VAPID configuration uses
`WEB_PUSH_VAPID_PUBLIC_KEY`, `WEB_PUSH_VAPID_PRIVATE_KEY`, and
`WEB_PUSH_VAPID_SUBJECT`; Push host policy uses `PUSH_ALLOWED_HOSTS`. A owns
the existing `database_configs` display writer and V3 `processed_events`; B
continues its status reads. V1-V4 remain immutable. The backend uses one
additive private V5 `notification_success_receipts` compact success receipt;
there is no V5 public DTO/event, no physical `eligible_at` column, and no other
schema change.

The frontend owns service-worker registration, permission UI, same-origin
login-return navigation, browser support, and real-device acceptance. Public
provider/mobile delivery remains an external, unverified acceptance step.

## Retention and late completion

The private receipt preserves the maximum successful opening/increase timestamp
after 30-day delivery-log retention. Backfill consumes only `SENT`
`INCIDENT_OPENED` and `SEVERITY_INCREASED` rows, includes OPEN/RESOLVED and
inactive/tombstoned recipients, and cannot restore purged history. A late
successful callback must retain proof monotonically without overwriting a newer
claim; if it races resolution, it must reconcile one `RECOVERED` notification
inside the original 600-second window. A resolved incident's 180-day cascade
deletes the receipt. Writer drain/fence applies during target-environment
activation of the migration and new binary, with the backfill lock held; it is a
rollout prerequisite rather than a pre-publication action. Source implementation
is documented here; environment runtime, native, and provider acceptance remain
separate evidence gates.

## Verification handoff

After the docs, fixture, and contract test are frozen, run the exact selector
`.\gradlew.bat test --tests
"com.example.monitoring.contract.PartCDocumentationContractTest"` from
`backend`, then run the native QA command in `docs/integration-operations.md`
with caller-supplied tool roots and an evidence directory. Resolve the tested
SHA with `git rev-parse HEAD` at invocation time; keep it in the QA ledger rather
than this document. The contract test must parse all machine-readable examples,
assert public/internal field separation, and verify the 600-second window.

The stale timer checks durable `metric_data.collection_attempt_time` as well as the
last consumed attempt before declaring collection stopped. Only the current
configuration version and attempts between activation and the scan time qualify;
future or preactivation records cannot suppress stale detection. This check shares
the target row lock with A's recorder. It postpones the timer without accepting a
synthetic metric, advancing rule/recovery clocks, or changing connection status.
If collection also stops while Redis is down, the timer still opens the incident
at the last durable attempt plus `staleAfterSeconds`. Candidate filtering uses the
same basis before the batch limit so delivery backlog cannot hide other due targets.
