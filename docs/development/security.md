# Security and privacy boundaries

This is the attack record for W-16: what was attempted, what held, and what changed.

## Attacks and where they are proven

Only what is actually attempted as an automated test is listed as "denied". Everything else is
recorded as a control by construction or as a residual gap.

| Attack | Evidence | Status |
|---|---|---|
| Foreign app reads this profile | `ProfileStatusHttpTest` "a token from another application cannot see this profile" | denied 404, revision and outbox unchanged |
| Wrong subject / unmapped caller | `ProfileStatusHttpTest` unmapped-caller tests | denied with the same non-enumerating answer as a hidden target |
| Hidden-target enumeration | unmapped-caller and unknown-reference tests | identical 404 shape |
| Access after erasure | `ProfileStatusHttpTest` "an erased profile is unavailable to reads edits and exports" | display, edit and export denied; status reports the terminal fact without display values |
| Denied path mutates state | same test and "a denied foreign-app request changes no state" | revision, receipts, exports, and outbox unchanged |
| Display values or tokens in stored facts, logs, or error bodies | `ProfileStatusHttpTest` "stored facts, logs, and error bodies carry no display values or tokens" | none in outbox payloads, audit correlation ids, receipt fingerprints, captured logs, or error bodies |
| Bulk reads starving privacy work | `RateLimitTest` and the MVC chain test | per-identity budgets; privacy commands get the smallest |
| Forged or expired token hammering | `RateLimitTest` "unauthenticated traffic is bounded before the security chain" | per-address budget before the security chain |
| Secrets in shipped sources | `SecretScanTest`, `scripts/security-audit.sh` | none found |
| Replayed valid token (`jti` reuse) | none | **not detected**; see residuals |
| Stale security version against a revocation list | claim shape/range only (`IamCallerResolverTest`) | **not compared to a registry**; see residuals |
| Forged delegation | no delegation or workload profile is accepted (`IamCallerResolverTest` rejects a workload token) | fail-closed by absence; no delegation token can be presented |
| Cross-app listing | no listing surface is implemented | **not present**; the internal routes remain gated |

Deferred behind W-11: the cross-scope avatar-reference attack. Avatar attachment is disabled, so
the surface does not exist yet.

## Rate limits

`RateLimitInterceptor` applies a fixed one-minute window per `(app, subject, bucket)` before any
handler runs:

| Bucket | Default | Bucket contents |
|---|---|---|
| reads | 60/min | `GET` profile, status, operation status |
| writes | 20/min | `PATCH` profile |
| privacy | 10/min | deletion request/status/cancellation, export request/status/capability/download |

A refusal is the safe error envelope with `429 rate_limited` and `Retry-After: 60`.

Unauthenticated or forged traffic is bounded separately: `PreAuthRateLimitFilter` runs before the
security chain and limits `/api/v1/**` per remote address (120/min, a fifth in degraded mode), so a
malformed or expired token cannot be hammered for free.

`ums.rate-limit.degraded=true` is a startup setting that tightens every bucket to a fifth; it never
removes a limit. It is an operator assertion that shared limit infrastructure is degraded, not a
runtime detection. Limits are in-process and per identity; a multi-instance deployment would need a
shared store, which this best-effort tier does not claim.

## Scanning

- `SecretScanTest` runs in the normal build over `src/main` and `contracts` for private keys,
  cloud keys, GitHub/Slack/OpenAI tokens, and JWT-shaped strings.
- `./gradlew securityAudit` runs gitleaks (with `.gitleaks.toml` allowing the fake contract
  fixtures) and osv-scanner (v1.9.2) when installed. CI installs both with `go install`
  (`github.com/zricethezav/gitleaks/v8@v8.30.1`, `osv-scanner@v1.9.2`) and wires the task into
  `check`; locally a missing scanner is a warning, and `CI=true` makes it a failure.
- Advisories found by the first scan were fixed by raising the Boot BOM versions: Tomcat 10.1.61,
  Jackson 2.21.8, log4j-api 2.26.1, PostgreSQL 42.7.12, commons-compress 1.28.0, commons-lang3
  3.18.0.
- Accepted risks are recorded in `osv-scanner.toml` with a reason: two `spring-webmvc` advisories
  whose fix exists only in Spring Framework 7 / Boot 4 (a major upgrade tracked separately), and
  BouncyCastle/OpenTelemetry advisories that only affect Kotlin build-tool configurations and are
  never packaged in the runtime artifact.
- A finding in the shipped runtime is recorded with its severity; unresolved critical or high
  runtime findings block the release. Build-tool-only findings are listed as accepted risks with
  the reason that they are never packaged, and they do not silently disappear: the ignore file is
  the record.

## Residual findings and gates

- Token `jti` replay is not detected: a stateless resource server accepts a valid, unexpired token
  more than once. Mitigation is short token lifetimes and TLS; a replay cache is a future gate.
- There is no server-side security-version registry; the claim is validated for shape and range,
  not compared to a revocation list.
- Rate limits are per instance, not shared. The pre-authentication limit keys on the socket
  address; behind a proxy every caller shares one bucket unless forward-header handling is
  configured, and enabling it naively would let a client rotate `X-Forwarded-For`.
- `spring-webmvc` carries two accepted advisories with no 6.2.x fix; the remediation is the
  Spring Framework 7 / Boot 4 upgrade.
- Build-tool-only BouncyCastle and OpenTelemetry advisories are documented and ignored; they are
  reachable only from the Kotlin compiler's own classpaths.
- R-1: `spring-webmvc` 6.2.x has two accepted 9.8 advisories whose fix is Spring Framework 7.0.9,
  available only with a Spring Boot 4 major upgrade. That upgrade is a tracked follow-up in this
  document, not part of W-16.
- The avatar cross-scope attack is deferred behind W-11.
- External scanner databases are fetched at CI time; offline builds skip them by design.
