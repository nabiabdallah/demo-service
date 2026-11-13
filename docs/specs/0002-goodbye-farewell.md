# Spec: Goodbye farewell endpoint

- Status: Draft
- Owner: issue #8 author
- Related ADRs: 0001 (hexagonal architecture); builds on spec 0001 (hello greeting), which stays unchanged

## Problem
A caller wants a farewell counterpart to the existing `GET /hello` greeting: a simple HTTP way to get a
"Goodbye" message for a given name, or a generic one when no name is given.

## Scope
**In:**
- `GET /goodbye?name=X` returns a farewell for `X`.
- `GET /goodbye` without `name` returns the farewell for `world`.
- Name validation at the web boundary, with the same rules as spec 0001.

**Out (explicitly not doing):**
- Changing `/hello` (spec 0001 still requires 400 for a missing name; only `/goodbye` defaults).
- Authentication or authorization (the service has none yet).
- Localization, persistence, external calls, any state.
- Idempotency keys (read-only, no state change).

## Contract
`GET /goodbye?name={name}`

| Part | Definition |
|---|---|
| Query `name` | string, optional; when present: trimmed length 1..50, no control characters (same as spec 0001) |
| 200 response | `application/json`: `{"message": "Goodbye, <name>!"}`; `{"message": "Goodbye, world!"}` when `name` is absent |
| 400 | RFC 9457 problem (via `ErrorHandler`) for blank, too-long or control-character `name` |
| 405 | problem response for non-GET methods |

Note the response field is `message` (not `greeting` as in `/hello`).

Layering: controller in `adapter/in/web` calls a use-case interface in `application/port/in` (command record
with `name`, result record with `message`), implemented by an `application` service. No port/out is needed.
The name is not echoed in logs, metric tags or span attributes (CLAUDE.md, ADR 0014 reference).

## Authorization
Anyone who can reach the service may call it; no scope is required and no caller data is touched (stateless,
reads nothing). This holds until the service gains authentication.

## Acceptance criteria
1. Given `name=Alice`, when `GET /goodbye?name=Alice`, then 200 with `{"message":"Goodbye, Alice!"}`.
2. Given `name=  Alice  ` (surrounding whitespace), when called, then the message uses the trimmed name `Alice`.
3. Given no `name` parameter, when `GET /goodbye`, then 200 with `{"message":"Goodbye, world!"}`.
4. Given `name=` or only whitespace, when called, then 400 with a problem body (assumes Q1).
5. Given a `name` longer than 50 characters (after trimming), when called, then 400 with a problem body.
6. Given a name containing a control character (`name=a%0Ab` or `name=a%00b`), when called, then 400 with a
   problem body that does not repeat the supplied name.
7. Given a non-ASCII name such as `Zoë` or `李雷`, when called, then the message contains it unchanged, as UTF-8.
8. Given a name containing markup such as `<b>x</b>`, when called, then it is returned only as a JSON string
   value with `Content-Type: application/json`.
9. Given `POST /goodbye`, when called, then 405.
10. Given any request, when handled, then the name does not appear in log output, and no metric tag or span
    attribute carries it.
11. Given concurrent requests with different names, when handled, then each response contains only its own name.
12. Given `GET /hello` without `name`, when called, then it is still 400 (spec 0001 unchanged).
13. The use-case service has a unit test with no Spring context, and `ArchitectureTest` passes.

## Failure modes
- Blank, too-long or control-character input: 400 problem response, generic detail that does not repeat the name.
- Unknown entity, downstream timeout, partial failure, duplicate request, other caller's data: not applicable
  (stateless, no dependencies, idempotent by nature).
- Unexpected error: 500 via `ErrorHandler` with `errorId`.

## Rollout
Additive read-only endpoint, no schema change; old and new pods can run together. Rollback means removing it.

## Non-functional
Trivial latency (in-memory string formatting). The name is treated as potential PII: not logged, not a tag.
No retention (nothing stored).

## Open questions
- Q1: Should a present-but-blank `name` (`name=` or whitespace only) be 400 or fall back to "world"? The idea
  only says "without a `name`" defaults. Recommended: 400, same as `/hello` ("everything else is the same"), so a
  blank value is treated as an invalid name rather than silently replaced.
