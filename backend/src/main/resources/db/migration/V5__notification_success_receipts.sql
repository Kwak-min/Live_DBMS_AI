LOCK TABLE notification_deliveries IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE notification_success_receipts (
    incident_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL,
    push_subscription_id BIGINT,
    notification_webhook_id BIGINT,
    recipient_id BIGINT GENERATED ALWAYS AS (
        COALESCE(push_subscription_id, notification_webhook_id)
    ) STORED,
    last_successful_open_or_increase_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_success_receipts_pk
        PRIMARY KEY (incident_id, channel, recipient_id),
    CONSTRAINT notification_success_receipts_incident_fk
        FOREIGN KEY (incident_id) REFERENCES incidents (incident_id) ON DELETE CASCADE,
    CONSTRAINT notification_success_receipts_push_fk
        FOREIGN KEY (push_subscription_id) REFERENCES push_subscriptions (id),
    CONSTRAINT notification_success_receipts_webhook_fk
        FOREIGN KEY (notification_webhook_id) REFERENCES notification_webhooks (id),
    CONSTRAINT notification_success_receipts_recipient_id_safe_check
        CHECK (recipient_id BETWEEN 1 AND 9007199254740991),
    CONSTRAINT notification_success_receipts_channel_recipient_check
        CHECK (
            (channel = 'WEB_PUSH' AND push_subscription_id IS NOT NULL
                AND notification_webhook_id IS NULL)
            OR
            (channel = 'SLACK' AND push_subscription_id IS NULL
                AND notification_webhook_id IS NOT NULL)
        )
);

INSERT INTO notification_success_receipts (
    incident_id,
    channel,
    push_subscription_id,
    notification_webhook_id,
    last_successful_open_or_increase_at
)
SELECT
    incident_id,
    channel,
    push_subscription_id,
    notification_webhook_id,
    MAX(sent_at)
FROM notification_deliveries
WHERE status = 'SENT'
  AND notification_type IN ('INCIDENT_OPENED', 'SEVERITY_INCREASED')
GROUP BY incident_id, channel, push_subscription_id, notification_webhook_id;
