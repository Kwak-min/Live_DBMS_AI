DROP SCHEMA IF EXISTS part_c_probe CASCADE;

SELECT to_regnamespace('part_c_probe') IS NULL AS probe_schema_removed;
