-- Part A-owned V3: 공통 outbox와 소비자 중복 처리 기록.
-- event_outbox는 A·B·C가 OutboxWriter로 같은 트랜잭션에 기록하고, OutboxPublisher가 Redis Stream으로 발행한다.
-- CollectorHeartbeatEvent는 Redis로 직접 발행하므로 outbox 대상이 아니다.

CREATE TABLE event_outbox (
    event_id        UUID         NOT NULL,
    seq             BIGSERIAL    NOT NULL,
    event_type      VARCHAR(64)  NOT NULL CHECK (event_type IN (
                        'MetricCollectedEvent',
                        'MonitoringStatusChangedEvent',
                        'IncidentCreatedEvent',
                        'IncidentUpdatedEvent',
                        'IncidentResolvedEvent')),
    stream_key      VARCHAR(128) NOT NULL,
    ordering_key    VARCHAR(128),
    payload         JSONB        NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(500),
    PRIMARY KEY (event_id),
    UNIQUE (seq)
);

CREATE INDEX idx_event_outbox_unpublished ON event_outbox (seq) WHERE published_at IS NULL;
CREATE INDEX idx_event_outbox_published_at ON event_outbox (published_at) WHERE published_at IS NOT NULL;

CREATE TABLE processed_events (
    stream         VARCHAR(128) NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    event_id       UUID         NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (stream, consumer_group, event_id)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
