CREATE TABLE processed_events (
    stream varchar(100) NOT NULL,
    consumer_group varchar(100) NOT NULL,
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (stream, consumer_group, event_id)
);
