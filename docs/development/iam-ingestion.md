# Slice C2: bounded IAM event ingestion

The ingestion service is implemented and verified. It accepts an already-attributed
producer payload, applies strict bounds and parsing, and quarantines anything it
cannot trust. No HTTP route or producer transport is enabled.

## Trust boundary

The caller supplies a `TrustedProducerRequest` carrying the application and
producer the transport has already authenticated. The service never reads those
from the payload, and the payload's own `destination.appRegistrationId` must equal
the authenticated application: neither side decides the binding alone.

A mismatched producer, an oversized body, or invalid UTF-8 is quarantined before
any parsing. Strict field parsing, schema checks, and registration lookup happen
next; a failure is quarantined as schema-invalid before any durable write, so a
poison payload cannot leave a partial profile behind.

## Outcomes and quarantine

Applied, duplicate, stale, reconciliation-required, not-adopted, and restricted
map from the existing onboarding adapter. A gap returns reconciliation rather than
provisioning. Poisoned payloads are recorded with a stable failure class, the
correlation id, and a payload digest only — never the body, which would turn a
quarantine into a personal-data archive.

Operators can resolve a quarantine and read the open count and oldest open time,
which is the signal a future scheduler and alerting will use.

## Deliberate limits

- **No transport.** Producer authentication is a named gate: UMS needs an
  accepted workload registration and transport binding (the provider publishes an
  mTLS workload certificate pattern for its own snapshot endpoint, not a
  consumer-ingestion contract). Until that exists the endpoint is not exposed.
- **No snapshot repair.** The accepted `iam.onboarding-snapshot.v1` contract will
  reconcile a divergent binding; it is the next vertical within this slice and
  must refuse to restore an erased profile or clear a deletion cause.
- **No retry driver.** Quarantined payloads are resolved by an operator or a
  future worker; there is no automatic replay, which is deliberate because replay
  needs the accepted transport identity.

## Verification

At this commit, `./gradlew clean check bootJar --no-daemon --console=plain` passed
with 167 JVM tests, zero failures/skips, 7 contract tests, 22 producer checks, and
97.7% line / 80.3% branch coverage.

`IamEventIngestionTest` covers a valid event applied exactly once, duplicate
replay, a version gap returning reconciliation, malformed, unknown-field, and
foreign-application payloads quarantined without mutation, producer mismatch, size
limit, invalid encoding, digest-only storage, quarantine resolution, and the
rejection of an empty payload at the trust boundary.

RED evidence: the first run showed a real gap — the transport application was not
compared with the payload destination, so a foreign application id still applied.
The adapter now requires both to agree.
