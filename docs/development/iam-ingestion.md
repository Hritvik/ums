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
quarantine into a personal-data archive. Authorization failures are classified
apart from poison: an unregistered application and a payload/binding mismatch get
their own classes, so operators do not have to read a security event as a schema
defect.

Operators can resolve a quarantine and read the open count and oldest open time,
which is the signal a future scheduler and alerting will use.

## Snapshot repair

Repair consumes the accepted `iam.onboarding-snapshot.v1` page for one app-scoped
subject. It is bounded, strictly parsed, and requires the page to be complete:
a partial page (`hasMore`) is rejected because absence from a truncated page is
not evidence. The page's application must equal the authenticated workload
binding.

Repair takes the shared application lock and only advances a lagging input
checkpoint to the snapshot's onboarding version, with a monotonic conditional
update so two concurrent repairs cannot regress it. It never creates a profile,
never restores an erased one, never clears a deletion cause, and never reopens a
closed or deletion-pending profile. A pinned activation that disagrees is reported
for reconciliation rather than overwritten, and an equal or older version changes
nothing.

A truncated page is valid evidence for a subject it contains, but not for one it
omits: a present subject repairs, an absent one returns page-incomplete instead of
no-evidence. Each accepted repair writes one `SNAPSHOT_RECONCILED` evidence row.
It emits no state event, because a checkpoint advance is not a profile change, and
it does not reconcile profile fields or readiness — only the ordering checkpoint.

## Deliberate limits

- **No transport.** Producer authentication is a named gate: UMS needs an
  accepted workload registration and transport binding (the provider publishes an
  mTLS workload certificate pattern for its own snapshot endpoint, not a
  consumer-ingestion contract). Until that exists the endpoint is not exposed.
- **No retry driver.** Quarantined payloads are resolved by an operator or a
  future worker; there is no automatic replay, which is deliberate because replay
  needs the accepted transport identity.
- **Quarantine retention.** Rows are minimized (digest, class, correlation) but
  unsalted, so a guessed payload can be confirmed; they must be covered by the
  retention schedule and an erasure check before production. Database failures
  propagate instead of being quarantined, because a transient outage is not a
  poison payload.

## Verification

At this commit, `./gradlew clean check bootJar --no-daemon --console=plain` passed
with 186 JVM tests, zero failures/skips, 7 contract tests, 22 producer checks, and
98.1% line / 81.4% branch coverage.

`IamEventIngestionTest` covers a valid event applied exactly once, duplicate
replay, a version gap returning reconciliation, malformed, unknown-field, and
foreign-application payloads quarantined without mutation, producer mismatch, size
limit, invalid encoding, digest-only storage, quarantine resolution, and the
rejection of an empty payload at the trust boundary.

`IamSnapshotRepairTest` additionally covers checkpoint convergence without new
profile state, the equal-version no-op, refusal for erased, closed, and
deletion-pending profiles, activation disagreement, missing local binding versus
missing snapshot evidence, and rejection of malformed, unattributable, partial,
oversized, duplicate-key, and undeclared-field pages.

RED evidence: the first ingestion run showed a real gap — the transport
application was not compared with the payload destination, so a foreign
application id still applied. The adapter now requires both to agree.
