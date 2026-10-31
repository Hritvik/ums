# Slice D1 plan: app-only deletion request and cancellation

Status: selected by the user on 24 October 2026; bounded local implementation
and verification complete. See [the implementation report](profile-deletion.md)
for evidence and outstanding release gates.

## Scope

Add a constructor-only privacy application service for requesting app-only
profile deletion, cancelling the named request, and reading restricted request
status. Business HTTP routes remain denied. No finalizer, erasure, scheduler,
IAM lifecycle consumer, or production privacy acceptance is included.

The deadline is authoritative acceptance time plus 30 elapsed days. Profile
reads, edits, and projections stop immediately on acceptance. Cancellation is
allowed strictly before the deadline; delayed workers never extend that window.
Until a later finalizer exists, an expired request remains restricted and must
not be reported as erased or locally completed.

## Required invariants

- Use a purpose-bound local privacy authority with current recent-Strong
  evidence; this is a trusted seam, not a substitute IAM authentication claim.
- Privacy expectedVersion refers to bindingRevision. Editing expectedVersion
  continues to refer to profileRevision.
- Separate app-only and IAM-global deletion causes and preserve closure
  independently. Cancellation removes only its named app-only cause; it never
  blindly writes ACTIVE or restores product permissions.
- Scope idempotency by actor, app, subject, and operation. Exact retries preserve
  the original request/deadline and recheck current authority before replay.
- Persist request/cause, safe receipt, binding revision, minimized audit, and
  canonical state outbox fact in one transaction using existing lock order.
- Emit DELETION_REQUESTED/DELETION_CANCELLED on the existing binding stream.
  Do not reinterpret operation completion or downstream acknowledgement.
- Replace the placeholder deletionCauses model with typed, faithfully persisted
  causes. Expose request/deadline/eligibility through a minimized privacy-status
  DTO without reopening normal personal-field access.

## Verification

Drive the service with tests first against disposable PostgreSQL. Cover exact
retry and changed-input conflict, authority/scope failure, request/edit and
cancellation races, deadline minus/at/after boundary, preservation of CLOSED and
IAM-global restrictions, onboarding non-reactivation, transaction rollback,
restricted display with accessible privacy status, and minimized event/receipt
content. Validate actual emitted state and privacy-status JSON against schemas.

IAM-global/closure fixtures provide restriction evidence for local invariants;
they do not fabricate an authenticated upstream lifecycle adapter. Independent
privacy recovery, retention enforcement, authenticated production authority,
finalization, and real consumer cleanup remain release gates.

## Sequencing decision

The user selected continued UMS lifecycle work. Real IAM/Dutch integration
remains gated independently; this slice does not change repository ownership
or claim Slice C complete.
