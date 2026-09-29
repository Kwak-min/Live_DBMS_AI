# Part C staged V4

`V4__part_c_monitoring.sql` is a staged migration. It is intentionally outside
`src/main/resources/db/migration`, so the current application cannot discover or
apply it automatically.

## Dependency gate

The migration may enter Flyway's active location only after the integration branch
contains the final B V2 and A V3 migrations with these stable keys:

| Owner | Table | Required columns and keys used by V4 |
| --- | --- | --- |
| B V2 | `users` | `id BIGINT PRIMARY KEY` |
| B V2 | `auth_sessions` | `sid UUID PRIMARY KEY`, `user_id BIGINT`, and `UNIQUE (sid, user_id)` |
| B V2 | `database_configs` | `id BIGINT PRIMARY KEY` |
| A V3 | `metric_data` | `id BIGINT PRIMARY KEY`, `database_config_id BIGINT`, and `UNIQUE (id, database_config_id)` |

The composite keys prevent a push subscription from pairing a user with another
user's session and prevent a C row from pointing at another target's metric.
`event_outbox` and `processed_events` remain A-owned common infrastructure. Part C
uses them at runtime but this migration does not create or alter them.

After V2 and V3 are final, the A migration owner must verify the prerequisites on a
clean PostgreSQL 16 database, then move this exact file to
`backend/src/main/resources/db/migration/V4__part_c_monitoring.sql`. The staged and
active copies must not coexist, and the file must not be edited after Flyway has
recorded its checksum. Activation also requires an upgrade test from an applied V3
database; the isolated fixture below is not that integration test.

## Isolated probe files

- `test-fixtures/V2_V3_prerequisites.sql` creates only a disposable test schema and
  the minimum prerequisite shapes. It is not a B or A production migration.
- `probes/pre-v4-missing-schema.sql` is the red-phase probe and must fail with
  SQLSTATE `42P01` immediately after the fixture.
- `probes/constraints.sql` checks the seven-table set and fifteen storage
  invariants.
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

The second command is expected to exit nonzero before V4 is applied. The constraint
probe succeeds only when its final row reports `passed_scenarios=15`. Always run the
cleanup command, including after a failed probe.

