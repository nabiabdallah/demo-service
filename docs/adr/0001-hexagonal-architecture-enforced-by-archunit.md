# 0001. Use hexagonal architecture, enforced by ArchUnit

- Status: Accepted
- Date: 2026-10-31
- Deciders: <you>

## Context
Most code in this repo will be written by AI agents. Agents follow written guidance most of the
time, but not always, and drift is silent. Integration-heavy banking services need clean
boundaries so each external system can be swapped, mocked and reasoned about in isolation.

## Decision
Use ports & adapters (domain / application / adapter / config). Encode every structural rule as an
ArchUnit test that runs in `mvn verify`. Agents are denied write access to `ArchitectureTest.java`;
changing a rule requires a human and a new ADR.

## Alternatives considered
- **Rules in CLAUDE.md only** — cheap, but advisory; violations surface late in code review.
- **Multi-module Maven build per layer** — strongest isolation, but heavier to evolve early on;
  revisit when the service grows.

## Consequences
- Positive: violations fail the build immediately, and the Stop hook makes the agent fix them.
- Negative: some boilerplate (ports, config wiring) for small features.
- Follow-ups: when adding a database, decide transaction boundaries in a new ADR.
