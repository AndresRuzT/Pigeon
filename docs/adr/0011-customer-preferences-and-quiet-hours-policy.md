# ADR 0011: Customer Preferences, Quiet Hours Policy, and Notification Deferral

## Status
Accepted

## Context
Commercial, promotional, or non-urgent notifications must respect customer contact preferences and quiet hours to prevent disturbing customers during non-business hours and to comply with telecommunication regulations. Conversely, mandatory security notifications (such as OTP verification codes or urgent fraud warnings) must be delivered immediately 24/7 without exception.

## Decisions

1. **Quiet Hours Definition**:
   - Normal business hours are strictly defined as Monday through Friday from 08:00 (inclusive) to 18:00 (exclusive) in the customer's configured time zone (defaulting to UTC).
   - Any time outside this window is classified as **Quiet Hours**:
     - Weekdays before 08:00 or at/after 18:00.
     - Entire 24 hours of Saturday and Sunday.

2. **Mandatory Security Exemption**:
   - `MandatoryMessagePolicy.isMandatory(eventType)` governs critical messages (`OTP_REQUESTED`, `FRAUD_SUSPECTED`).
   - Mandatory messages bypass all quiet hours rules, opt-out categories, and channel restrictions. They are dispatched immediately regardless of time or day.

3. **Deferred Status Lifecycle**:
   - Non-security notifications arriving during quiet hours are marked `DEFERRED` with a calculated `scheduled_at` timestamp pointing to 08:00:00 of the next business day (e.g. next morning on Mon-Thu; next Monday at 08:00 on Friday night or weekends).
   - Deferred notifications are persisted in PostgreSQL with a dedicated index (`idx_notification_deferred`) and are **not** forwarded to the message broker immediately.
   - A background scheduler (`DeferredNotificationScheduler`) periodically queries due deferred notifications using `SELECT ... FOR UPDATE SKIP LOCKED`, transitions them from `DEFERRED` to `PENDING`, writes an audit record, and enqueues them into the outbox for delivery.

4. **Opt-Out Handling**:
   - If a customer opts out of a category (e.g. `MARKETING`, `PAYMENT_REMINDER`), the notification is persisted as `FAILED` with `failure_reason = SUPPRESSED_OPT_OUT` and logged in the immutable audit trail.

## Consequences

- **Positive**: Strict regulatory adherence; protection of customer rest periods; zero delay for life-critical banking and security messages; safe multi-instance concurrency via `SKIP LOCKED`.
- **Negative**: Non-urgent messages may experience multi-day delivery delays if posted over holiday weekends.
