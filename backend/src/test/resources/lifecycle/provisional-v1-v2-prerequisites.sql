-- TEST-ONLY prerequisite fixture.
-- Actual A V3 owns event_outbox and processed_events. This file adds only the
-- two composite keys still absent from actual V2/V3 and must never be
-- registered with Flyway.

ALTER TABLE auth_sessions
    ADD CONSTRAINT auth_sessions_sid_user_unique UNIQUE (sid, user_id);

ALTER TABLE metric_data
    ADD CONSTRAINT metric_data_id_target_unique UNIQUE (id, database_config_id);
