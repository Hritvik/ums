# Slice D1: app-only deletion requests and cancellation

The bounded local service is implemented under the [selected plan](lifecycle-plan.md).
This slice supplies local application behavior; it does not enable privacy HTTP
routes, perform erasure, or satisfy the independent recovery deployment gate.

## Application boundary

A purpose-bound trusted privacy authority permits request, cancellation, or
restricted status for one exact app/subject. The local service checks recent
Strong evidence, future/expired evidence, and an explicitly configured freshness
interval using its injected clock. No production freshness interval or accepted
IAM claim mapping is supplied by these tests. This constructor seam cannot
establish network identity or delegated authority.

Privacy mutations use bindingRevision as expectedVersion. Field edits use
profileRevision. Restricted deletion status includes the current bindingRevision
so the cancellation journey can obtain its concurrency token without reopening
ordinary profile reads.

App-only deletion stops ordinary profile reads, edits, and display projections
immediately. Onboarding and edit retries cannot cancel the request. The original
server acceptance time and 30-elapsed-day deadline survive exact retries.
Cancellation must commit strictly before the deadline and removes only the
named app-only cause. Independent IAM-global causes and closure remain in force.

The service must recheck current authority on every command retry. A successful
cancellation retry may return its already committed safe outcome after the
deadline; it cannot apply a new transition. Safe outcome replay does not return
personal field snapshots or reopen a retired profile generation.

The subsequent [local finalization slice](profile-erasure.md) adds explicitly
invoked due-request erasure. The evidence below records D1 before that addition.

## Completion and retention limits

PENDING means a retained deletion request, not erased profile data. Once its
deadline passes, cancellation is unavailable and ordinary access stays blocked,
even when no worker has run. CANCELLED describes only the app-only request;
it does not assert IAM reactivation, product permissions, or global cancellation.

No finalizer, content cleanup, downstream erasure acknowledgement, independent
privacy journal, restore protection, scheduled retention enforcement, or live
privacy capability is implemented by this slice. Those remain separate slices
and release gates. Database-local audit is useful transaction evidence but does
not prove independently durable or tamper-resistant operational audit custody.

## Persistence and Kotlin implementation

V5 adds request records, typed independent cause records, safe idempotency
receipts, and minimized audit rows. An independent closure flag survives changes
to the aggregate lifecycle. Typed immutable data classes replace the earlier
placeholder deletion-cause map, so reads and emitted state preserve each cause's
request, source revision, acceptance time, and deadline.

Requests, causes, binding revisions, audit correlation, command receipts, and
canonical state events commit atomically under the existing app-policy lock.
Request acceptance time is normalized to PostgreSQL microseconds before deriving
the deadline. Cancellation and authority checks retain the actual post-lock
clock time, including the strict deadline boundary.

Scope-qualified safe receipts include the actor, purpose, target, request
fingerprint and operation result reference. They do not retain personal-field
snapshots or authority proofs. Profile reference/generation checks prevent a
receipt or old request from resolving to a replacement generation. Cancellation
recomputes readiness from current fields and policy when returning to ACTIVE.
The separate binding and profile revisions make lifecycle changes explicit
without pretending personal fields were edited.

## Recorded verification

On 24 October 2026, `./gradlew clean check bootJar --no-daemon --console=plain`
passed with the JDK 21 and Colima settings in [foundation](foundation.md):
82 JVM tests, zero failures/errors/skips, seven contract-suite tests, and eleven
producer/input contract checks. JaCoCo reported 99.54% line and 88.14% branch
coverage without exclusions. The contract suite includes 55 UMS fixtures plus
the pinned IAM contract checks.

Thirteen privacy integration tests cover fixed deadlines and exact retries,
current authority, changed-input conflicts, restricted access, cancellation
readiness, independent global/closure restrictions, rollback, concurrent
requests/edits/cancellations, and retired-generation fences. A queued cancellation
is observed waiting on a PostgreSQL lock before advancing the test clock to the
deadline. The worker then rejects it after acquiring that lock.

Actual acknowledgements, request status, and requested/cancelled state events
are emitted by PostgreSQL tests and validated against the schemas. RED evidence
includes the initially missing service, missing schema/artifacts, required
binding concurrency token, and a runnable timestamp-rounding regression before
the acceptance precision fix. Final read-only correctness/security review found
no remaining material finding in this local slice.

These are local application tests. Global/closure fixtures are test-only state
setup, not an accepted IAM lifecycle adapter. Audit custody/retention, service
ingress, independent privacy recovery, erasure finalization and downstream
cleanup remain outstanding.
