-- AI 인사이트(일일 DB 상태 보고서·위험 쿼리 분석) 결과 저장.
-- 요청 1건 = 행 1개. 생성은 비동기라 PENDING으로 먼저 저장하고 SUCCEEDED/FAILED로 끝낸다.
-- content는 API 응답 본문(dailyReport 또는 queryAnalysis)을 그대로 담은 JSON이다.

CREATE TABLE ai_reports (
    id                 BIGSERIAL    PRIMARY KEY,
    report_type        VARCHAR(32)  NOT NULL,
    database_config_id BIGINT       NOT NULL,
    database_name      VARCHAR(100) NOT NULL,
    report_date        DATE,
    window_start       TIMESTAMPTZ,
    window_end         TIMESTAMPTZ,
    status             VARCHAR(16)  NOT NULL,
    trigger_source     VARCHAR(16)  NOT NULL,
    requested_by       BIGINT,
    model              VARCHAR(64)  NOT NULL,
    content            JSONB,
    error_code         VARCHAR(32),
    error_message      VARCHAR(500),
    input_tokens       BIGINT,
    output_tokens      BIGINT,
    requested_at       TIMESTAMPTZ  NOT NULL,
    completed_at       TIMESTAMPTZ,
    CONSTRAINT ai_reports_target_fk
        FOREIGN KEY (database_config_id) REFERENCES database_configs (id),
    CONSTRAINT ai_reports_type_check
        CHECK (report_type IN ('DAILY_REPORT', 'QUERY_ANALYSIS')),
    CONSTRAINT ai_reports_status_check
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ai_reports_trigger_check
        CHECK (trigger_source IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT ai_reports_daily_date_check
        CHECK ((report_type = 'DAILY_REPORT') = (report_date IS NOT NULL)),
    CONSTRAINT ai_reports_content_check
        CHECK ((status = 'SUCCEEDED') = (content IS NOT NULL)),
    CONSTRAINT ai_reports_completion_check
        CHECK ((status = 'PENDING') = (completed_at IS NULL))
);

-- 같은 대상·종류(·날짜)로 동시에 두 번 생성하지 않는다.
CREATE UNIQUE INDEX ai_reports_one_pending_daily
    ON ai_reports (database_config_id, report_date)
    WHERE status = 'PENDING' AND report_type = 'DAILY_REPORT';
CREATE UNIQUE INDEX ai_reports_one_pending_query
    ON ai_reports (database_config_id)
    WHERE status = 'PENDING' AND report_type = 'QUERY_ANALYSIS';

CREATE INDEX idx_ai_reports_target_requested
    ON ai_reports (database_config_id, requested_at DESC, id DESC);
CREATE INDEX idx_ai_reports_requested
    ON ai_reports (requested_at DESC, id DESC);
