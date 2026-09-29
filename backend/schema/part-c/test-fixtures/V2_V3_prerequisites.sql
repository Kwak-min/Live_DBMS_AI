-- TEST-ONLY fixture. This is not an implementation of the B V2 or A V3 migrations.
-- It exposes only the prerequisite keys and columns needed to compile and probe
-- the staged Part C V4 migration in an isolated schema.

DROP SCHEMA IF EXISTS part_c_probe CASCADE;
CREATE SCHEMA part_c_probe;
SET search_path TO part_c_probe, pg_catalog;

CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    CONSTRAINT users_id_safe_check CHECK (id BETWEEN 1 AND 9007199254740991)
);

CREATE TABLE auth_sessions (
    sid UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT auth_sessions_user_fk
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT auth_sessions_sid_user_unique UNIQUE (sid, user_id),
    CONSTRAINT auth_sessions_user_id_safe_check
        CHECK (user_id BETWEEN 1 AND 9007199254740991)
);

CREATE TABLE database_configs (
    id BIGINT PRIMARY KEY,
    config_version BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT database_configs_id_safe_check
        CHECK (id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT database_configs_version_safe_check
        CHECK (config_version BETWEEN 1 AND 9007199254740991)
);

CREATE TABLE metric_data (
    id BIGINT PRIMARY KEY,
    database_config_id BIGINT NOT NULL,
    config_version BIGINT NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT metric_data_target_fk
        FOREIGN KEY (database_config_id) REFERENCES database_configs (id),
    CONSTRAINT metric_data_id_target_unique UNIQUE (id, database_config_id),
    CONSTRAINT metric_data_id_safe_check
        CHECK (id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT metric_data_target_id_safe_check
        CHECK (database_config_id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT metric_data_version_safe_check
        CHECK (config_version BETWEEN 1 AND 9007199254740991)
);

CREATE TABLE event_outbox (
    event_id UUID NOT NULL,
    seq BIGSERIAL NOT NULL,
    event_type VARCHAR(64) NOT NULL CHECK (event_type IN (
        'MetricCollectedEvent',
        'MonitoringStatusChangedEvent',
        'IncidentCreatedEvent',
        'IncidentUpdatedEvent',
        'IncidentResolvedEvent')),
    stream_key VARCHAR(128) NOT NULL,
    ordering_key VARCHAR(128),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error VARCHAR(500),
    PRIMARY KEY (event_id),
    UNIQUE (seq)
);

CREATE TABLE processed_events (
    stream VARCHAR(128) NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (stream, consumer_group, event_id)
);

INSERT INTO users (id) VALUES (201), (202);
INSERT INTO auth_sessions (sid, user_id)
VALUES
    ('00000000-0000-0000-0000-000000000201', 201),
    ('00000000-0000-0000-0000-000000000202', 202);
INSERT INTO database_configs (id, config_version, enabled)
VALUES (101, 1, TRUE), (102, 1, TRUE);
INSERT INTO metric_data (id, database_config_id, config_version, captured_at)
VALUES
    (301, 101, 1, '2026-09-29T00:00:00Z'),
    (302, 102, 1, '2026-09-29T00:00:00Z');
