# ADR 0004: Transactional Outbox Pattern for Zero Message Loss

## Status
Accepted

## Context
When ingesting a notification, Pigeon must perform two operations:
1. Persist the notification in PostgreSQL.
2. Publish an envelope message to RabbitMQ to trigger asynchronous delivery.

If the application commits the database transaction and then attempts to publish to RabbitMQ, a network partition or application crash occurring between the two operations results in a "dual-write" failure: the notification is saved in the database as `PENDING`, but the broker never receives it, causing silent message loss. Conversely, publishing before committing risks emitting phantom messages if the database transaction rolls back.

In a banking environment, silent message loss on fraud or transfer alerts is unacceptable.

## Decision
We pull the **Transactional Outbox Pattern** forward into **Phase 1** instead of deferring it to Phase 4:
1. **Atomic Ingestion Transaction**:
   - The notification record (`PENDING`), the initial audit log record, and an `outbox_event` record are inserted within a **single ACID transaction** in PostgreSQL.
   - The table `outbox_event` contains: `id` (UUID), `aggregate_id` (UUID), `event_type`, `routing_key`, `payload` (JSONB envelope), `created_at`, `published_at`, `publish_attempts`.
   - A partial index is created on `outbox_event (created_at) WHERE published_at IS NULL`.
2. **Outbox Relay**:
   - An asynchronous relay worker polls unpublished rows using `SELECT ... FOR UPDATE SKIP LOCKED` in batches.
   - It publishes messages to RabbitMQ with **publisher confirms** enabled.
   - Upon receiving the broker's positive confirmation (ACK), the relay updates `published_at = NOW()`.
   - In case of broker connection failure or timeout, the row remains unpublished and is retried safely.

## Consequences

### Positive
- Completely eliminates the dual-write risk (`R-01`) from day one.
- Multiple instances of Pigeon can run concurrent relay workers without lock contention due to `SKIP LOCKED`.
- Guaranteed at-least-once message publishing to RabbitMQ.

### Negative / Trade-offs
- Introduces polling overhead on PostgreSQL (mitigated by the partial index and adjustable polling intervals).
- Outbox table requires periodic pruning of historical published rows (`published_at < NOW() - INTERVAL '7 days'`).
