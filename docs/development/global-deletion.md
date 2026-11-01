# Slice D7: independent IAM-global deletion

The bounded local global-deletion service is implemented and verified. It applies
an authoritative IAM provider fact through a trusted seam; no provider transport,
token validation, or live route is enabled.

## Independence boundary

App-only deletion and IAM-global deletion are separate causes with separate
deadlines and separate provider revisions. Cancelling one never clears the other,
and a later obligation never extends an earlier deadline. The applied source
revision is stored on the binding, so a replayed or out-of-order provider fact is
rejected as stale instead of overwriting newer authority.

UMS never needs the global Human Identity or the person's other apps. The seam
accepts one app-qualified scope and an already-resolved provider revision.

## Pending and cancellation

A pending fact restricts ordinary use immediately, records the provider's own
deadline, advances the binding revision once, and publishes the canonical
deletion-requested fact. No local 30-day window is started; the deadline is the
provider's. Retention notices are withdrawn only when the global deadline is
earlier than the published retention deadline, because a later obligation cannot
extend retention and the expiry worker still governs.

Cancellation removes only the global cause and recomputes the lifecycle: a
surviving app-only cause keeps the profile restricted, a closure with a **live**
retention deadline returns it to closed, and otherwise it returns to active. The
historical closure flag is deliberately not used for this decision, because it
survives reactivation and would otherwise restore a closed state with no deadline,
which can neither be reactivated nor expire. Readiness is recomputed from
stored fields against the current policy. When the profile returns to closed, its
retention notices are re-armed, matching app-only cancellation.

## Authoritative final

A final fact erases immediately. There is no local grace period and no
cancellation step. Erasure clears the same fields as the other obligations, writes
one unified fence with the global obligation kind and the causes it superseded,
records `GLOBAL_ERASED` lifecycle evidence, marks any pending app-only request
`SUPERSEDED`, withdraws outstanding notices, and publishes the canonical erased
fact. An avatar association blocks the finalization without mutation but records
`GLOBAL_FINAL_BLOCKED` evidence, so an authoritative demand is never silently
dropped; completing a blocked final is a content-cleanup obligation.

## Recorded verification

On 1 November 2026, `./gradlew clean check bootJar --no-daemon --console=plain`
passed with the JDK 21 and Colima settings in [foundation](foundation.md):
145 JVM tests, zero failures/errors/skips, seven contract-suite tests, and
twenty-two producer/input checks. JaCoCo reported 98.2% line and 82.4% branch
coverage without exclusions.

Eleven PostgreSQL tests cover immediate restriction and the provider deadline,
both cause orders, cancellation of each cause, re-arming notices on cancellation,
a cancellation that must not resurrect a dead closure, immediate finalization with
a superseded request, replayed and stale revisions leaving state untouched,
concurrent finals, a later obligation leaving retention notices intact, and
absent, erased, and content-blocked cases.

RED evidence: the tests first failed to compile because the service and models did
not exist, and one test initially encoded a wrong expectation about equal
revisions that the service correctly rejected as stale.

## Remaining gates

The authenticated IAM lifecycle transport, real provider revisions, and the
lifecycle snapshot contract remain outstanding. The provider archive currently
publishes onboarding only; global pending, cancellation, and final schemas must be
accepted before this seam is wired to a network adapter.
