# Part B V2 credential migration runbook

Part B's V2 migration converts legacy plaintext target-database credentials to
AES-256-GCM ciphertext and creates the authentication tables. Part A's V1 is
already in `develop`; do not run V2 on a database with data to preserve until
that database has been backed up and its migration history has been checked.

## Preconditions

- Stop every backend and collector instance that can write `database_configs`.
- Take and verify a PostgreSQL backup.
- Confirm that Part A's V1 is applied first. For a pre-Flyway database with data to
  preserve, confirm the schema and explicit baseline procedure with Part A.
- Keep `baseline-on-migrate=false`; do not let Flyway silently baseline an existing database.
- Set `DB_CONFIG_ENCRYPTION_KEYS`, `DB_CONFIG_ACTIVE_KEY_VERSION`, and `LEGACY_TIME_ZONE`.
- Confirm `LEGACY_TIME_ZONE` is the time zone used by existing access-log timestamps;
  V2 converts those legacy local timestamps to PostgreSQL `timestamptz`.
- Keep every old encryption key available while rows still reference its version.

## Execution

1. Confirm `application.yml` still enables Flyway, disables automatic baselining,
   and sets Hibernate `ddl-auto: validate`.
2. Run the backend once against the backed-up database.
3. V2 locks each legacy row, encrypts username and password independently, and verifies
   an AES-GCM round trip. The original non-null plaintext columns remain until verification.
4. V2 verifies every row is completely encrypted, then drops both plaintext columns
   in the same PostgreSQL migration transaction.
5. Any missing key, partial/mixed credential state, or decryption failure aborts V2.

## Verification and final configuration

- Confirm Flyway reports V2 successful and `username`/`password` no longer exist.
- Confirm all six encrypted credential columns are populated for every active row.
- Start the backend with `DB_CONFIG_VERIFY_ON_STARTUP=true`; startup must fail if a row
  is incomplete or cannot be decrypted.
- Confirm Hibernate `ddl-auto: validate` passes after migration.
- Verify a read-only connection test through the application; never print credentials.

The previous `FLYWAY_ENABLED=false` and `HIBERNATE_DDL_AUTO=update` transitional
settings are obsolete. Do not use them to bypass a migration or schema mismatch.
