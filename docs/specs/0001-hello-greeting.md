# Spec: Hello greeting endpoint

- Status: Approved
- Owner: issue #3 author
- Related ADRs: 0001 (hexagonal architecture)

## Problem
A caller wants a simple way to get a greeting for a given name via HTTP. This is the first use case of the
service and also exercises the full spec → build pipeline end to end.

## Scope
**In:**
- `GET /hello?name=X` returns a greeting for `X`.
- Input validation of `name` at the web boundary.

**Out (explicitly not doing):**
- Authentication or authorization (the service has none yet).
- Localization / language selection.
- Persistence, external calls, or any state.
- Idempotency keys (read-only, no state change).

## Contract
`GET /hello?name={name}`

| Part | Definition |
|---|---|
| Query `name` | string, required, trimmed length 1..50 |
| 200 response | `application/json`: `{"greeting": "Hello, <name>!"}` |
| 400 | RFC 9457 problem (via `ErrorHandler`) for missing, blank or too-long `name` |
| 405 | problem response for non-GET methods |

Layering: controller in `adapter/in/web` calls a use-case interface in `application/port/in`
(command record with `name`, result record with `greeting`), implemented by an `application` service. No
port/out is needed. The name is not echoed in logs, metrics tags or span attributes (it is user data; ADR 0014
in CLAUDE.md forbids raw values as tags).

## Authorization
Anyone who can reach the service may call it; no scope is required and no caller data is touched (the endpoint
is stateless and reads nothing). This holds until the service gains authentication.

## Acceptance criteria
1. Given `name=Alice`, when `GET /hello?name=Alice`, then the response is 200 with `{"greeting":"Hello, Alice!"}`.
2. Given `name=  Alice  ` (surrounding whitespace), when called, then the greeting uses the trimmed name `Alice`.
3. Given no `name` parameter, when called, then the response is 400 with a problem body and no stack trace or internal detail.
4. Given `name=` or only whitespace, when called, then the response is 400 with a problem body.
5. Given a `name` longer than 50 characters, when called, then the response is 400.
6. Given a non-ASCII name such as `Zoë` or `李雷`, when called, then the greeting contains it unchanged and is returned as UTF-8.
7. Given a name containing markup such as `<b>x</b>`, when called, then it is returned as a JSON string value only, with `Content-Type: application/json`.
8. Given `POST /hello`, when called, then the response is 405.
9. Given any request, when it is handled, then the name does not appear in log output, and no metric tag or span attribute carries it.
10. Given concurrent requests with different names, when handled, then each response contains only its own name (no shared state).
11. The use-case service has a unit test with no Spring context, and `ArchitectureTest` passes.

## Failure modes
- Invalid or missing input: 400 problem response, generic detail that does not repeat the supplied name.
- Unknown entity, downstream timeout, partial failure, duplicate request, other caller's data: not applicable
  (stateless, no dependencies, idempotent by nature).
- Unexpected error: 500 via `ErrorHandler` with `errorId`.

## Rollout
Additive new endpoint, no schema change; old and new pods can run together. Rollback means removing it.

## Non-functional
Trivial latency (in-memory string formatting). The name is treated as potential PII: not logged, not a tag.
No retention (nothing stored).

## Decisions
Answered by the owner on 2026-11-13, each the recommended answer:
- Q1, length and trimming: trim, then 1 to 50 characters.
- Q2, missing or blank `name`: 400 problem response, no default.
- Q3, response format: JSON `{"greeting": "Hello, <name>!"}`.
- Q4, characters: reject control characters (400); JSON encoding handles the rest.

## Open questions
- None.
