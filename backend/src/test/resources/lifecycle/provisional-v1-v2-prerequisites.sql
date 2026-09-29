-- TEST-ONLY PROVISIONAL FIXTURE.
-- This file does not implement or replace A V3, does not patch B V2, and must
-- never be registered with Flyway. It exposes only the prerequisites needed to
-- execute the staged Part C V4 against a disposable V1+V2 integration database.

ALTER TABLE auth_sessions
    ADD CONSTRAINT auth_sessions_sid_user_unique UNIQUE (sid, user_id);

ALTER TABLE metric_data
    ADD CONSTRAINT metric_data_id_target_unique UNIQUE (id, database_config_id);

CREATE TABLE event_outbox (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL
);
