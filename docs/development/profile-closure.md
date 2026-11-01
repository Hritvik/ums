# Slice D4–D5: closure, published retention, and reactivation

The bounded local closure and reactivation services are implemented and verified.
This continues the [lifecycle plan](lifecycle-plan.md), which left closure and
reactivation out of scope. Closure starts the 90-day retained period and
reactivation is the only path back; neither erases anything, and no HTTP route is
enabled.

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

## Reactivation boundary

Only an explicit reactivation command reopens a retained profile. Login, edits,
retried onboarding facts, and status reads cannot, and the command is refused for
an erased profile, for a profile with an app-only or IAM-global deletion cause,
for an application that has been de-adopted, and for a legacy closed row that
never published a retention deadline.

Reactivation must complete strictly before the published deadline; at the
deadline it is already too late, whether or not an expiry worker has run. It
takes the same policy-row lock as closure, so the two serialize and a closure
cannot land between the deadline check and the update.

On success the lifecycle returns to `ACTIVE`, the binding revision advances once,
the retention deadline is cleared while `closure_present` keeps the closure
history, and one `REACTIVATED` event and one lifecycle evidence row commit with
the state change. Readiness is recomputed from the stored fields against the
current policy revision, so a profile that no longer satisfies today's required
fields reports incomplete rather than claiming readiness. Stored display values
are never rewritten by reactivation.

## Closure expiry

Expiry executes the retention promise that closure already published. It is not a
new deletion request: it opens no grace period, offers no cancellation, and never
moves the deadline. A profile whose deadline has not passed is reported as not
due, and anything that is not currently a closed generation is skipped without
mutation, which is how a deletion obligation or a reactivation that lands after
selection wins the race.

The expiry seam takes the shared policy lock as well, so it cannot interleave with
edits, closure, reactivation, deletion, or finalization. Because the obligation
was accepted while the app was adopted, expiry does not require adoption to
remain enabled; only the registered policy row is required for serialization.

Erasure clears exactly the fields the deletion finalizer clears and publishes the
same `ERASED` event, so consumers see one tombstone shape regardless of which
obligation expired. The fence differs by obligation: the deletion fence is keyed
to a deletion request row, so expiry records its own fence carrying the retired
profile reference, generation, closure time, and erased binding revision.
Repeated expiry finds an already erased profile, skips, and emits nothing.

## Recorded verification

On 31 October 2026, `./gradlew clean check bootJar --no-daemon --console=plain`
passed with the JDK 21 and Colima settings in [foundation](foundation.md):
114 JVM tests, zero failures/errors/skips, seven contract-suite tests, and
nineteen producer/input checks. JaCoCo reported 98.9% line and 85.0% branch
coverage without exclusions. Contract checks include 70 UMS fixtures and the
pinned IAM artifact validation.

Six PostgreSQL closure tests cover the transition and its published deadline,
the retry with the same authoritative time, a conflicting time, concurrent
closure, closure during a pending deletion, and unavailable profiles. Five
reactivation tests cover the reopen and readiness recomputation, the deadline
boundary, deletion and erasure refusal, absent, de-adopted, and legacy-closure
refusal, and a repeated command. Five expiry tests cover erasure at the deadline
with its fence and evidence, the not-due boundary, an independent deletion
obligation, a reactivated generation, and repeated or unrelated invocations.
Actual persisted event and status JSON are validated against the published
schemas by producer checks, including the absence of display values in every
event.

RED evidence: the closure and reactivation tests first failed to compile because
the services and models did not exist, and the status producer check failed
because the closed status carried a `readiness` property the first status variant
does not allow.

## Remaining gates

IAM-global deletion causes and the real departure, reactivation, and expiry
transports remain outstanding. No scheduler, HTTP route, or production authority
is enabled, and the retention period is a local constant until operations
version it. The next lifecycle work is [work items](work-items.md) W-4, and
scheduling the expiry worker is part of W-15.
