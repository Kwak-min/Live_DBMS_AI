SET search_path TO part_c_probe, pg_catalog;

CREATE TEMP TABLE part_c_probe_results (
    scenario TEXT PRIMARY KEY
);

DO $probe$
DECLARE
    actual_tables TEXT[];
    expected_tables TEXT[] := ARRAY[
        'incidents',
        'monitoring_states',
        'notification_deliveries',
        'notification_webhooks',
        'push_subscriptions',
        'risk_policies',
        'risk_rule_states'
    ];
BEGIN
    SELECT array_agg(table_name ORDER BY table_name)
    INTO actual_tables
    FROM information_schema.tables
    WHERE table_schema = 'part_c_probe'
      AND table_type = 'BASE TABLE'
      AND table_name NOT IN (
          'users', 'auth_sessions', 'used_refresh_tokens',
          'database_configs', 'metric_data', 'blocked_reasons',
          'audit_logs', 'access_logs', 'event_outbox', 'processed_events',
          'flyway_schema_history'
      );

    IF actual_tables IS DISTINCT FROM expected_tables THEN
        RAISE EXCEPTION 'unexpected Part C tables: %', actual_tables;
    END IF;

    INSERT INTO part_c_probe_results VALUES ('exact_seven_part_c_tables');
END
$probe$;

DO $probe$
DECLARE
    clock_count INTEGER;
BEGIN
    SELECT count(*)
    INTO clock_count
    FROM information_schema.columns
    WHERE table_schema = 'part_c_probe'
      AND table_name = 'risk_rule_states'
      AND column_name IN (
          'warning_candidate_since',
          'critical_candidate_since',
          'fatal_candidate_since'
      );

    IF clock_count <> 3 THEN
        RAISE EXCEPTION 'expected three independent candidate clocks, found %', clock_count;
    END IF;

    INSERT INTO part_c_probe_results VALUES ('independent_candidate_clocks');
END
$probe$;

DO $probe$
DECLARE
    activation_type TEXT;
    activation_nullable TEXT;
BEGIN
    SELECT udt_name, is_nullable
    INTO activation_type, activation_nullable
    FROM information_schema.columns
    WHERE table_schema = 'part_c_probe'
      AND table_name = 'monitoring_states'
      AND column_name = 'activation_at';

    IF activation_type <> 'timestamptz' OR activation_nullable <> 'YES' THEN
        RAISE EXCEPTION 'activation_at must be nullable TIMESTAMPTZ: %, %',
            activation_type, activation_nullable;
    END IF;

    INSERT INTO part_c_probe_results VALUES ('activation_at_nullable_timestamptz');
END
$probe$;

DO $probe$
DECLARE
    outbox_columns TEXT[];
    processed_columns TEXT[];
    typed_columns INTEGER;
BEGIN
    SELECT array_agg(column_name ORDER BY ordinal_position)
    INTO outbox_columns
    FROM information_schema.columns
    WHERE table_schema = 'part_c_probe'
      AND table_name = 'event_outbox';

    SELECT array_agg(column_name ORDER BY ordinal_position)
    INTO processed_columns
    FROM information_schema.columns
    WHERE table_schema = 'part_c_probe'
      AND table_name = 'processed_events';

    SELECT count(*)
    INTO typed_columns
    FROM information_schema.columns
    WHERE table_schema = 'part_c_probe'
      AND table_name = 'event_outbox'
      AND ((column_name = 'payload' AND udt_name = 'jsonb')
           OR (column_name IN ('created_at', 'published_at', 'next_attempt_at')
               AND udt_name = 'timestamptz'));

    IF outbox_columns IS DISTINCT FROM ARRAY[
        'event_id', 'seq', 'event_type', 'stream_key', 'ordering_key', 'payload',
        'created_at', 'published_at', 'attempts', 'next_attempt_at', 'last_error'
    ] OR processed_columns IS DISTINCT FROM ARRAY[
        'stream', 'consumer_group', 'event_id', 'processed_at'
    ] OR typed_columns <> 4 THEN
        RAISE EXCEPTION 'unexpected A common shape: outbox %, processed %, typed columns %',
            outbox_columns, processed_columns, typed_columns;
    END IF;

    INSERT INTO part_c_probe_results VALUES ('event_outbox_actual_eleven_columns');
END
$probe$;

INSERT INTO monitoring_states (
    database_config_id,
    config_version,
    state_version,
    enabled,
    deleted,
    connection_status,
    data_freshness,
    risk_level,
    activation_at,
    last_attempt_at,
    last_success_at,
    latest_metric_id,
    updated_at
) VALUES (
    101,
    1,
    1,
    TRUE,
    FALSE,
    'UP',
    'FRESH',
    'WARNING',
    '2026-09-29T00:00:00Z',
    '2026-09-29T00:00:10Z',
    '2026-09-29T00:00:10Z',
    301,
    '2026-09-29T00:00:10Z'
);

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        UPDATE monitoring_states
        SET latest_metric_id = 302
        WHERE database_config_id = 101;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23503' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'monitoring state accepted a metric owned by another target';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('cross_target_metric_rejected');
END
$probe$;

INSERT INTO risk_policies (
    database_config_id,
    version,
    rules,
    stale_after_seconds,
    notification_cooldown_seconds,
    created_at,
    updated_at
) VALUES (
    101,
    1,
    '[]'::JSONB,
    60,
    300,
    '2026-09-29T00:00:00Z',
    '2026-09-29T00:00:00Z'
);

INSERT INTO risk_rule_states (
    database_config_id,
    rule_id,
    warning_candidate_since,
    critical_candidate_since,
    fatal_candidate_since,
    recovery_since,
    last_observed_at,
    last_metric_id,
    updated_at
) VALUES (
    101,
    'CONNECTION_RATIO',
    '2026-09-29T00:00:00Z',
    '2026-09-29T00:00:05Z',
    NULL,
    NULL,
    '2026-09-29T00:00:10Z',
    301,
    '2026-09-29T00:00:10Z'
);

INSERT INTO incidents (
    incident_id,
    database_config_id,
    database_name,
    rule_id,
    rule_type,
    severity,
    status,
    opened_at,
    last_observed_at,
    metric_name,
    metric_value,
    threshold_value,
    source_metric_id,
    source_event_id,
    message,
    incident_version
) VALUES (
    '11111111-1111-1111-1111-111111111111',
    101,
    'Primary database',
    'CONNECTION_RATIO',
    'CONNECTION_RATIO_EXCEEDED',
    'WARNING',
    'OPEN',
    '2026-09-29T00:00:00Z',
    '2026-09-29T00:00:10Z',
    'activeConnectionsRatio',
    0.82,
    0.80,
    301,
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    'Connection ratio exceeded its warning threshold.',
    1
);

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO incidents (
            incident_id,
            database_config_id,
            database_name,
            rule_id,
            rule_type,
            severity,
            status,
            opened_at,
            last_observed_at,
            metric_name,
            message,
            incident_version
        ) VALUES (
            '22222222-2222-2222-2222-222222222222',
            101,
            'Primary database',
            'CONNECTION_RATIO',
            'CONNECTION_RATIO_EXCEEDED',
            'CRITICAL',
            'OPEN',
            '2026-09-29T00:00:11Z',
            '2026-09-29T00:00:11Z',
            'activeConnectionsRatio',
            'Duplicate open incident.',
            1
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23505' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'duplicate OPEN incident was accepted';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('open_incident_duplicate_rejected');
END
$probe$;

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO incidents (
            incident_id,
            database_config_id,
            database_name,
            rule_id,
            rule_type,
            severity,
            status,
            opened_at,
            last_observed_at,
            metric_name,
            message,
            incident_version
        ) VALUES (
            '33333333-3333-3333-3333-333333333333',
            102,
            'Replica database',
            'SLOW_QUERY_RATE',
            'SLOW_QUERIES_HIGH',
            'WARNING',
            'RESOLVED',
            '2026-09-29T00:00:00Z',
            '2026-09-29T00:00:10Z',
            'slowQueriesPerSecond',
            'Resolved row without resolution evidence.',
            1
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23514' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'RESOLVED incident without resolution evidence was accepted';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('resolved_incident_requires_evidence');
END
$probe$;

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO incidents (
            incident_id,
            database_config_id,
            database_name,
            rule_id,
            rule_type,
            severity,
            status,
            opened_at,
            last_observed_at,
            resolved_at,
            resolution_reason,
            metric_name,
            message,
            incident_version
        ) VALUES (
            '44444444-4444-4444-4444-444444444444',
            102,
            'Replica database',
            'SLOW_QUERY_RATE',
            'SLOW_QUERIES_HIGH',
            'WARNING',
            'OPEN',
            '2026-09-29T00:00:00Z',
            '2026-09-29T00:00:10Z',
            '2026-09-29T00:00:10Z',
            'RECOVERED',
            'slowQueriesPerSecond',
            'Open row with resolution evidence.',
            1
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23514' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'OPEN incident with resolution evidence was accepted';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('open_incident_rejects_resolution');
END
$probe$;

INSERT INTO push_subscriptions (
    id,
    user_id,
    sid,
    endpoint_hash,
    payload_key_version,
    payload_nonce,
    payload_ciphertext,
    expiration_time,
    enabled
) VALUES (
    401,
    201,
    '00000000-0000-0000-0000-000000000201',
    decode(repeat('ab', 32), 'hex'),
    1,
    decode(repeat('01', 12), 'hex'),
    decode(repeat('02', 17), 'hex'),
    1790640000000,
    TRUE
);

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO push_subscriptions (
            id,
            user_id,
            sid,
            endpoint_hash,
            payload_key_version,
            payload_nonce,
            payload_ciphertext,
            enabled
        ) VALUES (
            402,
            202,
            '00000000-0000-0000-0000-000000000202',
            decode(repeat('ab', 32), 'hex'),
            1,
            decode(repeat('03', 12), 'hex'),
            decode(repeat('04', 17), 'hex'),
            TRUE
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23505' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'duplicate active endpoint was accepted';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('active_endpoint_duplicate_rejected');
END
$probe$;

UPDATE push_subscriptions
SET enabled = FALSE,
    deleted_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 401;

INSERT INTO push_subscriptions (
    id,
    user_id,
    sid,
    endpoint_hash,
    payload_key_version,
    payload_nonce,
    payload_ciphertext,
    enabled
) VALUES (
    402,
    202,
    '00000000-0000-0000-0000-000000000202',
    decode(repeat('ab', 32), 'hex'),
    1,
    decode(repeat('03', 12), 'hex'),
    decode(repeat('04', 17), 'hex'),
    TRUE
);

DO $probe$
DECLARE
    active_count INTEGER;
    tombstone_count INTEGER;
BEGIN
    SELECT count(*) FILTER (WHERE enabled AND deleted_at IS NULL),
           count(*) FILTER (WHERE NOT enabled AND deleted_at IS NOT NULL)
    INTO active_count, tombstone_count
    FROM push_subscriptions
    WHERE endpoint_hash = decode(repeat('ab', 32), 'hex');

    IF active_count <> 1 OR tombstone_count <> 1 THEN
        RAISE EXCEPTION 'endpoint tombstone invariant failed: active %, tombstones %', active_count, tombstone_count;
    END IF;

    INSERT INTO part_c_probe_results VALUES ('endpoint_tombstone_allows_reuse');
END
$probe$;

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO push_subscriptions (
            id,
            user_id,
            sid,
            endpoint_hash,
            payload_key_version,
            payload_nonce,
            payload_ciphertext,
            enabled
        ) VALUES (
            403,
            201,
            '00000000-0000-0000-0000-000000000202',
            decode(repeat('cd', 32), 'hex'),
            1,
            decode(repeat('05', 12), 'hex'),
            decode(repeat('06', 17), 'hex'),
            TRUE
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23503' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'subscription accepted a session owned by another user';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('subscription_session_owner_enforced');
END
$probe$;

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
    plaintext_columns INTEGER;
BEGIN
    BEGIN
        INSERT INTO push_subscriptions (
            id,
            user_id,
            sid,
            endpoint_hash,
            payload_key_version,
            payload_nonce,
            payload_ciphertext,
            enabled
        ) VALUES (
            404,
            201,
            '00000000-0000-0000-0000-000000000201',
            decode(repeat('ef', 32), 'hex'),
            1,
            decode(repeat('07', 11), 'hex'),
            decode(repeat('08', 17), 'hex'),
            TRUE
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23514' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    SELECT count(*)
    INTO plaintext_columns
    FROM information_schema.columns
    WHERE table_schema = 'part_c_probe'
      AND (
          (table_name = 'push_subscriptions' AND column_name IN ('endpoint', 'p256dh', 'auth'))
          OR (table_name = 'notification_webhooks' AND column_name = 'url')
      );

    IF NOT rejected OR plaintext_columns <> 0 THEN
        RAISE EXCEPTION 'encrypted recipient payload shape was not enforced';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('encrypted_recipient_shape_enforced');
END
$probe$;

INSERT INTO notification_webhooks (
    id,
    name,
    provider,
    url_key_version,
    url_nonce,
    url_ciphertext,
    enabled
) VALUES (
    501,
    'Operations Slack',
    'SLACK',
    1,
    decode(repeat('09', 12), 'hex'),
    decode(repeat('10', 17), 'hex'),
    TRUE
);

INSERT INTO notification_deliveries (
    id,
    incident_id,
    incident_version,
    notification_type,
    channel,
    notification_webhook_id,
    status,
    attempt_count,
    next_attempt_at,
    expires_at
) VALUES (
    601,
    '11111111-1111-1111-1111-111111111111',
    1,
    'INCIDENT_OPENED',
    'SLACK',
    501,
    'PENDING',
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP + INTERVAL '10 minutes'
);

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO notification_deliveries (
            id,
            incident_id,
            incident_version,
            notification_type,
            channel,
            notification_webhook_id,
            status,
            attempt_count,
            next_attempt_at,
            expires_at
        ) VALUES (
            602,
            '11111111-1111-1111-1111-111111111111',
            1,
            'INCIDENT_OPENED',
            'SLACK',
            501,
            'PENDING',
            0,
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP + INTERVAL '10 minutes'
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23505' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'duplicate delivery was accepted';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('delivery_duplicate_rejected');
END
$probe$;

INSERT INTO monitoring_states (
    database_config_id,
    config_version,
    state_version,
    enabled,
    deleted,
    connection_status,
    data_freshness,
    risk_level,
    activation_at
) VALUES (
    102,
    1,
    1,
    FALSE,
    FALSE,
    'DOWN',
    'PAUSED',
    NULL,
    NULL
);

INSERT INTO part_c_probe_results VALUES ('disabled_activation_coherent');

UPDATE monitoring_states
SET deleted = TRUE
WHERE database_config_id = 102;

INSERT INTO part_c_probe_results VALUES ('deleted_state_activation_coherent');

DELETE FROM monitoring_states WHERE database_config_id = 102;

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO monitoring_states (
            database_config_id,
            config_version,
            state_version,
            enabled,
            deleted,
            connection_status,
            data_freshness,
            risk_level,
            activation_at
        ) VALUES (
            102,
            1,
            1,
            TRUE,
            FALSE,
            'UP',
            'NO_DATA',
            NULL,
            NULL
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23514' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'enabled state without activation_at was accepted';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('enabled_activation_required');
END
$probe$;

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO monitoring_states (
            database_config_id,
            config_version,
            state_version,
            enabled,
            deleted,
            connection_status,
            data_freshness,
            risk_level,
            activation_at
        ) VALUES (
            102,
            1,
            1,
            FALSE,
            FALSE,
            'DOWN',
            'PAUSED',
            NULL,
            '2026-09-29T00:00:00Z'
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23514' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'disabled state with activation_at was accepted';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('disabled_activation_must_be_null');
END
$probe$;

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO monitoring_states (
            database_config_id,
            config_version,
            state_version,
            enabled,
            deleted,
            connection_status,
            data_freshness,
            activation_at,
            updated_at
        ) VALUES (
            102,
            1,
            1,
            TRUE,
            FALSE,
            'BROKEN',
            'NO_DATA',
            '2026-09-29T00:00:00Z',
            '2026-09-29T00:00:00Z'
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23514' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'invalid connection status was accepted';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('invalid_enum_rejected');
END
$probe$;

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO risk_policies (
            database_config_id,
            version,
            rules,
            stale_after_seconds,
            notification_cooldown_seconds
        ) VALUES (102, -1, '[]'::JSONB, 60, 300);
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23514' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'negative policy version was accepted';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('negative_version_rejected');
END
$probe$;

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO notification_deliveries (
            id,
            incident_id,
            incident_version,
            notification_type,
            channel,
            notification_webhook_id,
            status,
            attempt_count,
            next_attempt_at,
            expires_at
        ) VALUES (
            603,
            '11111111-1111-1111-1111-111111111111',
            2,
            'SEVERITY_INCREASED',
            'SLACK',
            501,
            'PENDING',
            -1,
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP + INTERVAL '10 minutes'
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23514' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'negative delivery attempt count was accepted';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('negative_attempt_count_rejected');
END
$probe$;

DO $probe$
DECLARE
    rejected BOOLEAN := FALSE;
    actual_state TEXT;
BEGIN
    BEGIN
        INSERT INTO notification_webhooks (
            id,
            name,
            provider,
            url_key_version,
            url_nonce,
            url_ciphertext,
            enabled
        ) VALUES (
            9007199254740992,
            'Unsafe identifier',
            'SLACK',
            1,
            decode(repeat('11', 12), 'hex'),
            decode(repeat('12', 17), 'hex'),
            TRUE
        );
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = '23514' THEN
            rejected := TRUE;
        ELSE
            RAISE;
        END IF;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION 'unsafe JSON identifier was accepted';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('unsafe_identifier_rejected');
END
$probe$;

DELETE FROM metric_data WHERE id = 301;

DO $probe$
DECLARE
    incident_snapshot_count INTEGER;
    state_metric_id BIGINT;
    rule_metric_id BIGINT;
BEGIN
    SELECT count(*)
    INTO incident_snapshot_count
    FROM incidents
    WHERE incident_id = '11111111-1111-1111-1111-111111111111'
      AND source_metric_id IS NULL
      AND metric_name = 'activeConnectionsRatio'
      AND metric_value = 0.82
      AND threshold_value = 0.80
      AND message = 'Connection ratio exceeded its warning threshold.';

    SELECT latest_metric_id
    INTO state_metric_id
    FROM monitoring_states
    WHERE database_config_id = 101;

    SELECT last_metric_id
    INTO rule_metric_id
    FROM risk_rule_states
    WHERE database_config_id = 101
      AND rule_id = 'CONNECTION_RATIO';

    IF incident_snapshot_count <> 1 OR state_metric_id IS NOT NULL OR rule_metric_id IS NOT NULL THEN
        RAISE EXCEPTION 'metric retention did not preserve incident evidence or clear metric pointers';
    END IF;

    INSERT INTO part_c_probe_results VALUES ('metric_delete_preserves_incident_evidence');
END
$probe$;

DO $probe$
DECLARE
    scenario_count INTEGER;
BEGIN
    SELECT count(*) INTO scenario_count FROM part_c_probe_results;
    IF scenario_count <> 22 THEN
        RAISE EXCEPTION 'expected 22 passing scenarios, found %', scenario_count;
    END IF;
END
$probe$;

SELECT
    count(*) AS passed_scenarios,
    string_agg(scenario, ',' ORDER BY scenario) AS scenarios
FROM part_c_probe_results;
