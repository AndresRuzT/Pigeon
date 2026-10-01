# ADR 0012: Push Notification Channel and Redis Rate Limiting

## Status
Accepted

## Context
High notification volume presents risks of provider spam, denial-of-service, and fraudulent SMS pumping (generating massive billable SMS traffic through repeated OTP requests). In addition, mobile banking platforms require rich push notifications as their primary, cost-effective channel before degrading to SMS and Email.

## Decisions

1. **Third Channel: Push Notifications**:
   - Implemented via `PushSenderPort` and `PushChannelSender`, communicating over HTTP/1.1 with mobile push providers (simulated with WireMock).
   - Protected with dedicated Resilience4j `CircuitBreaker` and `Retry` policies (3 attempts with exponential backoff, client 4xx excluded from retry).

2. **Full Channel Fallback Cascade**:
   - `DeliveryOrchestratorService` executes the cascade:
     `PUSH → SMS → EMAIL` (or the customer's customized preferred order).
   - If a channel has no destination or fails after retries/circuit-open, the orchestrator immediately evaluates the next channel, logging each attempt and latency into the delivery attempt and audit logs.

3. **Dual-Bucket Redis Rate Limiting**:
   - Rate limiting is enforced at event ingestion behind `RateLimiterPort` via `RedisRateLimiterAdapter`.
   - **OTP / Security Bucket**: Key `rate:otp:{customerId}`, window 10 minutes, threshold 5 requests. Protects against SMS-pumping attacks.
   - **Standard Bucket**: Key `rate:std:{customerId}`, window 1 hour, threshold 10 requests. Protects against spam and notification flooding.
   - Idempotent replays bypass rate limiting, ensuring that network retries do not exhaust customer quotas.
   - Fail-open strategy: If Redis fails, rate limiting logs a warning and allows requests through to maintain banking service availability.

## Consequences

- **Positive**: Mitigates SMS toll fraud and provider exhaustion. Minimizes delivery costs by favoring Push over SMS. High availability resilience.
- **Negative**: Rate limits are currently per-customer rather than per-IP; IP-based rate limiting remains an API gateway concern.
