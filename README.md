# demo-service

A Spring Boot 4.1 / Java 21 hexagonal service, created by `/java-pipeline:new-project`.
It starts empty: layer packages, an `ErrorHandler`, architecture rules and CI, no business code.
The agentic pipeline — **spec → plan → architect review → failing tests → implementation →
`./mvnw verify` gate → code review** — comes from the `java-pipeline` plugin; this repo holds only
code, docs and project permissions.

## First run

The whole path (machine setup, GitHub repo and secrets, deploy, upgrades) is in claude-factory's
`docs/ONBOARDING.md`. This project is pinned to the java-pipeline release that created it (`ref` in
`.claude/settings.json`); upgrade by bumping it after reading the plugin's CHANGELOG.

The build inherits `factory-parent` from GitHub Packages, so Maven needs a read token: a
`<server id="github">` in `~/.m2/settings.xml`, and for CI the repo secret `FACTORY_PACKAGES_TOKEN`
(classic PAT with only `read:packages`). See the claude-factory README, "Shared build".

```bash
./mvnw verify      # must be green before you hand anything to an agent
claude             # accept the workspace-trust prompt and the plugin install prompt
```

Check the plugin loaded: `claude plugin details java-pipeline` (skills, agents, hooks), and in a
session `/hooks` (PostToolUse + Stop from the plugin) and `/plugin` (java-pipeline enabled). Then
start your first feature:

```
/java-pipeline:new-feature <what you want to build>. Write the spec first.
```

Requires `jq` on your PATH (the plugin hooks parse their JSON input with it).

## Runtime

- Actuator runs on its own **management port 8081** (claude-factory ADR 0014); port 8080 serves only the API.
  There: Kubernetes probes `/actuator/health/liveness|readiness` (no details) and `/actuator/prometheus`.
  Nothing else is exposed.
- **Metrics:** Prometheus scrapes pods by the `prometheus.io/*` annotations; `http_server_requests_seconds`
  has latency histograms, tagged by URI template (`/accounts/{id}`), never the raw path.
- **Traces:** OpenTelemetry over OTLP to a Collector (placeholder `otel-collector.monitoring:4318`, set in
  `deploy/k8s/base/deployment.yaml`); sampling 100% in staging, 10% in prod; W3C `traceparent` in and out.
  Raw request URLs are removed from spans (`config/ObservabilityConfig`). Logs carry `traceId`/`spanId`.
  Locally nothing is exported unless you set `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`.
- Cluster: label Prometheus' and the Collector's namespace `claude-factory/monitoring=true`; the
  NetworkPolicy admits the management port and OTLP only from/to there.
- Logs are ECS JSON by default; run locally with plain text:
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.
- Graceful shutdown: in-flight requests get up to 20s when the pod stops.

## Image and deployment

- CI pushes `ghcr.io/nabiabdallah/<service>:<sha>` and `:main` on every push to `main` (Jib, distroless,
  non-root; no Docker needed). PRs only run `verify`.
- `deploy/k8s/` (claude-factory ADR 0012): `base/` (Deployment, Service, PodDisruptionBudget, default-deny
  NetworkPolicy), `overlays/staging` (1 replica) and `overlays/prod` (3), and `components/recreate`.
  Once per cluster: create the namespace `<service>-<env>`, the database Secret in it, and label the ingress
  controller's namespace `claude-factory/ingress=true` (the NetworkPolicy admits traffic only from there; it is
  enforced only if the cluster's network plugin supports NetworkPolicy).
  ```bash
  kubectl -n <service>-prod create secret generic <service>-db --from-literal=DB_URL=... --from-literal=DB_USERNAME=... --from-literal=DB_PASSWORD=...
  kubectl apply -k deploy/k8s/overlays/prod
  ```
- **Promote** = a PR that sets an overlay's `newTag` to a commit on `main` (CI pushed and signed its image).
  Never `main` or `latest`. The PR review is the gate; `.github/scripts/check-deploy.sh` (CI job `manifests`)
  rejects mutable tags, commits not on `main`, edited released migrations and rollbacks across a migration.
- **Breaking migration** (`-- rollout: recreate`, see `CLAUDE.md`): the promotion that crosses it must add
  `components: [../../components/recreate]` to the overlay (all old pods stop first: downtime), and the next
  promotion must remove it. CI enforces both. Prefer expand/contract, which keeps the rolling update.
- Build the image locally without pushing (needs network to gcr.io): `./mvnw -DskipTests package jib:buildTar`.

## Build rules

`factory-parent` checks the build (claude-factory ADR 0013): Maven Enforcer at `validate` (Java 21+, Maven 3.9+,
pinned plugins, no SNAPSHOT or banned dependencies such as JUnit 4, log4j 1 or `javax.*`, no dependency built for
Java 22+), and a coverage floor at `verify`: 80% of lines and 70% of branches (JaCoCo; report in
`target/site/jacoco/index.html`, and a `coverage` artifact in CI). Raise the floor in this POM with
`factory.coverage.line.minimum` / `factory.coverage.branch.minimum`; lower values fail the build.

## Supply chain

CI scans the image on every run (Trivy; accepted findings in `.trivyignore.yaml` with a reason and expiry) and,
on `main`, signs the pushed digest and attests the SBOM (Spring Boot's `META-INF/sbom/application.cdx.json`). Verify an image:

```bash
cosign verify ghcr.io/nabiabdallah/<service>@sha256:<digest> \
  --certificate-identity-regexp '^https://github.com/nabiabdallah/<service>/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

## What's here

| Path | Role |
|---|---|
| `CLAUDE.md` | Always-loaded context: commands, architecture, rules. Keep it short. |
| `.claude/settings.json` | Permissions (allow Maven/git, ask before push, deny `.env` and edits to `ArchitectureTest`) and enablement of `java-pipeline@claude-factory`. |
| `src/main/java/.../adapter/in/web/ErrorHandler.java` | RFC 9457 problem responses; 404/422 for domain errors, generic 500 with an `errorId`. |
| `src/test/.../ArchitectureTest.java` | The architecture as build-breaking rules. |
| `src/test/.../ApplicationContextTest.java` | Boots the full Spring context. |
| `docs/specs/` | Specs with numbered acceptance criteria — the agents' source of truth. |
| `docs/adr/` | Architecture decisions. |
| `.github/workflows/factory-feature.yml` | Unattended runs: the label `factory:spec` on an issue → a spec PR from its idea; the label `factory:build` on an issue naming an approved spec → pipeline → PR (`.github/scripts/factory-run.sh`; needs the secret `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY`). |
| `.github/workflows/ci.yml` | CI: `./mvnw verify`, image scan, deploy gate (`manifests`), image push + signing on `main`. |

## Evolving it
- Add a rule to `ArchitectureTest` every time a review catches the same structural mistake twice.
- Pipeline changes (agents, skills, hooks) belong in the `claude-factory` repo, not here.
