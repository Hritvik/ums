# Slice D4: app-departure closure and published retention

The bounded local closure service is implemented and verified. This continues the
[lifecycle plan](lifecycle-plan.md), which left closure and reactivation out of
scope. Closure starts the 90-day retained period; it does not erase anything and
does not enable an HTTP route.

## Closure boundary

A trusted application seam closes the current profile for one exact app and
subject using an already-authoritative departure fact. It is not an IAM wire
format, and no production departure adapter is wired.

Closure takes the shared policy-row lock, so it serializes with edits, deletion
requests, cancellation, and finalization. The application must be registered,
but it does not have to remain adopted: an accepted departure obligation outlives
an adoption toggle. A profile that does not exist, or that is already erased,
cannot be closed.

The authoritative closure time is stored as an absolute instant, and the
retention deadline is derived from it as 90 elapsed days. Both are persisted
together, so a retry with the same authoritative time is harmless and cannot
move the deadline. A different closure time for an already closed profile is
rejected instead of silently shortening or extending retention.

## Interaction with deletion

Closure and deletion are independent. When a deletion cause already restricts the
profile, the closure is still recorded as evidence and its retention deadline is
published, but lifecycle, binding revision, readiness, and the emitted stream are
left alone. The earlier irreversible obligation therefore governs, and no second
lifecycle event is fabricated for a state that did not change.

Closure never cancels a deletion cause, and a deletion request never cancels
closure. The existing `closure_present` column continues to record that a profile
was ever closed, so cancelling an app-only deletion returns the profile to
`CLOSED` rather than `ACTIVE`.

## Status and evidence

A closed profile refuses ordinary reads, edits, and display projections because
they already require `ACTIVE`. Its restricted status keeps the minimized binding
state, reports no unmet fields, offers `REQUEST_REACTIVATION` as the only next
action, and publishes the retention deadline. The next-actions vocabulary and the
deadline field were already reserved in the status contract; this slice populates
them.

Each accepted closure writes one minimized, PII-free lifecycle evidence row and
one canonical `ums.profile.state-changed.v1` event with reason `CLOSED` in the
same transaction as the state change. The evidence table deliberately has no
foreign key to the binding: it is the durable record a restored backup must be
reconciled against before traffic is served.

## Recorded verification

On 31 October 2026, `./gradlew clean check bootJar --no-daemon --console=plain`
passed with the JDK 21 and Colima settings in [foundation](foundation.md):
104 JVM tests, zero failures/errors/skips, seven contract-suite tests, and
sixteen producer/input checks. JaCoCo reported 99.3% line and 86.0% branch
coverage without exclusions. Contract checks include 70 UMS fixtures and the
pinned IAM artifact validation.

Six PostgreSQL closure tests cover the transition and its published deadline,
the retry with the same authoritative time, a conflicting time, concurrent
closure, closure during a pending deletion, and unavailable profiles. Actual
persisted event and status JSON are validated against the published schemas by a
producer check, including the absence of display values in the event.

RED evidence: the closure tests first failed to compile because the service and
models did not exist, and the status producer check failed because the closed
status carried a `readiness` property the first status variant does not allow.

## Remaining gates

Reactivation, closure expiry, IAM-global deletion causes, and the real departure
transport remain outstanding. No scheduler, HTTP route, or production authority
is enabled, and the retention period is a local constant until operations
version it. The next lifecycle work is [work items](work-items.md)
W-2 and W-3.
