# Slice C3: first profile HTTP verticals

Two business reads are shipped: profile status and own display. They prove the authority
path end to end: bearer token, caller resolution, scope mapping, display authorization,
and a non-enumerating answer.

## Routes

`GET /api/v1/profile/status` returns the caller's own profile status.
`GET /api/v1/profile` returns the caller's own display envelope (`{data: …}`) and refuses
an unavailable or restricted profile with the same `404` as a hidden target. Authentication is
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

- **Two of eight operations.** Profile update, deletion request/cancellation, display
  batches, and policy reconciliation are still unimplemented; every other route stays
  denied.
- **No registration administration.** Registrations are configuration files, not an API.
- **Display read only.** Mutations need the caller actor reference and idempotency header;
  the update route is not shipped.
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
configuration fails closed. The HTTP test also covers the display envelope and the
non-enumerating refusal for an unmapped caller. The application context test proves the shipped route is the
only permitted business route.

RED evidence: the first wiring attempt registered the authority filter twice and returned
`401` for authenticated requests; the chain now uses an MVC interceptor after
authentication, and the legacy expectation for unimplemented routes was corrected to the
observed non-enumerating denial.
