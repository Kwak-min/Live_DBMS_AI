-- Part A-owned V1: 기준 커밋(d2f65db)의 기존 스키마.
-- 기존 엔티티에 Hibernate ddl-auto를 적용했을 때 생성되던 스키마와 동일해야 한다.
-- (MigrationV1SchemaTest가 두 스키마를 비교한다.) 이미 ddl-auto로 만들어진 DB는
-- 백업·스키마 비교 후 `flyway baseline -baselineVersion=1`로 등록한다.
-- timestamptz 전환·평문 계정 암호화·BLOCKED 이전은 V2/V3에서 처리하며 이 파일은 수정하지 않는다.

CREATE TABLE database_configs (
    id                          BIGSERIAL,
    collection_interval_seconds INTEGER      NOT NULL,
    created_at                  TIMESTAMP(6) NOT NULL,
    database_name               VARCHAR(100),
    enabled                     BOOLEAN      NOT NULL,
    host                        VARCHAR(255) NOT NULL,
    last_checked_at             TIMESTAMP(6),
    last_error_message          VARCHAR(255),
    name                        VARCHAR(100) NOT NULL,
    password                    VARCHAR(255) NOT NULL,
    port                        INTEGER      NOT NULL,
    status                      VARCHAR(20)  NOT NULL CHECK (status IN ('UP', 'DOWN', 'UNKNOWN', 'BLOCKED')),
    updated_at                  TIMESTAMP(6),
    username                    VARCHAR(100) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE metric_data (
    id                 BIGSERIAL,
    active_connections BIGINT,
    collection_status  VARCHAR(30)  NOT NULL CHECK (collection_status IN ('SUCCESS', 'PARTIAL_FAILURE', 'CONNECTION_FAILED')),
    cpu_usage          FLOAT(53),
    created_at         TIMESTAMP(6) NOT NULL,
    database_config_id BIGINT       NOT NULL,
    error_message      VARCHAR(500),
    max_connections    BIGINT,
    memory_usage       FLOAT(53),
    qps                FLOAT(53),
    response_time_ms   BIGINT,
    slow_queries       BIGINT,
    storage_bytes      BIGINT,
    threads_running    BIGINT,
    timestamp          TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE audit_logs (
    id                BIGSERIAL,
    client_ip         VARCHAR(45)  NOT NULL,
    execution_time_ms BIGINT,
    http_method       VARCHAR(10),
    http_status       INTEGER,
    request_uri       VARCHAR(500),
    timestamp         TIMESTAMP(6) NOT NULL,
    user_agent        VARCHAR(500),
    PRIMARY KEY (id)
);

CREATE TABLE blocked_reasons (
    id                 BIGSERIAL,
    block_type         VARCHAR(10)  NOT NULL CHECK (block_type IN ('AUTO', 'MANUAL')),
    blocked_at         TIMESTAMP(6) NOT NULL,
    blocked_by         VARCHAR(100) NOT NULL,
    database_config_id BIGINT       NOT NULL,
    incident_id        VARCHAR(64),
    reason             VARCHAR(500) NOT NULL,
    severity           VARCHAR(20)  NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL', 'FATAL')),
    unblocked_at       TIMESTAMP(6),
    unblocked_by       VARCHAR(100),
    PRIMARY KEY (id)
);

CREATE INDEX idx_metric_db_time ON metric_data (database_config_id, timestamp DESC);
CREATE INDEX idx_blocked_db_config_id ON blocked_reasons (database_config_id);
CREATE INDEX idx_blocked_at ON blocked_reasons (blocked_at);

-- FK 이름은 Hibernate가 생성하던 이름과 같게 두어 baseline DB와 신규 DB의 제약 이름을 일치시킨다.
-- blocked_reasons.database_config_id는 엔티티 설계상 FK 없이 ID만 보관한다.
ALTER TABLE metric_data
    ADD CONSTRAINT fks93nosmjnu4pisr7c6p6woks6
    FOREIGN KEY (database_config_id) REFERENCES database_configs (id);
