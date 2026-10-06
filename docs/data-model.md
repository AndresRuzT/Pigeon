# Pigeon Data Model Reference

This document describes the persistence models used by Pigeon across PostgreSQL and Redis.

---

## 1. Relational Model (PostgreSQL)

Pigeon uses PostgreSQL 16+ as its primary ACID transactional store, managed via Flyway migrations (`V1`, `V2`, `V3`).

### 1.1 `notification`
Represents the core transactional notification aggregate root.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique notification identifier. |
| `client_id` | `VARCHAR(64)` | `NOT NULL` | Producer client identifier (from JWT `azp`/`client_id`). |
| `idempotency_key` | `VARCHAR(128)` | `NOT NULL` | Client-provided idempotency key. |
| `payload_hash` | `VARCHAR(64)` | `NOT NULL` | SHA-256 digest of original ingestion JSON payload. |
| `customer_id` | `VARCHAR(64)` | `NOT NULL` | Banking customer identifier. |
| `event_type` | `VARCHAR(64)` | `NOT NULL` | Business event (`TRANSFER_COMPLETED`, etc.). |
| `priority` | `VARCHAR(16)` | `NOT NULL` | `HIGH` or `LOW`. |
| `locale` | `VARCHAR(8)` | `NOT NULL DEFAULT 'en'` | Language code (`en`, `es`). |
| `status` | `VARCHAR(32)` | `NOT NULL` | Canonical status (`PENDING`, `SENT`, `DELIVERED`, `FAILED`, `DEFERRED`). |
| `failure_reason` | `VARCHAR(64)` | `NULLABLE` | Standardized failure code if status is `FAILED`. |
| `template_id` | `VARCHAR(64)` | `NULLABLE` | Resolved template identifier. |
| `template_version` | `VARCHAR(16)` | `NULLABLE` | Resolved template version (e.g. `v1`). |
| `data` | `JSONB` | `NOT NULL DEFAULT '{}'` | Validated payload attributes (no raw PANs). |
| `scheduled_at` | `TIMESTAMPTZ` | `NULLABLE` | Scheduled release timestamp for deferred notifications. |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | UTC creation timestamp. |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | UTC last updated timestamp. |
| `version` | `BIGINT` | `NOT NULL DEFAULT 0` | Optimistic locking counter. |

**Indexes & Constraints:**
- `uk_notification_client_idempotency`: `UNIQUE (client_id, idempotency_key)`
- `idx_notification_customer`: Index on `(customer_id)`
- `idx_notification_status`: Index on `(status)`
- `idx_notification_deferred`: Partial index on `(status, scheduled_at) WHERE status = 'DEFERRED'`

---

### 1.2 `delivery_attempt`
Append-only log of every channel delivery attempt and provider roundtrip.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique attempt identifier. |
| `notification_id` | `UUID` | `NOT NULL REFERENCES notification(id)` | Foreign key to parent notification. |
| `channel` | `VARCHAR(16)` | `NOT NULL` | Delivery channel (`PUSH`, `SMS`, `EMAIL`). |
| `attempt_no` | `INT` | `NOT NULL` | Sequential attempt number per channel. |
| `outcome` | `VARCHAR(16)` | `NOT NULL` | `SUCCESS` or `FAILURE`. |
| `provider_ref` | `VARCHAR(128)` | `NULLABLE` | External provider message ID (MailHog, WireMock DLR). |
| `error_code` | `VARCHAR(64)` | `NULLABLE` | Error categorization code (e.g. `HTTP_500`, `TIMEOUT`). |
| `latency_ms` | `BIGINT` | `NOT NULL DEFAULT 0` | Roundtrip latency in milliseconds. |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Timestamp attempt was recorded. |

**Security & Immutability:**
- Database trigger `trg_delivery_attempt_immutable` blocks all `UPDATE` and `DELETE` operations.
- Statement trigger `trg_delivery_attempt_truncate` blocks all `TRUNCATE` operations.
- `uk_delivery_attempt`: `UNIQUE (notification_id, channel, attempt_no)`

---

### 1.3 `outbox_event`
Transactional Outbox table used to safely bridge database commits and RabbitMQ broker dispatch.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique outbox event identifier. |
| `aggregate_id` | `UUID` | `NOT NULL` | Associated notification ID. |
| `event_type` | `VARCHAR(64)` | `NOT NULL` | Domain event type. |
| `routing_key` | `VARCHAR(64)` | `NOT NULL` | AMQP routing key (`pigeon.notifications.high`, `low`, etc.). |
| `payload` | `JSONB` | `NOT NULL` | Serialized message payload. |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Timestamp enqueued in outbox. |
| `published_at` | `TIMESTAMPTZ` | `NULLABLE` | Timestamp acknowledged by RabbitMQ publisher confirms. |
| `publish_attempts`| `INT` | `NOT NULL DEFAULT 0` | Number of publish attempts made by relay. |

**Indexes:**
- `idx_outbox_unpublished`: Partial index on `(created_at) WHERE published_at IS NULL`

---

### 1.4 `audit_log`
Append-only, cryptographically hash-chained regulatory audit record for every state change.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique audit record identifier. |
| `occurred_at` | `TIMESTAMPTZ` | `NOT NULL` | UTC timestamp of the transition. |
| `notification_id` | `UUID` | `NOT NULL` | Associated notification ID. |
| `customer_id` | `VARCHAR(64)` | `NOT NULL` | Target banking customer identifier. |
| `actor` | `VARCHAR(64)` | `NOT NULL` | Initiator (`pigeon-orchestrator`, `webhook-controller`, etc.). |
| `action` | `VARCHAR(64)` | `NOT NULL` | Performed action (e.g. `STATUS_TRANSITION`). |
| `from_status` | `VARCHAR(32)` | `NULLABLE` | Previous status (`PENDING`, `SENT`, etc.). |
| `to_status` | `VARCHAR(32)` | `NOT NULL` | New status. |
| `channel` | `VARCHAR(16)` | `NULLABLE` | Channel involved in the transition. |
| `template_version`| `VARCHAR(16)` | `NULLABLE` | Rendered template version. |
| `reason` | `TEXT` | `NULLABLE` | Reason description or failure explanation. |
| `correlation_id` | `VARCHAR(64)` | `NULLABLE` | Distributed tracing correlation ID. |
| `prev_hash` | `VARCHAR(128)`| `NULLABLE` | Cryptographic SHA-256 hash chaining previous record. |

**Security & Immutability:**
- Database trigger `trg_audit_log_immutable` blocks all `UPDATE` and `DELETE` operations.
- Statement trigger `trg_audit_log_truncate` blocks all `TRUNCATE` operations.
- Hash chain formula: `SHA-256(prev_hash + notification_id + from_status + to_status + occurred_at + failure_reason + actor + channel)`

---

### 1.5 `customer_preference`
Stores customer delivery channel preferences, language locale, quiet hour rules, and opt-outs.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `customer_id` | `VARCHAR(64)` | `PRIMARY KEY` | Target customer identifier. |
| `allowed_channels`| `VARCHAR(64)` | `NOT NULL DEFAULT 'PUSH,SMS,EMAIL'` | Comma-delimited permitted channels. |
| `preferred_channel_order`| `VARCHAR(64)` | `NOT NULL DEFAULT 'PUSH,SMS,EMAIL'` | Fallback order cascade. |
| `opt_out_categories`| `VARCHAR(256)`| `NOT NULL DEFAULT ''` | Categories opted out from. |
| `time_zone` | `VARCHAR(64)` | `NOT NULL DEFAULT 'UTC'` | Customer IANA timezone (`America/New_York`, etc.). |
| `quiet_hours_enabled`| `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Whether quiet hours apply to non-urgent alerts. |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()` | Record creation timestamp. |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()` | Record last updated timestamp. |

---

### 1.6 `customer_contact`
Reference contact endpoints for customers in simulated environment.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `customer_id` | `VARCHAR(64)` | `PRIMARY KEY` | Customer identifier. |
| `email` | `VARCHAR(128)`| `NULLABLE` | Email address (e.g. MailHog destination). |
| `phone` | `VARCHAR(32)` | `NULLABLE` | E.164 phone number (e.g. WireMock SMS destination). |
| `push_token` | `VARCHAR(256)`| `NULLABLE` | Push device registration token. |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()` | Creation timestamp. |

---

## 2. In-Memory Key-Value Model (Redis)

Redis 7+ is utilized for high-throughput, low-latency distributed operations.

### 2.1 Distributed Idempotency Key
- **Key Pattern:** `pigeon:idempotency:{clientId}:{idempotencyKey}`
- **Value:** JSON string containing `{ "notificationId": "<UUID>", "status": "<STATUS>", "priority": "<PRIORITY>", "payloadHash": "<SHA-256>" }`
- **TTL:** 86,400 seconds (24 hours).

### 2.2 Customer Sliding Window Rate Limiting
- **Standard Notifications:**
  - **Key Pattern:** `pigeon:ratelimit:standard:{customerId}`
  - **Data Structure:** Redis Sorted Set (`ZSET`).
  - **Score / Member:** Unix epoch millisecond timestamp.
  - **Window:** 1 hour sliding window (max 10 notifications).
- **OTP Notifications:**
  - **Key Pattern:** `pigeon:ratelimit:otp:{customerId}`
  - **Data Structure:** Redis Sorted Set (`ZSET`).
  - **Score / Member:** Unix epoch millisecond timestamp.
  - **Window:** 10 minutes sliding window (max 5 OTP notifications).
