-- Flyway V1: Initial schema for Pigeon notification service

-- 1. Notification table
CREATE TABLE notification (
    id UUID PRIMARY KEY,
    client_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    locale VARCHAR(8) NOT NULL DEFAULT 'en',
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(64),
    template_id VARCHAR(64),
    template_version VARCHAR(16),
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    scheduled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_notification_client_idempotency UNIQUE (client_id, idempotency_key)
);

CREATE INDEX idx_notification_customer ON notification(customer_id);
CREATE INDEX idx_notification_status ON notification(status);

-- 2. Delivery attempt table
CREATE TABLE delivery_attempt (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notification(id) ON DELETE RESTRICT,
    channel VARCHAR(16) NOT NULL,
    attempt_no INT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    provider_ref VARCHAR(128),
    error_code VARCHAR(64),
    latency_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_delivery_attempt UNIQUE (notification_id, channel, attempt_no)
);

CREATE INDEX idx_delivery_attempt_notification ON delivery_attempt(notification_id);

-- 3. Outbox event table
CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    routing_key VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_unpublished ON outbox_event(created_at) WHERE published_at IS NULL;

-- 4. Customer contact table (synthetic demo contacts)
CREATE TABLE customer_contact (
    customer_id VARCHAR(64) PRIMARY KEY,
    email VARCHAR(128),
    phone VARCHAR(32),
    push_token VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed synthetic demo contacts for simulation
INSERT INTO customer_contact (customer_id, email, phone, push_token)
VALUES
    ('cus_8F2A91', 'maria.garcia@example.com', '+15550198234', 'push_tok_maria_123'),
    ('cus_A1B2C3', 'alex.chen@example.com', '+15550198235', 'push_tok_alex_456'),
    ('cus_X9Y8Z7', 'carlos.rodriguez@example.com', '+15550198236', 'push_tok_carlos_789')
ON CONFLICT (customer_id) DO NOTHING;

-- 5. Audit log table
CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    notification_id UUID NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    channel VARCHAR(16),
    template_version VARCHAR(16),
    reason TEXT,
    correlation_id VARCHAR(64),
    prev_hash VARCHAR(128)
);

CREATE INDEX idx_audit_log_notification ON audit_log(notification_id);

-- 6. Immutability trigger for audit_log and delivery_attempt
CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit records and delivery attempts are append-only. UPDATE and DELETE operations are forbidden.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER trg_delivery_attempt_immutable
    BEFORE UPDATE OR DELETE ON delivery_attempt
    FOR EACH ROW
    EXECUTE FUNCTION prevent_audit_modification();
