# Slice C: IAM consumer preparation

Slice C is in progress. Local consumer verification does not satisfy the real
IAM-to-Dutch journey exit criteria in the [implementation handoff](../ums-platform/issues/08-define-verification-and-implementation-handoff.md).

## Provider evidence and boundary

IAM W-7–W-9 are complete and independently accepted in `~/repositories/iam`:
native DPoP (W-7), app activation and `iam.app-subject.onboarding-ready.v1`
(W-8), and the onboarding relay plus app-scoped snapshot HTTP surface (W-9).
Evidence lives in IAM `docs/identity-platform/wayfinder/agent-work/w09`.

The remaining provider gap is deployment, not product code: no production
outbox transport, workload/mTLS authenticator, snapshot rate limiting, or relay
scheduler is implemented. Published schemas and local runs still do not prove
deployment, producer authentication, or delivery; UMS must keep the adapter
fail-closed and configuration-driven.

IAM wire identifiers are strings (`evt_`, `aps_`, `act_`), not UMS UUIDs.
They require durable private mappings. Trust in the issuer/environment and app
registration must come from an independently authenticated adapter configuration,
never from the event's own claims. No consumer constructor is an authentication
mechanism.

Onboarding versions order the activation aggregate, while activation versions
track separate IAM state. A changed activation ID cannot reset an existing
subject checkpoint or authorize profile re-entry. The `RECONCILIATION` reason
cannot waive gap checks, lifecycle restrictions, or erasure fences. The published
onboarding snapshot lacks complete lifecycle/privacy evidence and cannot serve
as a universal recovery authority.

## Dutch ownership and outstanding integration

Dutch ADR 0009 accepts dedicated IAM and UMS provider repositories and treats
embedded provider services as non-production reference adapters. Its older
UUID identity events and `ums.member.mapping.changed.v1` do not match shared
UMS's app-scoped profile state event. Dutch Member IDs, placeholders, claims,
canonicalization, and Ledger permissions remain Dutch-owned.

The 2026-11-01 ownership decision keeps `ums.profile.state-changed.v1` UMS-owned
and authoritative for profile lifecycle, and keeps
`dutch.member-profile.mapping-changed.v1` Dutch-owned and internal. UMS W-8
produces a Dutch-owned association report; it must not publish the Dutch mapping
event or absorb Dutch Member IDs, placeholders, claims, canonicalization, or
Ledger rules. The mapping's `profileState`/`lifecycleState` are non-authoritative
projections of UMS state. Record: `capabilities/work/decisions/onboarding-contract-ownership.yaml`.

Profile readiness never grants Ledger access.

The [local consumer journey](dutch-consumer-journey.md) connects these application
seams to a test-only product consumer; real provider acceptance remains separate.

## Remaining gates

- Accepted IAM app/resource registrations, subject mapping, producer authority,
  token kind/audience/security-version and workload/delegation validation.
- IAM deployment bindings: production outbox transport, workload/mTLS
  authentication, relay scheduling, snapshot rate limiting, bounded retry,
  delivery and gap-repair behavior, with named provider acceptance.
- IAM W-13 is gated by `ums/W-18` and the UMS Slice C provider gates; reference
  adapter tests do not close it.
- Accepted lifecycle/snapshot semantics, including activation changes and
  privacy-safe recovery.
- Dutch-owned Member mapping and protected display/status clients, with the
  real first-consumer journey and named acceptance.

Business HTTP routes remain denied. Local fixtures do not constitute provider
commitments, deployment approval, or an end-to-end integration result.

## Pinned provider contract

`contracts/dependencies/iam` retains the generated IAM 1.0.0 archive and provider
checksum, with a lock recording SHA-256
`fadba94390448f36cb0def04be812683841b8887e64e3d5e049b9d359146ff6f`.
All 80 archive entries matched the local IAM contract source on 24 October 2026.
The source directory has no Git metadata; this is a verified local generated
artifact, not a signed upstream release or provider acceptance.

Contract tests resolve 12 provider schemas offline and check 10 positive and 53
negative fixtures. Fourteen negative fixtures need semantic checks beyond JSON
Schema; the lock records them explicitly rather than claiming structural
validation proves their rejection. An RFC3339 checker is installed explicitly
so date-time validation cannot silently disappear from the Python environment.

## Local adapter behavior

`IamOnboardingAdapter` is a constructor-only application seam. It accepts a
bounded, strictly parsed onboarding event plus separately configured trusted
source and app-registration bindings. It rejects duplicate JSON keys, unknown
fields, trailing input, unexpected source/schema/constants, invalid timestamps,
and mismatched destination/aggregate/payload coordinates. Versions remain
positive signed 64-bit integers; values beyond that storage bound are rejected
without numeric rounding.

V4 stores original IAM identifiers and random internal UUID mappings privately.
Source environment, app registration, and activation ownership are pinned;
activation ownership cannot move to another subject. Original event identity
has a content fingerprint, and repeated aggregate versions cannot introduce
conflicting payloads. Onboarding and activation versions remain separate.
Reconciliation events return `RECONCILIATION_REQUIRED` without new durable
mutation until the snapshot evidence adapter exists.

The adapter uses the existing policy lock and transactional provisioning seam.
The caller must supply the same JDBC data source and transaction manager across
these collaborating services. Mapping, receipts, checkpoints, profile creation,
and its outbox event commit together. Gaps can retain bounded input receipts and
identifier mappings for retry, but cannot create a profile; changed activation
IDs require explicit reconciliation. These mappings need a privacy/retention
policy in the later lifecycle slice before production use.

Kotlin data classes express parsed facts and trusted configuration; immutable
registration copies prevent caller mutation after construction. Explicit JDBC
and transaction boundaries make the durable effects visible while avoiding
additional asynchronous machinery.

## Verification evidence

On 24 October 2026, `./gradlew clean check bootJar --no-daemon --console=plain`
passed with the JDK 21 and Colima settings in [foundation](foundation.md):
64 JVM tests, zero failures/errors/skips, six contract-suite tests, and six
producer/input contract checks. The contract suite includes the existing 43 UMS
fixtures and pinned IAM fixtures above. JaCoCo reported 99.41% line and 90.84%
branch coverage with no coverage exclusions.

The tests exercise concurrent retries, restart reuse, scoped identifier isolation,
changed event identities/payloads, activation ownership, gaps, lifecycle fences,
non-adoption, lossless large revisions, strict parsing, snapshot gating, and
rollback of all adapter effects after an injected outbox failure. Actual JSON
consumed in the successful PostgreSQL adapter test is validated against the
pinned IAM schema; it is not a substitute hand-authored producer artifact.

RED evidence includes initial missing adapter compilation, a runnable activation
rebind regression, and runnable timestamp format/precision regressions before
their fixes. The full run also caught older fixture resets and migration-count
expectations that needed updating for V4. Final read-only review found no
remaining material finding within this unwired adapter's scope.
