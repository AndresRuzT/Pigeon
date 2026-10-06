# Pigeon Operations Runbook

This runbook provides incident response, maintenance procedures, and troubleshooting workflows for the Pigeon notification engine.

---

## 1. Architecture Quick Reference

| Service | Host Port | Purpose | Health Check |
|---|---|---|---|
| Pigeon Core | `8080` | Spring Boot REST API & Workers | `GET /actuator/health` |
| PostgreSQL | `5432` | Relational Storage & Immutable Audit | `pg_isready -U pigeon` |
| RabbitMQ | `5672` / `15672` | AMQP Broker & Management UI | `rabbitmq-diagnostics ping` |
| Redis | `6379` | Distributed Idempotency & Rate Limiting | `redis-cli ping` |
| Prometheus | `9090` | Metrics Scraper & Time-series DB | `http://localhost:9090/-/healthy` |
| Grafana | `3000` | Observability Dashboards | `http://localhost:3000/api/health` |
| MailHog | `8025` / `1025` | SMTP Simulation Server | `http://localhost:8025` |
| WireMock | `8089` | SMS and Push Simulated Gateway | `http://localhost:8089/__admin` |

---

## 2. Common Incident Procedures

### 2.1 Poison Messages in Dead-Letter Queue (`pigeon.notifications.dlq`)

**Symptom:**
Prometheus metric `pigeon_queue_depth{queue="pigeon.notifications.dlq"}` increases above zero.

**Diagnosis:**
Messages land in the DLQ when:
1. Low-priority messages exhaust all retries across both the in-process Resilience4j policy and the RabbitMQ retry ladder queues (`pigeon.notifications.retry.10s`, `30s`, `60s`).
2. An unrecoverable deserialization or payload defect occurs.

**Inspection:**
1. Open the RabbitMQ Management UI (`http://localhost:15672`).
2. Navigate to **Queues** -> `pigeon.notifications.dlq`.
3. Use **Get messages** (set `Requeue: true` to avoid losing messages during inspection).
4. Inspect the `x-death` and `x-first-death-reason` message headers to identify the root cause channel or exception.

**Remediation:**
- If the downstream provider experienced an outage that is now resolved, messages can be shoveled back to the primary intake queue `pigeon.notifications.low` via the RabbitMQ Shovel plugin.
- If payload is fundamentally malformed, mark the corresponding database record with failure code `DEAD_LETTERED`.

---

### 2.2 Expired High-Priority Messages (`pigeon.expired`)

**Symptom:**
Consumer logs indicate messages being read from `pigeon.expired`.

**Diagnosis:**
High-priority messages (such as OTPs or Fraud alerts) have a strict broker TTL (default: 60,000 ms). If workers are starved or backlogged longer than this window, the broker dead-letters them to `pigeon.notifications.exchange.dlx` with routing key `pigeon.expired`.

**Automated Action:**
`RabbitExpiredListener` consumes from `pigeon.expired` and transitions the notification directly to `FAILED` with failure reason `EXPIRED` and writes an immutable audit log entry.

**Remediation:**
- Inspect system load, worker pool size, and CPU usage.
- Scale out Pigeon instances horizontally. The transactional outbox and AMQP competing consumers automatically distribute the load.

---

### 2.3 Outbox Relay Lagging or Stuck

**Symptom:**
Metric `pigeon_outbox_pending` exceeds normal thresholds (> 100 messages for > 10 seconds).

**Diagnosis:**
1. Check connectivity between Pigeon and RabbitMQ.
2. Verify if the database connection pool (HikariCP) is exhausted:
   ```sql
   SELECT count(*) FROM pg_stat_activity WHERE datname = 'pigeon';
   ```
3. Query the number of unpublished outbox records:
   ```sql
   SELECT count(*) FROM outbox_event WHERE published_at IS NULL;
   ```

**Remediation:**
- Ensure RabbitMQ is healthy and accepting publisher confirms.
- If the relay worker is hung on network sockets, restarting the Pigeon instance triggers `OutboxCrashRecovery` automatically: upon restart, `OutboxRelayService` uses `SKIP LOCKED` to safely claim unacknowledged rows and republish them with publisher confirms.

---

### 2.4 Cryptographic Audit Chain Verification

To verify the cryptographic integrity of a notification's lifecycle audit trail:

```sql
SELECT id, notification_id, from_status, to_status, actor, channel, occurred_at, prev_hash
FROM audit_log
WHERE notification_id = '00000000-0000-0000-0000-000000000001'
ORDER BY occurred_at ASC;
```

**Verification Rule:**
For each row $N > 0$, verify that `prev_hash` equals:
$$\text{SHA-256}(\text{prev\_hash}_{N-1} + \text{notification\_id} + \text{from\_status} + \text{to\_status} + \text{occurred\_at} + \text{failure\_reason} + \text{actor} + \text{channel})$$
The initial entry has `prev_hash = NULL` or genesis string.

---

### 2.5 Redis Rate Limiter Fail-Open Semantics

**Symptom:**
Redis is unreachable or undergoing maintenance.

**Behavior:**
Pigeon rate limiting implements **fail-open semantics**. If Redis throws a connection timeout or exception during rate-limit evaluation, Pigeon logs a warning and permits the transaction through to avoid dropping critical banking events during an in-memory cache outage.
Replays with an existing `Idempotency-Key` are automatically bypassed without consuming customer rate limit tokens.
