# Slice A: internal app-isolated profile provisioning

This slice implements the first profile behavior behind an internal service
interface. It does not enable an HTTP route or connect to IAM. The foundation's
deny-all application security boundary remains intact.

## Supported behavior

- A configuration authority explicitly registers app adoption and its initial
  profile policy. Exact retries are harmless; policy changes are refused until
  deliberate policy reevaluation is implemented.
- `ProfileProvisioningService.provision` consumes a `TrustedOnboardingFact`
  containing already resolved UMS app/subject scope references. This type is a
  development/application seam, not an IAM event schema or proof of authority.
- First contiguous eligible entry creates one binding and a fresh profile
  reference. Default policy is ready; required fields remain incomplete because
  this slice does not accept personal profile fields.
- `status` reads existing state without creating a profile. Scope checks prevent
  using another application as the permitted context. The caller must already
  have authority for the exact subject; this method is not authentication.
- Same-app creation is serialized through the policy row, while complete scope
  keys separate issuers, apps, and subjects. This is a deliberate low-throughput
  starting point, not a claim of high-concurrency scaling.
- Inbox receipts, source checkpoints, binding creation, and one canonical
  `ums.profile.state-changed.v1` outbox event commit in one transaction.
- Duplicate/stale input cannot create another generation. Gaps request repair;
  CLOSED, DELETION_PENDING, and ERASED fixtures remain restricted. No lifecycle
  transition or re-entry journey is implemented.

## Contracts and tests

`./gradlew check` runs the Kotlin tests, schema fixtures, coverage gates, and
`producerContractTest`. The last check validates the event JSON actually stored
by the PostgreSQL integration test against the published state-event schema.
Its generated input is under `build/generated-contract-fixtures/`; hand-authored
fixtures are not substituted for producer output.

Use the JDK/Docker/Python setup in [foundation](foundation.md). A running Docker
daemon is required, and database tests are not silently skipped. The tests use
disposable containers and do not connect to your application database.

The behavior tests cover adoption, default/strict readiness, complete-scope
isolation, duplicates, gaps/stale checkpoints, event identity consistency,
concurrent first entry, policy conflicts, atomic rollback, and lifecycle fences.
Schema tests additionally check primitive limits, privacy exclusions, and
lossless revisions. These are local implementation checks, not provider acceptance.

### Recorded verification

On 24 October 2026, `./gradlew clean check bootJar --no-daemon --console=plain`
passed with JDK 21 and the local Colima Docker context supplied explicitly:
41 Kotlin tests, no failures/skips, 43 schema fixtures, and the persisted-event
producer check. JaCoCo reported 98.5% line and 96.6% branch coverage.

The initial missing service API produced the test-first RED compilation result.
Two later runnable regressions were observed failing before their fixes: a
generation-based outbox stream key, and changed input reusing an acknowledged
stale event's identity. The final clean build includes both fixes. Stale events
retain receipt fingerprints without advancing checkpoints; gaps remain
unconsumed until repair. Read-only correctness/security review found no
remaining material finding within this internal slice.

## Kotlin concepts used

Small data classes and enums express scope, policy, readiness, and outcomes.
Properties are `val`; database state changes occur in explicit transactions,
not by mutating shared in-memory profile objects. Nullable status represents
absence honestly rather than manufacturing a profile. An injected `Clock`
makes event time controllable. Coroutines and Reactor are not required here.

## Remaining boundaries

The subsequent [editing slice](profile-editing.md) adds field edits, readiness
recalculation, and protected display reads; the original verification above
records Slice A's historical checkpoint.

The service and registry have constructors rather than production bean wiring.
Before real ingress, add an accepted IAM app-subject mapping and authority
adapter, producer validation, registered callers, security-version enforcement,
and the existing provider gates. HTTP status/profile DTOs,
policy reevaluation, lifecycle operations, event transport/repair adapters,
content/notice integration, independent privacy recovery, and deployment remain
future slices. A structurally valid trusted fact must never be accepted as
authenticated network input by itself.
