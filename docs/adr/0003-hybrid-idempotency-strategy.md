# ADR 0003: Hybrid Idempotency Strategy (Redis Fast-Path + PostgreSQL Source of Truth)

## Status
Accepted

## Context
Upstream core banking systems and payment orchestrators frequently retry webhook calls and event emissions due to network timeouts, load balancer reconnects, and transient failures. In banking, sending a duplicate OTP or duplicate debit alert degrades customer trust and can trigger panic.

However:
- Relying exclusively on Redis for idempotency is unsafe: Redis is an in-memory store that can be flushed, evicted under memory pressure, or fail over with asynchronous replica lag.
- Relying exclusively on PostgreSQL requires locking and can cause contention during ingestion spikes.

## Decision
We adopt a **two-tier hybrid idempotency strategy**:
1. **Scope and Identification**:
   - Every ingestion request requires an `Idempotency-Key` HTTP header.
   - The uniqueness scope is strictly `(clientId, idempotencyKey)`, preventing cross-tenant collisions.
   - The stored state includes a SHA-256 hash of the canonical request payload.
2. **Fast-Path (Redis)**:
   - On request ingress, the application performs atomic `SET key payloadHash NX EX 86400` (24-hour TTL).
   - If Redis returns that the key exists with the **identical payload hash**, Pigeon replays the previous `notificationId` with HTTP `200 OK` and header `Idempotency-Replayed: true`.
   - If the key exists with a **different payload hash**, Pigeon rejects the request with HTTP `409 Conflict` (Problem Details `idempotency-key-conflict`).
3. **Source of Truth (PostgreSQL)**:
   - The `notification` table enforces a database-level `UNIQUE (client_id, idempotency_key)` constraint.
   - If Redis is down, flushed, or in a split-brain condition, PostgreSQL guarantees that duplicate inserts fail at commit time.
   - In the event of a database unique constraint violation, the service queries the existing notification and replays its result if the payload hash matches.
   - If an ingestion transaction fails before commit, the Redis lock key is unlocked to permit valid producer retries.

## Consequences

### Positive
- Sub-millisecond duplicate detection during normal operations via Redis fast-path.
- 100% mathematical guarantee against duplicate notifications via PostgreSQL ACID unique constraint.
- Protection against key misuse (altering payload while reusing the key) via SHA-256 hash comparison.

### Negative / Trade-offs
- Slight overhead of computing SHA-256 hash on incoming event JSON.
- Two systems to monitor (Redis memory and PostgreSQL index health).
