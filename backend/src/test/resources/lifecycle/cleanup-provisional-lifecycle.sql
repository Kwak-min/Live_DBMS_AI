DROP TABLE IF EXISTS lifecycle_outbox_insert_ledger CASCADE;
DROP FUNCTION IF EXISTS lifecycle_capture_outbox_insert() CASCADE;
DROP FUNCTION IF EXISTS lifecycle_force_outbox_failure() CASCADE;

DROP TABLE IF EXISTS notification_deliveries CASCADE;
DROP TABLE IF EXISTS notification_webhooks CASCADE;
DROP TABLE IF EXISTS push_subscriptions CASCADE;
DROP TABLE IF EXISTS risk_rule_states CASCADE;
DROP TABLE IF EXISTS incidents CASCADE;
DROP TABLE IF EXISTS risk_policies CASCADE;
DROP TABLE IF EXISTS monitoring_states CASCADE;
DROP TABLE IF EXISTS event_outbox CASCADE;

ALTER TABLE auth_sessions
    DROP CONSTRAINT IF EXISTS auth_sessions_sid_user_unique;
ALTER TABLE metric_data
    DROP CONSTRAINT IF EXISTS metric_data_id_target_unique;
