# ADR 0007: Notification Lifecycle State Machine and Immutable Audit Trail

## Status
Accepted

## Context
In transactional banking systems, an alert's lifecycle must be deterministic and fully auditable by regulatory examiners. Ambiguous states (e.g. "semi-failed", "half-delivered") or mutable logs make compliance audits difficult and can mask bugs.

Every state transition must preserve causal history: who initiated it, when, what channel was attempted, what outcome occurred, and why any failure happened.

## Decision
We enforce a strict 4-state finite state machine with immutable auditing:
1. **Canonical States**:
   - `PENDING`: Notification is accepted and persisted; waiting for delivery execution.
   - `SENT`: A provider has accepted the transmission (e.g. MailHog SMTP `250 OK` or SMS gateway HTTP `202`).
   - `DELIVERED`: Delivery to customer device/mailbox is confirmed (terminal state).
   - `FAILED`: Delivery attempt or suppression exhausted (terminal state).
2. **Domain Invariants**:
   - `DELIVERED` and `FAILED` are strictly terminal; no transition leaves them.
   - `DELIVERED` can only be transitioned into from `SENT`. Direct transitions from `PENDING` to `DELIVERED` are invalid.
   - Delivery attempts (`delivery_attempt`) and audit records (`audit_log`) are **strictly append-only**.
   - **Every state transition writes exactly one audit record** in the same database transaction.
   - Suppressed notifications (due to customer opt-out or quiet hours expiration) transition to `FAILED` with specific `failure_reason` codes (`SUPPRESSED_OPT_OUT`, `EXPIRED`).
3. **Database-Level Immutability**:
   - In PostgreSQL, the `audit_log` and `delivery_attempt` tables are protected by a database trigger that raises an exception on any `UPDATE` or `DELETE` statement.
   - The application database user role is granted only `SELECT` and `INSERT` privileges on these tables.

## Consequences

### Positive
- Transparent, tamper-evident audit history compliant with banking accountability standards.
- Prevents race conditions or logical errors from resetting or mutating completed notifications.
- Simplifies operational reporting and Prometheus metrics calculation.

### Negative / Trade-offs
- For channels with immediate acceptance (such as MailHog SMTP with policy `ON_ACCEPT`), the orchestrator must perform two consecutive transitions (`PENDING -> SENT`, then `SENT -> DELIVERED`), resulting in two audit records. This accurately reflects the fact that provider acceptance preceded delivery confirmation.
