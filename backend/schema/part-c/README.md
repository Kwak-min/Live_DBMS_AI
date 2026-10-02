# Part C active V4

`V4__part_c_monitoring.sql` is the active Flyway migration at
`src/main/resources/db/migration/V4__part_c_monitoring.sql`. There is no second
staged production copy. Flyway applies V1, V2, V3, and V4 in order; Hibernate
continues to validate the resulting schema.

## Dependency gate

V1, B V2, and Actual A V3 are the forward-only baseline. V4 declares the two
stable composite keys below before creating C foreign keys; it does not patch
already-applied V2/V3 migrations:

| Owner | Table | Required columns and keys used by V4 |
| --- | --- | --- |
| B V2 | `users` | `id BIGINT PRIMARY KEY` |
| B V2 | `auth_sessions` | `sid UUID PRIMARY KEY`, `user_id BIGINT`, and `UNIQUE (sid, user_id)` |
| B V2 | `database_configs` | `id BIGINT PRIMARY KEY` |
| A V3 | `metric_data` | `id BIGINT PRIMARY KEY`, `database_config_id BIGINT`, and `UNIQUE (id, database_config_id)` |

The composite keys prevent a push subscription from pairing a user with another
user's session and prevent a C row from pointing at another target's metric.
Actual A V3 owns the common 11-column `event_outbox` (including its sequence, stream,
ordering, retry, and timestamp columns) and `processed_events`. Part C uses
those tables through the common interfaces but this migration does not create
or alter them. C lifecycle events use body-only payloads, `database:<id>` as
their ordering key, and the common writer's envelope creation time is distinct
from the lifecycle occurrence time in the body.

At the start of V4, PostgreSQL locks `database_configs` and rejects the first
retained row with an out-of-range ID/version or an enabled soft-deleted state.
The migration creates the seven C tables and, using one
`date_trunc('milliseconds', transaction_timestamp())` epoch, inserts exactly one
state and one version-1 default policy for every target at its actual
`config_version`. Enabled nondeleted targets start at `NO_DATA` with that epoch;
disabled or deleted targets start at `PAUSED` with no activation. Historical
metrics are preserved but do not seed C attempt/success/latest-metric fields, and
migration initialization emits no lifecycle outbox rows. Any postcondition or
retained-row failure aborts all V4 DDL and data.

## Constraint probe files

- `probes/catalog.sql` reports the active seven-table set, nullable activation
  type, common A outbox/processed-event shape, foreign-key count, and required
  unique indexes.
- `probes/constraints.sql` checks the active seven-table set, activation
  coherence, common A shape, cross-target metric rejection, incident and
  recipient invariants, policy/version bounds, and metric-retention evidence.
- `probes/cleanup.sql` removes only the disposable `part_c_probe` schema.

Run these probes only against a disposable PostgreSQL 16 database, never a
shared or production database. The database must already contain active V1-V4
and the probe's `part_c_probe` search path. Keep the cleanup command in the
finally path, including after a failed probe:

```powershell
$probeUrl = 'postgresql://postgres@127.0.0.1:55432/postgres'
psql $probeUrl -v ON_ERROR_STOP=1 -f backend/schema/part-c/probes/catalog.sql
psql $probeUrl -v ON_ERROR_STOP=1 -f backend/schema/part-c/probes/constraints.sql
psql $probeUrl -v ON_ERROR_STOP=1 -f backend/schema/part-c/probes/cleanup.sql
```

The probes inspect the active V4 result and do not create a fallback schema,
seed historical metrics, or claim Docker/shared-deployment execution. Current
active-V4 migration and lifecycle receipts belong under
`.omo/evidence/c-v4-finish/`.
