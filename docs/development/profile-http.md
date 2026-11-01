# Slice C3: profile HTTP verticals

The profile read, status, update, and app-only deletion commands are shipped. They prove the
authority path end to end: bearer token, caller resolution, scope mapping, display and
privacy authorization, strict bounded input, idempotency, and non-enumerating answers.

## Routes

`GET /api/v1/profile/status` returns the caller's own profile status.
`GET /api/v1/profile` returns the caller's own display envelope (`{data: …}`) and refuses
an unavailable or restricted profile with the same `404` as a hidden target.

`POST /api/v1/profile/deletion-requests` requests app-only deletion and
`POST /api/v1/profile/deletion-requests/{id}/cancellation` cancels it, both with the same
bounded JSON body (`expectedVersion` only) and UUID `Idempotency-Key`. Both require recent
Strong authority derived from the token: `acr` must map to strong authentication and
`auth_time` must be within the configured window (`ums.profile.privacy.max-strong-age-seconds`).
A caller with weaker or missing evidence gets `403 forbidden`, an unmapped caller gets
`404 unavailable`, and commands answer `202` with the accepted operation. The status read
returns the restricted request view.

`PATCH /api/v1/profile` accepts at most 16 KiB of `application/json` with exactly
`expectedVersion` and a non-empty `changes` object of known fields (string or explicit
null), plus a UUID `Idempotency-Key` header. Duplicate keys, trailing content, unknown
fields, and oversized bodies are `400 malformed_input`; a stale version is
`409 version_conflict`; reusing a key with different content is `409 idempotency_key_reused`;
a rejected field value is `422 invalid_field`. Success returns the resulting display, and an
exact retry returns the same revision without a second event. Authentication is
the resource server's job; the caller-authority interceptor then resolves the pinned IAM
human token into one app-scoped caller and stores it for the handler. A caller with no
mapped application or subject receives the same `404 unavailable` as a hidden target, and
an unresolved or unauthenticated caller receives `401 unauthenticated`. Responses are
`Cache-Control: no-store` and never echo a claim value.

Caller registrations are explicit deployment configuration (a JSON array), validated at
startup: no default, no wildcard, one client per app. Mapping from the caller's opaque
`app_id`/`sub` to UMS scope references uses the existing IAM mapping tables; a missing
mapping is never repaired here.

## Deliberate limits

- **Deletion, not export.** Export artifacts, display batches, and policy reconciliation are
  still unimplemented; every other route stays denied.
- **Contract correction needed.** The OpenAPI declares the status read on the collection
  (`GET /deletion-requests`) without a parameter, while the status DTO requires an operation
  reference. The implementation uses `/deletion-requests/{requestId}`; the contract should be
  corrected to match rather than the implementation bending to a gap.
- **No registration administration.** Registrations are configuration files, not an API.
- **No rate limiting.** Bounds exist per request, but no per-app/actor quota or backpressure
  is enforced yet.
- **Actor reference is local.** A name-based reference for audit and idempotency is derived
  from the registered app and client. It is not an IAM identity and must not be exported.
- **No DPoP proof verification.** The token's confirmation key is validated for shape only;
  proof-of-possession belongs with the ingress transport.
- **No rate limits or request-size enforcement** at the HTTP layer yet; the service-level
  bounds exist, but the surface does not yet reject oversized bodies.

## Verification

`ProfileStatusHttpTest` drives the interceptor and controller against disposable
PostgreSQL: a mapped caller gets its own status, an unmapped caller gets the same
`unavailable` answer, and an unauthenticated request is refused before the handler.
`CallerRegistrationsTest` proves missing, malformed, duplicate, and open-ended
configuration fails closed. The HTTP test covers the display envelope, the non-enumerating
refusal for an unmapped caller, an idempotent update and its replay, distinct version and
idempotency conflicts, malformed bodies, an invalid field value, and restricted or unmapped
profiles.

At this commit `check bootJar` passes with 211 JVM tests, 7 contract tests, 22 producer
checks, 97.0% line and 80.1% branch coverage. The application context test proves the shipped route is the
only permitted business route.

RED evidence: the first wiring attempt registered the authority filter twice and returned
`401` for authenticated requests; the chain now uses an MVC interceptor after
authentication, and the legacy expectation for unimplemented routes was corrected to the
observed non-enumerating denial.
