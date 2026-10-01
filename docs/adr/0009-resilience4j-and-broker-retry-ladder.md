# ADR 0009: Two-Tier Resiliency Strategy (Resilience4j In-Process and Broker Retry Ladder)

## Status
Accepted

## Context
In high-throughput financial notification systems, external provider communication is subject to transient errors (network hiccups, timeouts, gateway 5xx spikes, rate-limit 429s) and catastrophic outages (provider downtime).
Naively retrying inside consumer worker threads blocks thread pools, increases database connection holding times, and risks cascading thread starvation across all tenants.
Conversely, immediately dead-lettering every failed delivery request degrades service quality and overwhelms operations with transient errors.

## Decision
We implement a **two-tier resiliency model**:

### Tier 1: In-Process Resiliency via Resilience4j
- **Scope**: Applied per outbound channel adapter (`SmsChannelSender`, `EmailChannelSender`).
- **Isolation**: Separate `CircuitBreaker`, `Retry`, `TimeLimiter`, and `Bulkhead` instances per channel (e.g. `sms` vs `email`). An SMS provider outage will trip the `sms` circuit breaker but leave the `email` circuit breaker completely unaffected.
- **Fail-Fast**:
  - Provider 4xx errors (e.g. invalid phone number/destination) are classified as non-retryable and immediately trigger channel fallback without retry delays.
  - Open circuit breakers (`CallNotPermittedException`) fail fast in < 1 ms, triggering immediate channel fallback (`SMS -> EMAIL`).
  - Provider 5xx errors and timeouts undergo exponential backoff retries (3 attempts, initial 500 ms wait, multiplier 2) before tripping the circuit breaker.

### Tier 2: Broker-Level Delayed Retry Ladder
- **Scope**: Applied to informational (`LOW` priority) messages when all primary and fallback channels are temporarily unavailable.
- **Mechanism**: Dead-letter exchange (`pigeon.retry`) with fixed-TTL dead-letter queues:
  1. `pigeon.retry.30s` (30-second TTL)
  2. `pigeon.retry.2m` (2-minute TTL)
  3. `pigeon.retry.10m` (10-minute TTL)
- **TTL Expiry**: When message TTL expires in the retry queue, RabbitMQ dead-letters the message back to `pigeon.events` with routing key `low` without blocking consumer threads.
- **Critical Exemption**: Security-critical `HIGH` priority messages (such as OTP and fraud alerts) do not enter the delayed retry ladder to avoid delivering expired, obsolete authentication codes to customers.
- **Poison Message Isolation**: Unparseable payloads or messages violating schema/sensitive data invariants bypass all retries and are sent directly to `pigeon.dlq` with diagnostic headers (`x-failure-reason`, `x-exception-message`).

## Consequences

### Positive
- Worker threads are never blocked for minutes waiting for backoff timers.
- Outages in one channel do not affect delivery capabilities of other channels.
- Zero risk of poison message delivery loops halting RabbitMQ consumer queues.
- Exponential recovery backoff natively handled by RabbitMQ AMQP TTLs.

### Negative / Trade-offs
- Additional RabbitMQ queue topology to monitor in Prometheus/Grafana.
- Messages in retry queues experience delayed delivery during extended external provider outages.
