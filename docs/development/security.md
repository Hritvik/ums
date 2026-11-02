# Security and privacy boundaries

This is the attack record for W-16: what was attempted, what held, and what changed.

## Attacks and where they are proven

| Attack | Test | Result |
|---|---|---|
| Foreign app reads this profile | `ProfileStatusHttpTest` "a token from another application cannot see this profile" | denied, 404, no state change |
| Wrong subject / unmapped caller | `ProfileStatusHttpTest` unmapped-caller tests | denied with the same non-enumerating answer as a hidden target |
| Replayed or stale authentication | `ProfileStatusHttpTest` "the strong-authentication window is exact"; `IamCallerResolverTest` expiry/clock ordering | denied |
| Stale security version / forged delegation | `IamCallerResolverTest` claim shapes, token profiles, unpublished delegation profiles | denied fail-closed |
| Cross-app listing | `ProfileStatusHttpTest` foreign-app and collection-status tests | only the caller's own operation is visible |
| Hidden-target enumeration | unmapped-caller and unknown-reference tests | identical 404 shape |
| Access after erasure | `ProfileStatusHttpTest` "an erased profile is unavailable to reads edits and exports" | display, edit and export denied; status reports the terminal fact without display values |
| Denied path mutates state | same test and "a denied foreign-app request changes no state" | revision and receipts unchanged |
| Bulk reads starving privacy work | `RateLimitTest` and the rate-limit interceptor | per-app/subject budgets, privacy commands get the smallest |
| Display values or tokens in stored facts | `ProfileStatusHttpTest` "stored facts and error bodies carry no display values or tokens" | none in outbox, receipts, audit, or error bodies |
| Secrets in shipped sources | `SecretScanTest`, `scripts/security-audit.sh` | none found |

Deferred behind W-11: the cross-scope avatar-reference attack (a reference owned by another
subject or app). Avatar attachment is disabled, so the surface does not exist yet; it is recorded
as an open gate rather than claimed.

## Rate limits

`RateLimitInterceptor` applies a fixed one-minute window per `(app, subject, bucket)` before any
handler runs:

| Bucket | Default | Bucket contents |
|---|---|---|
| reads | 60/min | `GET` profile, status, operation status |
| writes | 20/min | `PATCH` profile |
| privacy | 10/min | deletion request/status/cancellation, export request/status/capability/download |

A refusal is the safe error envelope with `429 rate_limited` and `Retry-After: 60`. When shared
limit infrastructure is degraded, `ums.rate-limit.degraded=true` tightens every bucket to a fifth
(never removes it), so the local window still bounds a bulk reader that could otherwise queue
privacy work behind display reads. Limits are in-process: a multi-instance deployment would need a
shared store, which this best-effort tier does not claim.

## Scanning

- `SecretScanTest` runs in the normal build over `src/main` and `contracts` for private keys,
  cloud keys, GitHub/Slack/OpenAI tokens, and JWT-shaped strings.
- `./gradlew securityAudit` runs gitleaks and osv-scanner when installed. In CI the task is wired
  into `check` and fails when either scanner is missing, so a release cannot skip it silently.
- A finding is recorded with its severity; unresolved critical or high findings block the release.

## Residual findings and gates

- Token `jti` replay is not detected: a stateless resource server accepts a valid, unexpired token
  more than once. Mitigation is short token lifetimes and TLS; a replay cache is a future gate.
- There is no server-side security-version registry; the claim is validated for shape and range,
  not compared to a revocation list.
- Rate limits are per instance, not shared.
- The avatar cross-scope attack is deferred behind W-11.
- External scanner databases are fetched at CI time; offline builds skip them by design.
