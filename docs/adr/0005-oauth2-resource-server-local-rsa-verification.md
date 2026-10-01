# ADR 0005: OAuth2 Resource Server with Local RSA Key Pair Verification

## Status
Accepted

## Context
Pigeon must secure all REST ingestion and management endpoints using industry-standard OAuth2 Bearer Tokens (JWT). In production banking platforms, identity is managed by enterprise IDPs (Keycloak, Okta, Ping Identity).

However, for local development, reproducible demonstrations, and automated continuous integration (CI) tests:
1. Spinning up a heavy Keycloak container significantly slows down CI test runs and consumes excessive memory on local workstations.
2. Hardcoding symmetric secrets (e.g. HMAC-SHA256 secret strings) into code or configuration repositories violates security best practices and fails security scanning.
3. Using `alg=none` or mock filters bypasses real token validation mechanisms.

## Decision
We configure Pigeon as an **OAuth2 Resource Server** using asymmetric **RS256** signatures:
1. **Local RSA-2048 Key Pair**:
   - A dedicated RSA 2048-bit key pair (`private_key.pem` and `public_key.pem`) is provided for development and test environments.
   - Spring Security is configured with `NimbusJwtDecoder` using the RSA public key.
2. **Security Checks**:
   - Validates cryptographic signature using RS256.
   - Explicitly rejects unsigned tokens or tokens specifying `alg=none`.
   - Validates token expiration (`exp`) and not-before (`nbf`).
   - Extracts the caller identity from the `azp` (Authorized Party) or `sub` / `client_id` claim.
   - Enforces scope-based authorization (`SCOPE_notifications:write`, `SCOPE_notifications:read`, `SCOPE_preferences:write`, `SCOPE_preferences:read`).
3. **Developer Script (`scripts/dev-token.sh`)**:
   - A lightweight bash/Java script generates valid signed JWTs with arbitrary scopes and expiration for local testing and curl experiments without requiring an external Identity Provider container.

## Consequences

### Positive
- Fully realistic, standards-compliant JWT signature and scope validation in Spring Security.
- Zero-dependency local and CI execution: no external OAuth2 provider container required.
- Seamless transition to production: only the public key / JWKS URL configuration property changes.

### Negative / Trade-offs
- Private key used for testing must be clearly documented as a development fixture and prohibited in production deployments.
