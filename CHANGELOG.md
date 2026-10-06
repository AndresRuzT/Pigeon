# Changelog

All notable changes to **Pigeon** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-10-05

### Added
- **Core Hexagonal Architecture:** Domain core, application use cases/ports, and infrastructure adapters completely decoupled and verified via ArchUnit tests.
- **Multichannel Delivery Cascade:** Fallback pipeline supporting `PUSH → SMS → EMAIL` with customer channel preference ordering and provider abstractions (MailHog for SMTP, WireMock for SMS & Push).
- **Transactional Outbox Engine:** Eliminates dual-write hazard between PostgreSQL and RabbitMQ using `SELECT ... FOR UPDATE SKIP LOCKED` batching and broker Publisher Confirms (`CorrelationData`).
- **Cryptographic Audit Trail:**
  - Append-only `audit_log` with SHA-256 tamper-evident hash chain (`prev_hash`).
  - PostgreSQL database triggers prohibiting `UPDATE`, `DELETE`, and `TRUNCATE` operations on audit and delivery attempt records.
  - Role-based least privilege setup for `pigeon_app`.
- **Hybrid Idempotency:** Distributed Redis fast-path caching combined with PostgreSQL unique constraints and SHA-256 payload checksums to prevent duplicate delivery and detect conflicts.
- **Resilience & Fault Tolerance:**
  - Resilience4j Circuit Breakers, Bulkheads, Timeouts, and in-process retries per channel.
  - Two-tier RabbitMQ retry ladder (`pigeon.notifications.retry.10s`, `30s`, `60s`) and poison-message Dead Letter Queue (`pigeon.notifications.dlq`).
  - High-priority TTL queue (`pigeon.notifications.high`) with dead-letter routing to `pigeon.expired` for expired OTPs/fraud alerts.
- **Customer Preferences & Quiet Hours:**
  - Timezone-aware quiet hours scheduler (`QuietHoursSchedulerService`) deferring non-urgent alerts until the next business day at 08:00.
  - Security and OTP events bypass quiet hours automatically.
- **Multilingual Templates:** Versioned Thymeleaf email/SMS/push templates in English and Spanish with SSTI prevention and variable allowlists.
- **Per-Customer Sliding Window Rate Limiting:** Redis Sorted Sets enforcing 1-hour standard and 10-minute OTP limits with fail-open semantics.
- **Signed Webhook Delivery Receipts:** `POST /api/v1/webhooks/{channel}/receipts` endpoint verifying HMAC-SHA256 signatures (`X-Signature`).
- **Observability & Dashboards:**
  - Micrometer integration exposing 10 core domain metrics to Prometheus.
  - MDC structured logging with service name, correlation ID, notification ID, and customer ID.
  - Automated Prometheus scraper and pre-provisioned Grafana dashboard (`pigeon-overview.json`).
- **Developer Experience:**
  - Interactive scenario demo script (`scripts/demo.sh`).
  - RSA-2048 JWT generation script (`scripts/dev-token.sh`).
  - Complete OpenAPI 3.0 export (`docs/api/openapi.yaml`).
  - Comprehensive documentation: `ARCHITECTURE.md`, `CONTRIBUTING.md`, `SECURITY.md`, `docs/data-model.md`, `docs/runbook.md`, and 13 ADRs.

### Security
- Boundary validation blocking raw credit/debit card numbers (Luhn check) and long account number runs with RFC 7807 Problem Details.
- Logback `MaskingPatternLayout` masking sensitive numeric runs in all console and file logs.
- OAuth2 Resource Server validating RS256 JWT tokens with scoped permissions.
- Constant-time HMAC comparison on delivery webhook receipts.
