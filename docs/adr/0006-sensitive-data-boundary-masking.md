# ADR 0006: Sensitive Financial Data Boundary Validation and Irreversible Masking

## Status
Accepted

## Context
Under banking regulations (such as PCI DSS 4.0 and GDPR/financial privacy standards), storing or logging Primary Account Numbers (PAN / credit or debit card numbers) or full bank account numbers is strictly regulated or prohibited unless encrypted under HSM controls and audited.

For a notification microservice, storing full card or account numbers is unnecessary: customer notifications only require the last 4 digits (e.g. `**** 4821`) so the customer can recognize which card or account is referenced.

Allowing full card numbers to enter the application layer creates risks of accidental leakage into database dumps, application logs, cache entries, or broker queues.

## Decision
We enforce a **multi-layered defensive masking and boundary rejection policy**:
1. **Boundary Rejection at REST Ingestion**:
   - Upstream producers are required by contract to send only `cardLast4` and `accountLast4` (4 digits).
   - Inbound DTOs and JSON payloads are scanned by a custom validator:
     - Any digit sequence of 13 to 19 digits that satisfies the **Luhn checksum algorithm** is recognized as a full card number.
     - Any contiguous sequence of 10 or more digits is treated as an unmasked account or PAN.
   - If detected, the request is immediately rejected at the HTTP boundary with HTTP `400 Bad Request` and RFC 7807 Problem Details (`sensitive-data-rejected`).
   - The rejected payload is **never persisted, never cached, and never logged in clear text**.
2. **Domain Value Objects**:
   - The domain model uses type-safe value objects: `MaskedCardNumber` and `MaskedAccountNumber`.
   - These value objects validate that their content strictly conforms to the masked pattern `**** XXXX` and forbid initialization from full numbers.
3. **Defense-in-Depth Logging Filter**:
   - A custom Logback converter/filter inspects formatted log messages and replaces any 12+ digit sequence with masked digits before stdout emission.
   - One-Time Passwords (OTP codes) and security bearer tokens are explicitly excluded from logs.

## Consequences

### Positive
- Prevents accidental compliance violations and data breaches before data touches internal queues or database tables.
- Clear, immediate feedback to upstream services violating security contracts.
- Guarantees that logs and database snapshots remain safe for developer inspection.

### Negative / Trade-offs
- Slight CPU overhead running the Luhn check on incoming JSON strings.
- Upstream systems must be responsible for extracting the last 4 digits before posting events.
