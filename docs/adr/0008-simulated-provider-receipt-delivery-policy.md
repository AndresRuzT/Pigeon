# ADR 0008: Simulated Provider Receipt and Delivery Confirmation Policies

## Status
Accepted

## Context
Real-world delivery channels differ in how they acknowledge delivery:
- Standard SMTP servers acknowledge accepting the message (`250 OK`), but do not provide an immediate synchronous receipt confirming that the message reached the user's specific inbox.
- Modern SMS gateways (e.g. Twilio) and Push Notification Services (e.g. FCM/APNs) accept messages asynchronously (`202 Accepted`) and later send asynchronous delivery receipts (DLR) via signed webhooks.

In an educational simulation using MailHog and WireMock, we must define clear delivery confirmation semantics without blocking the pipeline indefinitely.

## Decision
We define two explicit confirmation policies per channel:
1. **`ON_ACCEPT` Policy**:
   - Used for **Email** (MailHog SMTP):
   - When the SMTP server responds with success (`250 OK`), the channel sender marks the notification as `SENT` (recording attempt and audit), and immediately executes the transition to `DELIVERED` with an audit record.
   - This models standard transactional email behavior in the simulation.
2. **`ON_RECEIPT` Policy**:
   - Used for **SMS** and **Push** (introduced in Phases 2 and 3 via WireMock):
   - When the simulated HTTP gateway returns `200/202`, the notification transitions to `SENT` and records a provider reference ID (`provider_ref`).
   - The notification remains in `SENT` until a signed webhook receipt (`POST /api/v1/webhooks/{channel}/receipts`) is received by Pigeon, at which point it transitions to `DELIVERED` or `FAILED`.
   - The webhook endpoint verifies HMAC signatures using `PIGEON_WEBHOOK_SECRET`.

## Consequences

### Positive
- Accurately mirrors real-world provider protocol differences.
- Enables end-to-end testing of both synchronous SMTP flows and asynchronous webhook delivery receipts.
- Satisfies Domain Invariant 2 (`DELIVERED` only from `SENT`) across all channels.

### Negative / Trade-offs
- Two different paths for reaching the `DELIVERED` status depending on channel configuration.
