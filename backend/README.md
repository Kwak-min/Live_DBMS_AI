# Backend

This Spring Boot application runs on JDK 17 with the repository Gradle 8.5 wrapper. Spring Boot remains at 3.2.3 and springdoc at 2.3.0.

## Local runtime

1. Install JDK 17 and Docker Compose.
2. From the repository root, run `./scripts/start-local-services.ps1`. Add `-WithMariaDb` when a target MariaDB 10.11 instance is needed.
3. From `backend`, run `./scripts/run-local.ps1` or set `SPRING_PROFILES_ACTIVE=local` and run `./gradlew.bat bootRun`.
4. Stop infrastructure with `./scripts/stop-local-services.ps1`. Named volumes remain intact.

The compose file publishes PostgreSQL, Redis, and optional MariaDB only on `127.0.0.1`. Redis uses AOF with `appendfsync everysec` and `maxmemory-policy noeviction`. The local profile binds the backend to `127.0.0.1`; staging and production must use the deployment ingress binding and its controls. The application does not load `.env`; `backend/.env.example` is a key and format reference.

Local defaults connect to PostgreSQL at `localhost:5432/monitoring_db` with the compose-only account and to Redis at `localhost:6379` without a password. These defaults exist only in `application-local.yml`. Staging and production must inject all datasource and Redis values, including passwords. The application does not load `.env`; `backend/.env.example` is a key and format reference.

## Migrations

Flyway owns schema creation and Hibernate uses `ddl-auto=validate`. Automatic baselining and Flyway clean are disabled. `V1__baseline_existing_schema.sql` creates exactly the four current legacy entity tables. Existing databases must be backed up, compared with V1, and explicitly baselined at version 1 only after they match; the application never baselines, drops, or rewrites existing data automatically.

V1 deliberately preserves Java `LocalDateTime` as PostgreSQL `timestamp without time zone`. The later A/B data conversion must require `LEGACY_TIME_ZONE`; do not infer an unknown historical zone. Migration ownership is coordinated as V1 legacy/A coordination, V2 Part B, V3 Part A, and V4 Part C. Part C's staged V4 stays outside `classpath:db/migration` until V2 and V3 exist.

## Verification and endpoints

Run service-free tests with `./gradlew.bat test`; `MigrationSchemaTest` starts embedded PostgreSQL 16 and applies every classpath migration before validating all entities.

Actuator health details and components are hidden. Dedicated readiness and liveness groups from the operations contract are not wired in this stage. OpenAPI (`/v3/api-docs`) and Swagger UI (`/swagger-ui.html`) are enabled only in the local profile. Part B authentication and database-security APIs are present; realtime transport and consumer beans remain disabled unless `REALTIME_ENABLED=true`.

The full environment contract and startup order are documented in [integration-operations.md](../docs/integration-operations.md), with security constraints in [integration-security.md](../docs/integration-security.md).

The checked Stage 3 realtime handoff, including the local B candidate status,
fixture-only `processed_events` prerequisite, native STOMP frames, and pending
integration gates, is documented in
[part-c-realtime.md](../docs/part-c-realtime.md).
