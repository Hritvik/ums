# Slice E1: retention notice intents

The bounded local notice service is implemented and verified. It publishes
app-routed intents for the 14-day and 7-day closure-expiry windows. It holds no
contact details, chooses no channel or wording, and never claims that a notice was
delivered. No scheduler, HTTP route, or adapter is enabled.

## Delivery boundary

A window intent is published per notice class with the profile scope, the
published retention deadline, a deterministic deduplication identity, and a
delivery state. The intent carries no contact detail, message body, or channel:
the product or notice owner decides how to reach the person.

The deduplication identity is bound to the closure instance, not only to the app,
subject, and class. A profile that is closed again after reactivation therefore
gets fresh intents, while retries for the same closure remain idempotent.

A closure observed after a window start has already passed is reported as passed
and never shifted. The retention deadline is read from the closure record and is
never extended or recomputed by this service, so a late observation shortens the
notice opportunity rather than the retention period.

## States and suppression

Intents move from `QUEUED` to `HANDED_OFF`, and from `HANDED_OFF` to
`ACKNOWLEDGED`. A queued or handed-off intent may be reported `FAILED` with a
safe failure code and retried, because delivery failure must not silently extend
retention or imply successful notice. Out-of-order transitions are refused.

Reactivation, closure expiry, and app-only erasure suppress every outstanding
intent for the scope, so a person who comes back does not receive an expiry
notice, and an erased profile does not leave a notice that fires after the data
is gone. Acknowledged intents are left as history.

## Recorded verification

On 1 November 2026, `./gradlew clean check bootJar --no-daemon --console=plain`
passed with the JDK 21 and Colima settings in [foundation](foundation.md):
121 JVM tests, zero failures/errors/skips, seven contract-suite tests, and
twenty-one producer/input checks. JaCoCo reported 98.8% line and 84.4% branch
coverage without exclusions, including 71 contract fixtures.

Seven PostgreSQL notice tests cover both windows and their deterministic
identities, the idempotent retry, a late-observed closure that skips the passed
window, the delivery state machine and its refusals, reactivation suppression
with fresh intents after a second closure, suppression by both erasure paths, and
active or absent profiles. A producer check validates an actual persisted intent
against the published `notice-intent.json` and confirms it carries no contact,
channel, wording, or personal value.

RED evidence: the notice tests first failed on a real defect they exposed — the
app-only finalizer did not clear the closure columns introduced by the closure
slice, so erasing a previously closed profile violated the closure constraint.
The test suite also showed that an idempotent retry returned no notice reference,
which would have made a caller unable to act on the existing intent.

## Remaining gates

No notice adapter, delivery provider, scheduler, or monitoring exists; delivery
states are recorded locally and no acknowledgement is propagated anywhere yet.
Scheduling the notice job, alerting on missed windows, and real delivery
ownership are part of the operations work. A notice must not be enabled for a
consumer until its notice responsibility, wording, and failure behaviour are
accepted.
