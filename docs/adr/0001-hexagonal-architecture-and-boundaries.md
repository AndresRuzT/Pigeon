# ADR 0001: Hexagonal Architecture and Architectural Boundaries

## Status
Accepted

## Context
Pigeon is a transactional notification engine for banking services. In financial and core-adjacent software systems, business rules (such as state machines, policies for mandatory notifications, quiet hours, and data masking) must remain completely decoupled from technical frameworks (Spring Boot, JPA/Hibernate, RabbitMQ, Jackson, HTTP clients). 

Without strict isolation:
1. Framework upgrades or migrations can introduce subtle behavioral changes to domain rules.
2. Business policies become difficult to test without heavy application context or mocks.
3. Leaking database or transport concepts into core entities violates regulatory auditability and clean domain-driven design principles.

## Decision
We adopt **Hexagonal Architecture (Ports and Adapters)** with three strictly isolated layers:
- `domain`: Contains entities, value objects, domain services, policies, domain exceptions. It relies solely on the standard Java Development Kit (JDK 21) and must never import or reference any framework or library (no Spring, JPA, Jackson, etc.).
- `application`: Contains use-case orchestrators (inbound ports) and contracts for external dependencies (outbound ports). It depends strictly on `domain`. The only framework exception is Spring's `@Transactional` to coordinate business boundaries.
- `infrastructure`: Contains all technical adapters (REST controllers, JPA repositories, Flyway migrations, RabbitMQ listeners/publishers, Redis caches, HTTP/SMTP channel senders, configuration classes).

We enforce these boundaries automatically in continuous integration using **ArchUnit** tests. Any violation of these dependency rules immediately fails the build.

The Maven structure is kept as a single-module repository in Phase 1 to minimize build complexity while relying on ArchUnit for strict logical boundary enforcement.

## Consequences

### Positive
- Pure business logic is 100% testable in milliseconds with JUnit 5 without starting Spring or containers.
- Technology changes (e.g. switching message broker or template engine) do not touch domain code.
- Clear separation of concerns simplifies security and compliance reviews.

### Negative / Trade-offs
- Requires explicit mapping layers between domain objects, JPA persistence entities, and external REST/AMQP DTOs.
- Increased number of classes and boilerplate mappers.
