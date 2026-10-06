# Contributing to Pigeon

Thank you for your interest in contributing to **Pigeon**! As an open-source project aiming for production-grade engineering in financial services, we hold high standards for code quality, architectural discipline, and comprehensive testing.

---

## 1. Code of Conduct

All contributors are expected to uphold our [Code of Conduct](CODE_OF_CONDUCT.md). Please ensure respectful and professional communication at all times.

---

## 2. Development Setup

### 2.1 Prerequisites
- **Java**: OpenJDK 21 or later (Amazon Corretto recommended).
- **Build Tool**: Maven 3.9+ (or use the provided `./mvnw` wrapper).
- **Containers**: Docker and Docker Compose (required for running infrastructure and Testcontainers).

### 2.2 Local Environment Verification
Clone the repository and run the test suite:

```bash
git clone https://github.com/andres/pigeon.git
cd pigeon
./mvnw clean verify
```

This command executes unit tests, ArchUnit architectural rule validations, Testcontainers integration tests against PostgreSQL and RabbitMQ, and runs the JaCoCo coverage gate.

---

## 3. Engineering Guidelines & Architectural Rules

Pigeon enforces strict **Hexagonal Architecture (Ports and Adapters)**. Violating these rules will fail the CI build.

### 3.1 Domain Purity
- Classes in `io.github.andres.pigeon.domain` must be **100% pure Java**.
- **Do not** import annotations or classes from Spring, Jakarta Persistence/Validation, Jackson, Resilience4j, or Micrometer into the domain.
- Domain invariants, state transitions, and business rules belong in domain entities and value objects.

### 3.2 Ports and Adapters
- Use cases (inbound ports) belong in `io.github.andres.pigeon.application.port.in`.
- SPIs / infrastructure contracts (outbound ports) belong in `io.github.andres.pigeon.application.port.out`.
- Outbound port interfaces must return domain models or standard JDK types—never framework-specific types.
- Technical implementations belong in `io.github.andres.pigeon.infrastructure`.

### 3.3 Test Coverage & Quality Gates
- Every new feature or bug fix must include appropriate unit and integration tests.
- **Coverage Policy**: Minimum **85% line coverage** on `io.github.andres.pigeon.domain.*` and `io.github.andres.pigeon.application.*` is enforced by JaCoCo.
- Architecture rules must not be bypassed; keep `HexagonalArchitectureTest` green.

---

## 4. Commit Message Convention

We follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```text
<type>(<scope>): <short summary>

[optional body]

[optional footer]
```

### Allowed Types:
- `feat`: A new feature or capability.
- `fix`: A bug fix.
- `docs`: Documentation updates or additions.
- `refactor`: Code changes that neither fix a bug nor add a feature.
- `perf`: Performance improvements.
- `test`: Adding or correcting tests.
- `chore`: Build process, dependencies, or tool configurations.

### Examples:
- `feat(outbox): implement publisher confirms with correlation data`
- `fix(ratelimit): handle fail-open when redis connection times out`
- `docs(api): update openapi spec with webhook receipt parameters`

---

## 5. Pull Request Process

1. Fork the repository and create a feature branch (`git checkout -b feat/your-feature-name`).
2. Implement your changes following the architectural and code guidelines.
3. Verify that all tests and coverage checks pass:
   ```bash
   ./mvnw clean verify
   ```
4. If modifying database schemas, create a new sequential Flyway migration (`V<N>__description.sql`). **Never modify existing applied migrations.**
5. If adding architectural decisions, document them via a new ADR in `docs/adr/`.
6. Submit a Pull Request clearly describing the problem solved, design choices, and verification steps.
