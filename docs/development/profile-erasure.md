# Slice D2: local app-only erasure finalization

The bounded local application service is implemented and verified. This extends [request and cancellation](profile-deletion.md)
with an explicitly invoked local finalizer; no scheduler or live privacy route
is enabled. Independent recovery, operational custody, and consumer cleanup
remain deployment gates.

## Finalization boundary

A narrow trusted maintenance authority identifies the exact scope and request.
It executes already accepted intent rather than acting as a human or renewing
Strong authentication. Constructor arguments are trusted local seams, not
network authorization. Acquire the same application lock as other mutations,
then sample time. A request becomes due at or after its original deadline.

Only a matching, due, PENDING app-only cause for the current profile generation
can finalize. Cancellation and finalization serialize; a cancelled or not-yet-due
request cannot erase data. Earlier IAM obligations are not inferred by this
worker, while a later global cause cannot extend the accepted app-only deadline.

The transaction clears UMS-owned profile display fields, removes the active
profile reference/generation/revision, writes an ERASED binding with a newer
binding revision, clears active causes and closure state, marks the request
LOCAL_COMPLETED, and records minimized audit and the canonical ERASED event.
A durable fence links the completed request and retired generation to the
resulting erased binding revision. Input checkpoints and necessary opaque
identifier mappings remain to reject historical onboarding/replay.

Completed retries return the same safe outcome without another event or state
transition. Scoped privacy status remains available only for the request that
matches the current erasure fence. Old cancelled requests and later replacement
generations cannot be resolved through it. Ordinary profile reads/edits and old
command receipts cannot restore erased fields.

## Content and completion limits

The current runtime does not support accepted avatar attachment. An unexpected
non-null avatar association blocks finalization without mutation, pending an
accepted content cleanup integration; this worker cannot silently claim that a
content owner's data was deleted.

LOCAL_COMPLETED describes this local UMS transaction. It does not mean published,
delivered, acknowledged, or erased from a product, content service, backup, or
external cache. A canonical state event plus authoritative request status is
sufficient for this slice; no separate downstream completion promise is added.

No exports, local caches, or search indexes exist in the implemented runtime.
Adding them must extend the erasure transaction/fencing behavior before they can
serve personal data. Privacy receipts and lifecycle evidence retain only safe
references and purpose-limited metadata; later retention work must enforce their
documented disposal schedule without removing necessary replay fences.

## Configuration and authority limits

Accepted deletion obligations do not depend on the app adoption toggle at
finalization time. The maintenance service requires the existing scoped policy
row for serialization, but does not require adoption to remain enabled.
Fresh Strong authority for the exact existing request can read restricted
privacy status even after adoption is disabled. New requests and cancellation
retain the existing adoption check; disabled-policy status does not advertise
cancellation eligibility. A production de-adoption workflow remains a separate
decision. The current policy registry rejects live policy changes rather than
silently providing that workflow.

The subsequent [batch-processing slice](deletion-batch.md) coordinates bounded
pages of due requests. The evidence below records the D2 checkpoint.

## Recorded verification

On 24 October 2026, `./gradlew clean check bootJar --no-daemon --console=plain`
passed with the JDK 21 and Colima settings in [foundation](foundation.md):
90 JVM tests, zero failures/errors/skips, seven contract-suite tests, and thirteen
producer/input checks. JaCoCo reported 99.43% line and 86.98% branch coverage
without exclusions. Contract checks include 59 UMS fixtures plus the pinned
IAM artifact validation.

Eight PostgreSQL finalization tests cover due boundaries, avatar blocking,
wrong scope/cancelled requests/replaced generations, atomic rollback after field
clearing, concurrent retries and cancellation at the deadline, retained
completion fences, old patch/privacy replay rejection, independent global
causes, app isolation, and accepted obligations after adoption is disabled.
Actual persisted ERASED event JSON and completed-status output pass the published
schemas. Final read-only review found no remaining material finding in this
bounded local scope.

RED evidence includes the initially missing finalizer, the previously rejected
LOCAL_COMPLETED status fixture, missing output artifacts, and a runnable
disabled-adoption status regression before its authorization fix. These tests
use disposable databases; no live data, external content, or product data was
erased. Scheduler, authenticated maintenance ingress, independent recovery,
retention sweeps, and real downstream cleanup remain outstanding.
