SET search_path TO part_c_probe, pg_catalog;

SELECT database_config_id, state_version
FROM monitoring_states
WHERE database_config_id = 101;
