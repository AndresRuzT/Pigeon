# ADR 0013: Transactional Outbox Confirms, Tamper-Evident Hash Chain Audit, and Metrics Observability

## Status
Accepted

## Context
In transactional financial systems, message loss or undetected audit log tampering is unacceptable. 
While Phase 1 introduced the initial `outbox_event` table and relay, and Phase 2 introduced retries and DLQs:
1. Broker confirms were unconfirmed at the relay level, posing a dual-delivery or silent loss risk if RabbitMQ restarted or rejected an unroutable message before persistence.
2. The audit log relied purely on table-level Postgres triggers against `UPDATE` and `DELETE`. However, database administrators or storage-level interventions could tamper with historical records without a cryptographic integrity trail, and table truncation needed formal trigger interception.
3. Observability was limited to logs without standardized Prometheus metrics or visual Grafana panels monitoring acceptance, latency, fallbacks, circuit breakers, and rate limit rejections.
4. Asynchronous delivery channels (SMS and Push) required an authenticated receipt ingestion path via HMAC-SHA256 signed webhooks.

## Decision
1. **Outbox Relay & Broker Publisher Confirms**:
   - Outbox polling uses `SELECT ... FOR UPDATE SKIP LOCKED` batching to allow horizontal relay scaling.
   - The publisher confirms mechanism (`CorrelationData`) synchronously awaits broker ACK (`confirm.isAck()`) and verifies returned unroutable messages before marking outbox records as published.
   - A scheduled purge routine cleans published outbox records older than 7 days (`outbox.retention-days`).
2. **Tamper-Evident Hash-Chained Audit Trail**:
   - Added a `prev_hash` column to `audit_log`. Each audit record hashes its predecessor (`SHA-256(prev_hash + notification_id + from_status + to_status + occurred_at + failure_reason + actor + channel)`) forming an immutable blockchain-like integrity chain per notification.
   - Added a statement-level trigger `trg_block_audit_log_truncate` blocking `TRUNCATE` operations on `audit_log`.
   - Dedicated application role `pigeon_app` restricted to `SELECT, INSERT` on audit and attempt tables.
3. **Hexagonal Metrics Port & Observability Stack**:
   - Defined pure domain/JDK port `MetricsPort` in `application.port.out`, implemented by `MicrometerMetricsAdapter` in `infrastructure.adapter.out.metrics`.
   - Instrumented the 10 core metrics defined in Section 14:
     - Counters: `pigeon.notifications.accepted`, `pigeon.notifications.duplicates`, `pigeon.delivery.attempts`, `pigeon.notifications.final`, `pigeon.fallback.triggered`, `pigeon.ratelimit.rejected`.
     - Timer: `pigeon.delivery.latency` with p50, p95, p99 percentiles and SLAs.
     - Gauges: `pigeon.circuitbreaker.state`, `pigeon.outbox.pending`, `pigeon.queue.depth`.
   - Configured MDC structured logging with `service="pigeon"`, `correlationId`, `notificationId`, and `customerId`.
   - Provisioned Prometheus configuration and an automated Grafana dashboard (`pigeon-overview.json`).
4. **Signed Webhook Delivery Receipts**:
   - `POST /api/v1/webhooks/{channel}/receipts` endpoint protected by HMAC-SHA256 signature verification header (`X-Signature`) against `PIGEON_WEBHOOK_SECRET`.
   - Differentiates `ON_ACCEPT` (Email SMTP) vs `ON_RECEIPT` (SMS / Push DLRs) confirmation policies.

## Consequences

### Positive
- Zero dual-write and message-loss risks with end-to-end publisher confirms and crash recovery.
- Cryptographically verifiable tamper-evident audit history ensuring banking regulatory compliance.
- Complete operational visibility with provisioned Prometheus scraping and Grafana dashboards out-of-the-box.
- Secure, authenticated asynchronous receipt processing for multi-channel pipelines.

### Negative / Trade-offs
- Publisher confirms introduce a slight latency overhead per published outbox message (~1-3ms per confirm), easily handled by asynchronous relay batching.
- Computing SHA-256 for each state transition introduces minimal CPU overhead during write operations.
