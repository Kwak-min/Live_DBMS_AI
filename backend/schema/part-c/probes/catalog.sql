SET search_path TO part_c_probe, pg_catalog;

SELECT current_setting('server_version') AS postgres_version;

SELECT string_agg(table_name, ',' ORDER BY table_name) AS part_c_tables
FROM information_schema.tables
WHERE table_schema = 'part_c_probe'
  AND table_type = 'BASE TABLE'
  AND table_name NOT IN ('users', 'auth_sessions', 'database_configs', 'metric_data');

SELECT count(*) AS part_c_foreign_keys
FROM information_schema.table_constraints
WHERE constraint_schema = 'part_c_probe'
  AND constraint_type = 'FOREIGN KEY'
  AND table_name IN (
      'monitoring_states',
      'risk_policies',
      'incidents',
      'risk_rule_states',
      'push_subscriptions',
      'notification_webhooks',
      'notification_deliveries'
  );

SELECT string_agg(indexname, ',' ORDER BY indexname) AS required_unique_indexes
FROM pg_indexes
WHERE schemaname = 'part_c_probe'
  AND indexname IN (
      'incidents_one_open_rule_idx',
      'push_subscriptions_active_endpoint_idx',
      'notification_deliveries_dedup_idx'
  );
