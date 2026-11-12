# Service — Agent Guide

Spring Boot service, Java 21, Maven wrapper. Hexagonal (ports & adapters) architecture.
Pipeline (agents, skills, hooks) comes from the `java-pipeline` plugin — not from this repo's `.claude/`.

## Commands
- Build + all gates: `mvnw-sandboxed -q verify` (plugin helper: `./mvnw` + sandbox proxy; same args)
- Unit tests only: `mvnw-sandboxed -q test`
- One test class: `mvnw-sandboxed -q test -Dtest=ErrorHandlerTest`
- Format: `mvnw-sandboxed -q spotless:apply`
- Run locally: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` (plain-text logs; default is ECS JSON)
- Actuator on management port 8081 only: probes `/actuator/health/liveness|readiness`, `/actuator/prometheus`.
  Metrics/spans use URI templates, never raw paths; don't add raw ids as tags or span attributes (ADR 0014).

- Image: built and pushed by CI on `main` only (Jib); don't add a Dockerfile. Manifests: `deploy/k8s/` (base +
  `overlays/staging|prod`); deploying = a PR setting an overlay's `newTag` to a commit on `main`. Never `main`/`latest`.

## Architecture (enforced by `ArchitectureTest` — the build fails if you break it)
```
com.example.demo
├── domain/                  pure Java: entities, value objects, domain exceptions. NO framework imports.
├── application/
│   ├── port/in/             use-case interfaces + command/result records
│   ├── port/out/            interfaces the app needs (repositories, gateways, clocks)
│   └── *Service             use-case implementations. NO Spring, NO adapter imports.
├── adapter/in/web/          REST controllers → call port/in only. ErrorHandler lives here.
├── adapter/out/<name>/      one package per integration, implementing a port/out interface
└── config/                  Spring wiring (@Configuration). Only place that knows everything.
```
Each layer's `package-info.java` says what belongs there. Empty layers are fine; a rule applies as
soon as the first class lands in its layer.

## Rules
- Amounts are `BigDecimal`-based value objects with explicit scale and rounding. Never `double`/`float`.
- Business-rule violations throw a `DomainException` subclass (`NotFoundException` → 404, others → 422).
  Exception messages reach clients and logs: never put raw identifiers, amounts or PII in them.
- Validate input at the web boundary (Bean Validation) or with a `DomainException`; a bare
  `IllegalArgumentException` is treated as a bug (500).
- New external integration = new port in `port/out` + adapter in `adapter/out/<name>`. Never call an
  external system from `application` or `domain`.
- Anything that changes state on retry (payments, orders, messages) takes a client-supplied idempotency key. Once callers are authenticated, the key is unique per
  (caller, key): another caller's same key is a separate request, never a replay of someone else's response.
- Never log full account numbers, customer identifiers with amounts, or tokens.
- Tests first. Every use case has a unit test with in-memory fakes; no Spring context in unit tests.
  `ApplicationContextTest` boots the full context to catch wiring mistakes.

## Workflow
1. Start features with `/java-pipeline:new-feature`. Every feature starts from a spec in `docs/specs/` (copy `_template.md`). No spec → draft one with `/java-pipeline:intake <idea>` and get it approved.
2. Plan before coding; get the plan reviewed by the `java-pipeline:architect-reviewer` agent.
3. Work is done only when `./mvnw verify` passes — the plugin's Stop hook enforces this.
4. Significant design decisions → an ADR in `docs/adr/` (use `/java-pipeline:write-adr`).

## Don't
- Don't edit `ArchitectureTest` or `archunit.properties` to make a build pass. If a rule is wrong, stop and say so.
- Don't add dependencies without stating why in the plan.
- Don't lower `factory.coverage.*.minimum`, exclude classes from JaCoCo, skip Enforcer/JaCoCo
  (`-Denforcer.skip`, `-Djacoco.skip`) or exclude a banned dependency from the rule to get green: add tests or
  remove the dependency. The floor (80% lines, 70% branches) comes from factory-parent.
- Don't read `.env` or anything under `secrets/`.
- Stage files by path when committing; never `git add -A` / `git add .` (sandbox placeholders under `.claude/` break it).
