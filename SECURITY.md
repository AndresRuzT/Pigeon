# Security Policy

## 1. Supported Versions

We provide security updates and patches for the following versions:

| Version | Supported |
|---|---|
| `1.0.x` | :white_check_mark: |
| `< 1.0.0` | :x: |

---

## 2. Reporting a Vulnerability

If you discover a security vulnerability in **Pigeon**, please report it responsibly:

- **Do NOT** disclose vulnerabilities publicly via GitHub Issues or public pull requests.
- Please send a detailed report to the repository maintainer via private security advisory on GitHub or email to `andresruztp@outlook.com`.
- Include the following details:
  - Description of the vulnerability and attack scenario.
  - Steps to reproduce or proof-of-concept (PoC).
  - Affected components, configurations, or dependencies.
  - Any proposed remediations.

We will acknowledge receipt within 48 hours and coordinate a coordinated disclosure schedule.

---

## 3. Security Architecture & Controls

Pigeon is engineered with banking-grade security principles:

### 3.1 Sensitive Data Boundary Validation (PCI-DSS & PII)
- Incoming payloads undergo strict boundary validation before entering the domain core:
  - **Luhn Algorithm Validation:** Payloads containing 13–19 digit numeric sequences matching credit/debit card numbers are rejected immediately with HTTP 400 Problem Details.
  - **Account Run Detection:** Long numeric sequences ($\ge 10$ digits) require masking (e.g., `**** 4821`).
  - **Masking Log Layout:** A custom Logback layout (`MaskingPatternLayout`) masks any residual PAN-like sequences before logs reach disk or stdout.

### 3.2 Authentication & Authorization
- Pigeon operates as an **OAuth2 Resource Server** validating RS256 asymmetric signatures against public keys (`PIGEON_JWT_PUBLIC_KEY`).
- Ingestion endpoints enforce scopes (`notifications:write`, `notifications:read`).
- Provider webhook endpoints enforce HMAC-SHA256 signatures (`X-Signature`) using constant-time comparison (`MessageDigest.isEqual`) to prevent timing side-channel attacks.

### 3.3 Database Least Privilege & Audit Immutability
- Dedicated application role `pigeon_app` is restricted to `SELECT` and `INSERT` on audit tables.
- PostgreSQL database triggers block all `UPDATE`, `DELETE`, and `TRUNCATE` operations on `audit_log` and `delivery_attempt`.
- A cryptographic SHA-256 hash chain (`prev_hash`) guarantees tamper-evident causal integrity across notification lifecycle state transitions.

### 3.4 SSTI (Server-Side Template Injection) Prevention
- Thymeleaf templates use strict HTML escaping (`th:text`) exclusively. Unescaped expressions (`th:utext`) are strictly forbidden.
- Templates enforce event-specific variable allowlists to prevent expression execution from arbitrary user input.
