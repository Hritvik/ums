# Slice D3: bounded due-deletion processing

The bounded local batch service is implemented and verified. This slice coordinates the existing
[local finalizer](profile-erasure.md) for one bounded page of due requests.
It does not enable a scheduler, HTTP route, service bean, or live worker.

## Processing boundary

The caller supplies trusted maintenance authority for one exact app. A batch
selects only PENDING app-only requests due by a fixed cutoff, ordered by deadline
and request reference. Page size is bounded from 1 to 100. Selection takes no
row locks; the finalizer reacquires the existing application lock and rechecks
current authority, generation, cause, and deadline before each mutation.

A continuation cursor binds the app, fixed cutoff, and last attempted key. It
advances past blocked and retryable items as well as successful items, so a
transient failure at the start of a page cannot prevent later due requests from
being attempted. A cursor is continuation metadata, not authority or a
snapshot of the database. Late changes may require a fresh pass.

Each finalization uses its own transaction. An ambient caller transaction is
rejected to prevent accidental all-or-nothing batch behavior. A rollback for one
item cannot undo an earlier committed erasure. Concurrent batches can select
the same work; the finalizer's locks and completion fences prevent duplicate
transitions/events. Reported completion observations are not an exactly-once
worker execution guarantee.

## Failures and retry

Expected blocked conditions produce a safe BLOCKED result. Recognized transient
database/availability failures produce RETRYABLE. Results contain opaque request
references and outcome codes, not exception messages, SQL, or profile fields.

Unexpected failures, including schema/integrity defects, propagate rather than
being disguised as retryable work. Previously committed items remain committed;
the caller must inspect authoritative state and repair the failure before
continuing. No successful whole-batch outcome is fabricated for that case.

A fresh invocation without a cursor starts a new pass, including previously
blocked/retryable items. This is not a durable retry queue, fairness guarantee,
lease protocol, or automatic backoff policy. Cursor persistence, monitoring,
scheduling, authenticated workload registration, independent privacy recovery,
and real downstream cleanup remain separate operational work.

## Implementation details

The pending-request index follows the app-qualified deadline/request-reference
keyset. The query reads at most page size plus one to decide whether continuation
is available; it does not lock candidates or hold a batch-wide transaction.
Cutoff and cursor timestamps use canonical UTC microseconds. Future cutoffs,
foreign-app cursors, positions beyond cutoff, and invalid page sizes are rejected
before candidate selection.

Small immutable Kotlin DTOs express the cursor, outcome enum, and minimized
report. Expected finalizer blocks use a distinct exception type. Corrupt cause,
completion-fence, and pending-lifecycle evidence remains an invariant failure
outside that caught type, so a batch cannot silently relabel corruption as a
routine block. The runner relies on database commits and completion fences,
rather than an in-memory processed set.

## Recorded verification

On 25 October 2026, `./gradlew clean check bootJar --no-daemon --console=plain`
passed with the JDK 21 and Colima settings in [foundation](foundation.md):
98 JVM tests, zero failures/errors/skips, seven contract-suite tests, and fourteen
producer/input checks. JaCoCo reported 99.21% line and 87.07% branch coverage
without exclusions. Contract checks include 69 UMS fixtures and the pinned
IAM artifact validation.

Eight PostgreSQL batch tests cover blocked-head continuation, SQLSTATE 40001
transient failure with later retry, SQLSTATE 23514 integrity failure with prior
commits preserved, ambient transaction and cursor/page validation, app isolation
and fixed cutoff, concurrent runners, corrupted cause propagation, and tied
deadline paging in PostgreSQL UUID order without skips or duplicates. Actual
batch output is schema-validated, including cursor/app/cutoff consistency.

RED evidence includes the initially missing batch service/schema/artifact and a
runnable corruption-classification regression before its fix. Final read-only
review found no remaining material finding in this bounded scope. All database
work used disposable containers; no live scheduler, credentials, user data, or
external service was changed.
