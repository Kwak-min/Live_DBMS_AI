# Part C staged V4

`V4__part_c_monitoring.sql` is a staged migration. It is intentionally outside
`src/main/resources/db/migration`, so the current application cannot discover or
apply it automatically.

## Dependency gate

V1, B V2, and Actual A V3 are the active baseline. This staged migration may
enter Flyway's active location only after the B and A owners approve a migration
reconciliation that exposes the two stable composite keys below. This staged
file must not patch already-applied V2/V3 migrations:

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

After the owner-approved V2/V3 migration reconciliation is verified, the A
migration owner must check the prerequisites on a clean PostgreSQL 16 database,
then move this exact file to
`backend/src/main/resources/db/migration/V4__part_c_monitoring.sql`. The staged and
active copies must not coexist, and the file must not be edited after Flyway has
recorded its checksum. Activation also requires an upgrade test from an applied V3
database; the isolated fixture below is not that integration test.

## Isolated probe files

- `test-fixtures/V2_V3_prerequisites.sql` is a disposable mirror of Actual A V3:
  it defines the 11-column `event_outbox` and four-column `processed_events`
  shape, then supplies only the missing composite keys and C's staged schema for
  the isolated probe. It is not a B or A production migration. The separate
  `PartCStagedSchemaTest` also applies real Flyway V1/V2/V3 and verifies the
  staged V4 path against that actual migration inventory.
- `probes/pre-v4-missing-schema.sql` is the red-phase probe and must fail with
  SQLSTATE `42P01` immediately after the fixture.
- `probes/constraints.sql` checks the seven-table set, Actual A outbox shape,
  lifecycle coherence, and the existing storage invariants.
- `probes/cleanup.sql` removes only the `part_c_probe` schema.

Run against a disposable PostgreSQL 16 database, never a shared or production
database:

```powershell
$probeUrl = 'postgresql://postgres@127.0.0.1:55432/postgres'
psql $probeUrl -v ON_ERROR_STOP=1 -f backend/schema/part-c/test-fixtures/V2_V3_prerequisites.sql
psql $probeUrl -v ON_ERROR_STOP=1 -f backend/schema/part-c/probes/pre-v4-missing-schema.sql
psql $probeUrl -v ON_ERROR_STOP=1 -c 'SET search_path TO part_c_probe, pg_catalog' -f backend/schema/part-c/V4__part_c_monitoring.sql
psql $probeUrl -v ON_ERROR_STOP=1 -f backend/schema/part-c/probes/constraints.sql
psql $probeUrl -v ON_ERROR_STOP=1 -f backend/schema/part-c/probes/cleanup.sql
```

The second command is expected to exit nonzero before V4 is applied. The current
schema receipt records six tests with zero failures, errors, and skips, including
the pre-V4 failure, both missing-key rejections, the isolated mirror probe, the
Actual A V1/V2/V3 plus missing-keys path, and the active-inventory check; each
successful constraint probe recorded 21 scenarios. See
`.omo/evidence/c-lifecycle-next/actual-a/schema-reconciliation.md` and
`schema-green-actual-a.xml`. Always run the cleanup command, including after a
failed probe.
