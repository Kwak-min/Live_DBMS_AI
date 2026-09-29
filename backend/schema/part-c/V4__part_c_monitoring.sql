CREATE TABLE monitoring_states (
    database_config_id BIGINT PRIMARY KEY,
    config_version BIGINT NOT NULL,
    state_version BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    connection_status VARCHAR(16) NOT NULL,
    data_freshness VARCHAR(16) NOT NULL,
    risk_level VARCHAR(16),
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    latest_metric_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT monitoring_states_target_fk
        FOREIGN KEY (database_config_id) REFERENCES database_configs (id),
    CONSTRAINT monitoring_states_metric_fk
        FOREIGN KEY (latest_metric_id, database_config_id)
        REFERENCES metric_data (id, database_config_id)
        ON DELETE SET NULL (latest_metric_id),
    CONSTRAINT monitoring_states_target_id_safe_check
        CHECK (database_config_id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT monitoring_states_config_version_safe_check
        CHECK (config_version BETWEEN 1 AND 9007199254740991),
    CONSTRAINT monitoring_states_state_version_safe_check
        CHECK (state_version BETWEEN 1 AND 9007199254740991),
    CONSTRAINT monitoring_states_metric_id_safe_check
        CHECK (latest_metric_id IS NULL OR latest_metric_id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT monitoring_states_connection_status_check
        CHECK (connection_status IN ('UP', 'DOWN', 'UNKNOWN')),
    CONSTRAINT monitoring_states_data_freshness_check
        CHECK (data_freshness IN ('FRESH', 'STALE', 'NO_DATA', 'PAUSED')),
    CONSTRAINT monitoring_states_risk_level_check
        CHECK (risk_level IS NULL OR risk_level IN ('INFO', 'WARNING', 'CRITICAL', 'FATAL')),
    CONSTRAINT monitoring_states_lifecycle_check
        CHECK (
            (enabled AND NOT deleted AND data_freshness <> 'PAUSED')
            OR (NOT enabled AND data_freshness = 'PAUSED' AND risk_level IS NULL)
        ),
    CONSTRAINT monitoring_states_attempt_time_check
        CHECK (last_success_at IS NULL OR last_attempt_at IS NULL OR last_success_at <= last_attempt_at)
);

CREATE TABLE risk_policies (
    database_config_id BIGINT PRIMARY KEY,
    version BIGINT NOT NULL,
    rules JSONB NOT NULL,
    stale_after_seconds INTEGER NOT NULL,
    notification_cooldown_seconds INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT risk_policies_target_fk
        FOREIGN KEY (database_config_id) REFERENCES database_configs (id),
    CONSTRAINT risk_policies_target_id_safe_check
        CHECK (database_config_id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT risk_policies_version_safe_check
        CHECK (version BETWEEN 1 AND 9007199254740991),
    CONSTRAINT risk_policies_rules_array_check
        CHECK (jsonb_typeof(rules) = 'array'),
    CONSTRAINT risk_policies_stale_range_check
        CHECK (stale_after_seconds BETWEEN 30 AND 300),
    CONSTRAINT risk_policies_cooldown_range_check
        CHECK (notification_cooldown_seconds BETWEEN 60 AND 3600),
    CONSTRAINT risk_policies_timestamps_check
        CHECK (updated_at >= created_at)
);

CREATE TABLE incidents (
    incident_id UUID PRIMARY KEY,
    database_config_id BIGINT NOT NULL,
    database_name VARCHAR(100) NOT NULL,
    rule_id VARCHAR(64) NOT NULL,
    rule_type VARCHAR(40) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    resolution_reason VARCHAR(32),
    metric_name VARCHAR(100) NOT NULL,
    metric_value NUMERIC,
    threshold_value NUMERIC,
    source_metric_id BIGINT,
    source_event_id UUID,
    message TEXT NOT NULL,
    incident_version BIGINT NOT NULL,
    CONSTRAINT incidents_target_fk
        FOREIGN KEY (database_config_id) REFERENCES database_configs (id),
    CONSTRAINT incidents_source_metric_fk
        FOREIGN KEY (source_metric_id, database_config_id)
        REFERENCES metric_data (id, database_config_id)
        ON DELETE SET NULL (source_metric_id),
    CONSTRAINT incidents_target_id_safe_check
        CHECK (database_config_id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT incidents_metric_id_safe_check
        CHECK (source_metric_id IS NULL OR source_metric_id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT incidents_version_safe_check
        CHECK (incident_version BETWEEN 1 AND 9007199254740991),
    CONSTRAINT incidents_database_name_check
        CHECK (char_length(btrim(database_name)) BETWEEN 1 AND 100),
    CONSTRAINT incidents_rule_id_check
        CHECK (rule_id IN ('CONNECTION_RATIO', 'SLOW_QUERY_RATE', 'CONNECTION_FAILURE', 'COLLECTION_STALE')),
    CONSTRAINT incidents_rule_type_check
        CHECK (rule_type IN ('CONNECTION_RATIO_EXCEEDED', 'SLOW_QUERIES_HIGH', 'CONNECTION_FAILURE', 'COLLECTION_STALE')),
    CONSTRAINT incidents_severity_check
        CHECK (severity IN ('WARNING', 'CRITICAL', 'FATAL')),
    CONSTRAINT incidents_status_check
        CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT incidents_resolution_reason_check
        CHECK (
            resolution_reason IS NULL
            OR resolution_reason IN (
                'RECOVERED',
                'POLICY_CHANGED',
                'MONITORING_PAUSED',
                'CONFIG_CHANGED',
                'TARGET_DELETED'
            )
        ),
    CONSTRAINT incidents_resolution_coherence_check
        CHECK (
            (status = 'OPEN' AND resolved_at IS NULL AND resolution_reason IS NULL)
            OR (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution_reason IS NOT NULL)
        ),
    CONSTRAINT incidents_observation_time_check
        CHECK (
            opened_at <= last_observed_at
            AND (resolved_at IS NULL OR resolved_at >= last_observed_at)
        ),
    CONSTRAINT incidents_metric_name_check
        CHECK (char_length(btrim(metric_name)) BETWEEN 1 AND 100),
    CONSTRAINT incidents_metric_value_check
        CHECK (metric_value IS NULL OR (metric_value >= 0 AND metric_value < 'Infinity'::NUMERIC)),
    CONSTRAINT incidents_threshold_value_check
        CHECK (threshold_value IS NULL OR (threshold_value >= 0 AND threshold_value < 'Infinity'::NUMERIC)),
    CONSTRAINT incidents_message_check
        CHECK (char_length(btrim(message)) > 0)
);

CREATE UNIQUE INDEX incidents_one_open_rule_idx
    ON incidents (database_config_id, rule_id)
    WHERE status = 'OPEN';

CREATE INDEX incidents_opened_order_idx
    ON incidents (opened_at DESC, incident_id ASC);

CREATE INDEX incidents_target_opened_idx
    ON incidents (database_config_id, opened_at DESC, incident_id ASC);

CREATE TABLE risk_rule_states (
    database_config_id BIGINT NOT NULL,
    rule_id VARCHAR(64) NOT NULL,
    warning_candidate_since TIMESTAMPTZ,
    critical_candidate_since TIMESTAMPTZ,
    fatal_candidate_since TIMESTAMPTZ,
    recovery_since TIMESTAMPTZ,
    last_observed_at TIMESTAMPTZ,
    last_metric_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (database_config_id, rule_id),
    CONSTRAINT risk_rule_states_target_fk
        FOREIGN KEY (database_config_id) REFERENCES database_configs (id),
    CONSTRAINT risk_rule_states_metric_fk
        FOREIGN KEY (last_metric_id, database_config_id)
        REFERENCES metric_data (id, database_config_id)
        ON DELETE SET NULL (last_metric_id),
    CONSTRAINT risk_rule_states_target_id_safe_check
        CHECK (database_config_id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT risk_rule_states_metric_id_safe_check
        CHECK (last_metric_id IS NULL OR last_metric_id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT risk_rule_states_rule_id_check
        CHECK (rule_id IN ('CONNECTION_RATIO', 'SLOW_QUERY_RATE', 'CONNECTION_FAILURE', 'COLLECTION_STALE')),
    CONSTRAINT risk_rule_states_observation_check
        CHECK (
            (last_metric_id IS NULL OR last_observed_at IS NOT NULL)
            AND (warning_candidate_since IS NULL OR last_observed_at IS NOT NULL)
            AND (critical_candidate_since IS NULL OR last_observed_at IS NOT NULL)
            AND (fatal_candidate_since IS NULL OR last_observed_at IS NOT NULL)
            AND (recovery_since IS NULL OR last_observed_at IS NOT NULL)
            AND (warning_candidate_since IS NULL OR warning_candidate_since <= last_observed_at)
            AND (critical_candidate_since IS NULL OR critical_candidate_since <= last_observed_at)
            AND (fatal_candidate_since IS NULL OR fatal_candidate_since <= last_observed_at)
            AND (recovery_since IS NULL OR recovery_since <= last_observed_at)
        )
);

CREATE TABLE push_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    sid UUID,
    endpoint_hash BYTEA NOT NULL,
    payload_key_version INTEGER NOT NULL,
    payload_nonce BYTEA NOT NULL,
    payload_ciphertext BYTEA NOT NULL,
    expiration_time BIGINT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT push_subscriptions_user_fk
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT push_subscriptions_session_owner_fk
        FOREIGN KEY (sid, user_id) REFERENCES auth_sessions (sid, user_id)
        ON DELETE SET NULL (sid),
    CONSTRAINT push_subscriptions_id_safe_check
        CHECK (id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT push_subscriptions_user_id_safe_check
        CHECK (user_id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT push_subscriptions_endpoint_hash_check
        CHECK (octet_length(endpoint_hash) = 32),
    CONSTRAINT push_subscriptions_key_version_check
        CHECK (payload_key_version > 0),
    CONSTRAINT push_subscriptions_nonce_check
        CHECK (octet_length(payload_nonce) = 12),
    CONSTRAINT push_subscriptions_ciphertext_check
        CHECK (octet_length(payload_ciphertext) > 16),
    CONSTRAINT push_subscriptions_expiration_check
        CHECK (expiration_time IS NULL OR expiration_time BETWEEN 1 AND 9007199254740991),
    CONSTRAINT push_subscriptions_active_session_check
        CHECK (NOT enabled OR (deleted_at IS NULL AND sid IS NOT NULL)),
    CONSTRAINT push_subscriptions_tombstone_check
        CHECK (deleted_at IS NULL OR NOT enabled),
    CONSTRAINT push_subscriptions_timestamps_check
        CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX push_subscriptions_active_endpoint_idx
    ON push_subscriptions (endpoint_hash)
    WHERE enabled AND deleted_at IS NULL;

CREATE INDEX push_subscriptions_user_active_idx
    ON push_subscriptions (user_id, id)
    WHERE enabled AND deleted_at IS NULL;

CREATE TABLE notification_webhooks (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    provider VARCHAR(16) NOT NULL,
    url_key_version INTEGER NOT NULL,
    url_nonce BYTEA NOT NULL,
    url_ciphertext BYTEA NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT notification_webhooks_id_safe_check
        CHECK (id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT notification_webhooks_name_check
        CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT notification_webhooks_provider_check
        CHECK (provider = 'SLACK'),
    CONSTRAINT notification_webhooks_key_version_check
        CHECK (url_key_version > 0),
    CONSTRAINT notification_webhooks_nonce_check
        CHECK (octet_length(url_nonce) = 12),
    CONSTRAINT notification_webhooks_ciphertext_check
        CHECK (octet_length(url_ciphertext) > 16),
    CONSTRAINT notification_webhooks_tombstone_check
        CHECK (deleted_at IS NULL OR NOT enabled),
    CONSTRAINT notification_webhooks_timestamps_check
        CHECK (updated_at >= created_at)
);

CREATE TABLE notification_deliveries (
    id BIGSERIAL PRIMARY KEY,
    incident_id UUID NOT NULL,
    incident_version BIGINT NOT NULL,
    notification_type VARCHAR(32) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    push_subscription_id BIGINT,
    notification_webhook_id BIGINT,
    recipient_id BIGINT GENERATED ALWAYS AS (
        COALESCE(push_subscription_id, notification_webhook_id)
    ) STORED,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    last_error_code VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    CONSTRAINT notification_deliveries_incident_fk
        FOREIGN KEY (incident_id) REFERENCES incidents (incident_id),
    CONSTRAINT notification_deliveries_push_fk
        FOREIGN KEY (push_subscription_id) REFERENCES push_subscriptions (id),
    CONSTRAINT notification_deliveries_webhook_fk
        FOREIGN KEY (notification_webhook_id) REFERENCES notification_webhooks (id),
    CONSTRAINT notification_deliveries_id_safe_check
        CHECK (id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT notification_deliveries_version_safe_check
        CHECK (incident_version BETWEEN 1 AND 9007199254740991),
    CONSTRAINT notification_deliveries_recipient_id_safe_check
        CHECK (recipient_id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT notification_deliveries_type_check
        CHECK (notification_type IN ('INCIDENT_OPENED', 'SEVERITY_INCREASED', 'INCIDENT_RESOLVED')),
    CONSTRAINT notification_deliveries_channel_recipient_check
        CHECK (
            (channel = 'WEB_PUSH' AND push_subscription_id IS NOT NULL AND notification_webhook_id IS NULL)
            OR (channel = 'SLACK' AND push_subscription_id IS NULL AND notification_webhook_id IS NOT NULL)
        ),
    CONSTRAINT notification_deliveries_status_check
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    CONSTRAINT notification_deliveries_attempt_count_check
        CHECK (attempt_count >= 0),
    CONSTRAINT notification_deliveries_error_code_check
        CHECK (
            last_error_code IS NULL
            OR last_error_code IN ('TIMEOUT', 'RATE_LIMITED', 'RECIPIENT_GONE', 'REJECTED', 'PROVIDER_ERROR')
        ),
    CONSTRAINT notification_deliveries_state_check
        CHECK (
            (status = 'PENDING' AND next_attempt_at IS NOT NULL AND sent_at IS NULL)
            OR (status = 'SENT' AND next_attempt_at IS NULL AND sent_at IS NOT NULL AND last_error_code IS NULL)
            OR (status IN ('FAILED', 'CANCELLED') AND next_attempt_at IS NULL AND sent_at IS NULL)
        ),
    CONSTRAINT notification_deliveries_failed_error_check
        CHECK (status <> 'FAILED' OR last_error_code IS NOT NULL),
    CONSTRAINT notification_deliveries_expiry_check
        CHECK (
            expires_at > created_at
            AND (next_attempt_at IS NULL OR next_attempt_at <= expires_at)
            AND (sent_at IS NULL OR sent_at >= created_at)
        )
);

CREATE UNIQUE INDEX notification_deliveries_dedup_idx
    ON notification_deliveries (incident_id, incident_version, channel, recipient_id);

CREATE INDEX notification_deliveries_created_order_idx
    ON notification_deliveries (created_at DESC, id DESC);

CREATE INDEX notification_deliveries_pending_due_idx
    ON notification_deliveries (next_attempt_at, id)
    WHERE status = 'PENDING';
