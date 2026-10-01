-- Flyway V2: Customer Preferences, Quiet Hours and Deferred Notifications
CREATE TABLE customer_preference (
    customer_id VARCHAR(64) PRIMARY KEY,
    allowed_channels VARCHAR(64) NOT NULL DEFAULT 'PUSH,SMS,EMAIL',
    preferred_channel_order VARCHAR(64) NOT NULL DEFAULT 'PUSH,SMS,EMAIL',
    opt_out_categories VARCHAR(256) NOT NULL DEFAULT '',
    time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    quiet_hours_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_deferred ON notification(status, scheduled_at) WHERE status = 'DEFERRED';

-- Seed synthetic customer preferences
INSERT INTO customer_preference (customer_id, allowed_channels, preferred_channel_order, opt_out_categories, time_zone, quiet_hours_enabled)
VALUES
    ('cus_8F2A91', 'PUSH,SMS,EMAIL', 'PUSH,SMS,EMAIL', '', 'UTC', FALSE),
    ('cus_A1B2C3', 'PUSH,SMS,EMAIL', 'PUSH,SMS,EMAIL', 'MARKETING', 'America/New_York', TRUE),
    ('cus_X9Y8Z7', 'SMS,EMAIL', 'SMS,EMAIL', 'PAYMENT_REMINDER', 'Europe/Madrid', TRUE)
ON CONFLICT (customer_id) DO NOTHING;
