# ADR 0002: Message Broker Selection and Dedicated Priority Topology

## Status
Accepted

## Context
A critical requirement in banking notification services is message differentiation:
- Security-critical messages (such as One-Time Passwords / OTP and Suspicious Fraud Alerts) must be delivered within seconds and must never get queued behind large batches of bulk or low-priority notifications (such as monthly statements or transfer confirmations).
- High-priority security messages have a short lifetime (TTL); an OTP that arrives 15 minutes late is not only useless but a security vulnerability.
- When message delivery repeatedly fails, messages must follow an exponential backoff retry ladder before landing in a Dead Letter Queue (DLQ).

We considered Apache Kafka vs. RabbitMQ. While Kafka excels at high-throughput event streaming and replayable log retention, it lacks out-of-the-box per-message TTL, native Dead Letter Exchanges, and simple multi-tier retry ladders without deploying complex stateful stream topologies or third-party orchestrators.

## Decision
We select **RabbitMQ** as the message broker for Pigeon.

The messaging topology consists of:
1. **Direct Exchange `pigeon.events`**: Routes messages by priority routing key (`high`, `low`).
2. **Dedicated Queue `pigeon.events.high`**:
   - Durable queue configured with `x-message-ttl = 60000` (60 seconds default).
   - Configured with Dead Letter Exchange (`pigeon.dlx`) and routing key `expired`.
   - Critical messages are processed immediately by dedicated consumer threads.
3. **Queue `pigeon.events.low`**:
   - Durable queue for informational notifications.
   - Configured with DLX `pigeon.dlx` and routing key `dead`.
4. **Retry Ladder Exchange `pigeon.retry`**:
   - Queues `pigeon.retry.30s`, `pigeon.retry.2m`, `pigeon.retry.10m` with fixed TTL that dead-letter back to `pigeon.events` with routing key `low`.
5. **Dead Letter Exchange `pigeon.dlx`**:
   - Routes expired messages to `pigeon.expired` (where a listener records `FAILED` with `failure_reason = EXPIRED` and writes audit logs).
   - Routes unrecoverable / poison messages to `pigeon.dlq` for operator inspection.
6. **Zero PII on Broker**:
   - Messages sent to RabbitMQ carry only a lightweight JSON envelope (`notificationId`, `eventType`, `priority`, `attempt`, `schemaVersion`). No card numbers, account numbers, or customer contact details are stored in the broker. The consumer retrieves sanitized details directly from PostgreSQL.

## Consequences

### Positive
- Strict queue isolation prevents priority inversion (OTP messages never block behind slow batches).
- Native broker TTL handles expiration reliably without consuming application worker threads.
- Absence of sensitive customer data on the broker reduces PCI DSS scope and data breach surface.

### Negative / Trade-offs
- Consumers must perform a database lookup by `notificationId` upon receiving the envelope, introducing a small read latency that is offset by PostgreSQL indexing and connection pooling.
