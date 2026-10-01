# ADR 0010: Versioned Multilingual Templates and Server-Side Template Injection Prevention

## Status
Accepted

## Context
A banking notification platform requires structured, multilingual, and localized notifications across multiple delivery channels (HTML email, plain text SMS, and push notifications with title and body). 

Crucially, in a financial platform:
1. Message payloads contain user-provided variables (e.g. merchant names, amounts, account fragments). If variables are concatenated into template sources or evaluated as executable expressions, the platform becomes vulnerable to Server-Side Template Injection (SSTI) and Cross-Site Scripting (XSS).
2. Auditing and compliance require exact reconstruction of what message was delivered to a customer at any given time.
3. Unsupported or missing client locales must degrade gracefully without throwing runtime failures.

## Decisions

1. **Thymeleaf Template Engine**:
   - We adopt Thymeleaf via `ClassLoaderTemplateResolver` for compiling templates stored in `classpath:templates/`.
   - Template files are segregated by channel: `templates/email/`, `templates/sms/`, and `templates/push/`.

2. **Strict Variable Allowlisting per EventType**:
   - Each `EventType` defines an immutable allowlist of permitted variable names (e.g. `otpCode`, `amount`, `currency`, `cardLast4`, `merchantName`, `accountLast4`, `beneficiaryName`, `dueDate`).
   - Prior to evaluation, the payload data map is sanitized against the allowlist. Any unexpected key is discarded, preventing context pollution.

3. **Prevention of SSTI and Injection**:
   - Variables are injected exclusively through Thymeleaf `Context` evaluation. Variables are **never** concatenated directly into template source text.
   - In HTML email templates, only escaped expressions (`th:text`) are permitted. Unescaped evaluation (`th:utext`) is strictly forbidden.
   - Automated unit and architecture tests verify that no template file contains `th:utext`.

4. **Template Versioning and Locales**:
   - Every rendered message returns `RenderedMessage(subjectOrTitle, body, templateVersion)`. The version (e.g. `v1.0.0`) is persisted on the `notification` record and immutable audit log.
   - Supported locales in v1 are `en` (English) and `es` (Spanish). Any request with an unsupported or null locale falls back safely to `en`.

## Consequences

- **Positive**: Guaranteed immunity from SSTI and XSS attacks. Regulatory audit compliance via immutable template version tracking. Seamless multilingual support.
- **Negative**: Adding a new template variable requires updating the allowlist in `ThymeleafTemplateAdapter`.
