# Backend

This Spring Boot application runs on JDK 17 with the repository Gradle 8.5 wrapper. Spring Boot remains at 3.2.3 and springdoc at 2.3.0.

## Local runtime

1. Install JDK 17 and Docker Compose.
2. From the repository root, run `./scripts/start-local-services.ps1`. Add `-WithMariaDb` when a target MariaDB 10.11 instance is needed.
3. From `backend`, run `./scripts/run-local.ps1` or set `SPRING_PROFILES_ACTIVE=local` and run `./gradlew.bat bootRun`.
4. Stop infrastructure with `./scripts/stop-local-services.ps1`. Named volumes remain intact.

The canonical `docker-compose.yml` publishes PostgreSQL, Redis, and the optional `mariadb-target` only on `127.0.0.1`. Redis uses AOF with `appendfsync everysec` and `maxmemory-policy noeviction`. The local profile binds the backend to `127.0.0.1`; staging and production must use the deployment ingress binding and its controls. The application does not load `.env`; `backend/.env.example` is a key and format reference.

The checked-in `docker-compose.yml` is the sole local stack with PostgreSQL 16 (`monitoring_db`, `postgres`/`postgres`), Redis 7.4, and a MariaDB 10.11 target on port 13306. Its credentials are local-only and the target is initialized from `infra/local/mariadb-init`. The backend still requires locally generated B signing and encryption keys; never commit those values. CLI authentication calls need `Origin: http://localhost:5173` and the `X-CSRF-Token` returned by `/api/v1/auth/csrf`.

### Frontend handoff (Part B authentication)

The local frontend origin is `http://localhost:5173` (`PUBLIC_ORIGIN`), and the backend listens on `127.0.0.1:8080`. The Vite development server must proxy `/api` and `/ws` to `http://127.0.0.1:8080` (`ws: true` for `/ws`). Frontend requests use relative `/api/v1/...` URLs and same-origin credentials; use `ws://localhost:5173/ws` for STOMP. Do not call port 8080 directly from browser code: the local authentication contract uses a same-origin proxy, not credentialed cross-origin CORS. Use `localhost` consistently in the browser, not `127.0.0.1` for one side and `localhost` for the other.

The `refreshToken` and `csrfSession` cookies are host-only, HttpOnly, `SameSite=Lax`, and scoped to `/api/v1/auth`. Under the `local` profile only, `AUTH_SECURE_COOKIES=false` permits HTTP; other profiles default to Secure cookies and require HTTPS. Both cookie names and values stay out of frontend JavaScript. Fetch `GET /api/v1/auth/csrf` through the proxy before signup/login/refresh/logout; send its returned token as `X-CSRF-Token`. Keep the access token in memory. The backend compares authentication request `Origin` with `PUBLIC_ORIGIN`.

To create the first administrator on an empty local database, start PostgreSQL and Redis, set locally generated `JWT_SIGNING_KEYS`, `JWT_ACTIVE_KID`, `DB_CONFIG_ENCRYPTION_KEYS`, and `DB_CONFIG_ACTIVE_KEY_VERSION` in the backend process environment (see `.env.example`), then set `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`, and `BOOTSTRAP_ADMIN_DISPLAY_NAME`. Run `SPRING_PROFILES_ACTIVE=local,bootstrap-admin` with `./gradlew.bat bootRun` from `backend`; this one-shot process does not open HTTP. Remove the three bootstrap variables and start the normal server with `./scripts/run-local.ps1`. There is no checked-in seed account. Ordinary signup creates a USER account. Never put actual signing, encryption, or admin secrets in source files or shared messages.

The B user and database response DTOs expose `Instant` timestamps, and the shared JSON mapper writes `YYYY-MM-DDTHH:mm:ss.SSSZ`. This is covered by `DatabaseResponseTest`; the frontend should still confirm the value against a live response during integration. `VITE_USE_MOCK=false` and the Vite proxy configuration belong to the frontend project, which is not stored in this repository.

Local defaults connect to PostgreSQL at `localhost:5432/monitoring_db` as `postgres`/`postgres` and to Redis at `localhost:6379` without a password. These defaults exist only in `application-local.yml`. Staging and production must inject all datasource and Redis values, including passwords. The application does not load `.env`; `backend/.env.example` is a key and format reference.

## Migrations

Flyway owns schema creation and Hibernate uses `ddl-auto=validate`. Automatic baselining and Flyway clean are disabled. `V1__baseline_existing_schema.sql` creates exactly the four current legacy entity tables. Existing databases must be backed up, compared with V1, and explicitly baselined at version 1 only after they match; the application never baselines, drops, or rewrites existing data automatically.

V1 deliberately preserves historical Java `LocalDateTime` as PostgreSQL `timestamp without time zone`. A's active V3 requires `LEGACY_TIME_ZONE` when legacy metric rows exist and converts them to UTC instants; do not infer an unknown historical zone. Migration ownership is coordinated as V1 legacy/A coordination, V2 Part B, V3 Part A, and active V4 Part C. V4 acquires the target-table lock, validates retained rows, creates the C tables, and backfills exactly one state and version-1 policy per target in one transaction. Invalid retained rows abort the migration without normalization.

The B database write service requires `MonitoringLifecyclePort` and calls it synchronously inside the row-locked write transaction. Lifecycle failures propagate and roll back the B target, audit, C rows, and common outbox together. The C metric-driven state consumer owns live status updates; B response/status reads and A's existing four-column `database_configs` display writer remain the compatibility boundary during rollout.

## Common outbox

Internal Redis events are recorded with `common.outbox.OutboxWriter` in the same transaction as business data. The writer adds `schemaVersion`, `eventId`, `eventType`, and `publishedAt`, enforces an object payload and the 64 KiB cap, and routes each `OutboxEventType` to its configured stream. Producers pass body fields only and use an ordering key such as `database:12` when per-target order matters. `OutboxPublisher` publishes the single Redis hash field `payload` and retries failures with bounded backoff. Consumers call `ProcessedEventStore.markProcessed` in their business transaction and XACK after commit. `CollectorHeartbeatEvent` is sent directly because stale heartbeats must not be replayed.

Spring's shared `ObjectMapper` applies `UtcInstantJacksonConfig`, so event and REST instants use fixed three-digit UTC milliseconds. Do not create a separate mapper bean.

## Verification and endpoints

Run service-free tests with `./gradlew.bat test`; `MigrationSchemaTest` starts embedded PostgreSQL 16 and applies every classpath migration before validating all entities.

Actuator health details and components are hidden. Dedicated readiness and liveness groups from the operations contract are not wired in this stage. OpenAPI (`/v3/api-docs`) and Swagger UI (`/swagger-ui.html`) are enabled only in the local profile. Part B authentication and database-security APIs are present; realtime transport and consumer beans remain disabled unless `REALTIME_ENABLED=true`.

The full environment contract and startup order are documented in [integration-operations.md](../docs/integration-operations.md), with security constraints in [integration-security.md](../docs/integration-security.md).

The checked Stage 3 realtime handoff, including active V4, native STOMP frames,
and the deferred metric-driven live-state boundary, is documented in
[part-c-realtime.md](../docs/part-c-realtime.md).

For activation, stop and drain old application writers before applying V4; hold
the database lock until the migration commit completes, then start the new
binary before allowing writes. This repository documents the handoff only; no
shared deployment is claimed here.

## Part C backend handoff

The backend contract and the browser handoff are documented in
[`docs/part-c-risk-notifications.md`](../docs/part-c-risk-notifications.md),
[`docs/api.md`](../docs/api.md), [`docs/events.md`](../docs/events.md), and
[`docs/integration-handoff.md`](../docs/integration-handoff.md). The three
activation flags are fail-closed and independent: `RISK_ENABLED`,
`REALTIME_ENABLED`, and `NOTIFICATIONS_ENABLED` default to `false`. Realtime
still requires A V3 `processed_events`; notification delivery uses the
process-lifetime PostgreSQL advisory lease and revalidates the recipient
session immediately before an external attempt.

The frontend owns the user gesture, permission prompt, service-worker
registration, same-origin incident navigation, and browser/device acceptance.
The backend provides the public DTO/STOMP/Push contracts and local provider
fixtures only. This repository does not claim shared deployment, exactly-once
provider delivery, or real mobile/provider acceptance.
