# PROPOSED — not accepted

> This is a proposal for the Dutch owner to accept, amend, or reject. No UMS code may depend on
> it until the Dutch owner accepts a version and both sides validate the same fixtures. It is the
> first bullet of work item W-8, not an implementation.

# Association report v1 (UMS → Dutch), proposal

## Why

Dutch owns its Member record, placeholder, claim, and Ledger rules. UMS owns the profile. Dutch
needs to know which UMS profile belongs to which member; today it has no contract for that and
infers nothing. The retired `ums.member.mapping.changed.v1` event is gone and is not revived here.

## Ownership

| Decision | Owner |
|---|---|
| Member id, mapping lifecycle, claim and Ledger rules | Dutch |
| Profile lifecycle, readiness, revision, erasure | UMS |
| When a report is sent and what it contains | this contract |
| What Dutch does with a report (create, link, ignore, reconcile) | Dutch |

## Direction and transport

UMS publishes one report per profile-readiness transition on the existing outbox, on a stream per
app subject. Dutch consumes it. UMS never calls Dutch synchronously and never reads Dutch data.

## Payload (proposal)

```json
{
  "schemaVersion": "1",
  "eventId": "uuid",
  "type": "ums.profile.association-reported.v1",
  "producer": "ums",
  "environment": "production|staging|test|development",
  "occurredAt": "2026-11-02T09:00:00Z",
  "correlationId": "safe-correlation",
  "appRegistrationId": "dutch-development",
  "appSubject": "aps_…",
  "profileState": "PROFILE_READY|PROFILE_REQUIRED",
  "lifecycleState": "ACTIVE|CLOSED|DELETION_PENDING|ERASED",
  "profileRevision": "7"
}
```

Only the fields above. No display name, locale, timezone, avatar, Human Identity id, email, phone,
credential, or token. `appSubject` is Dutch's own subject reference; UMS does not send its internal
subject UUID and Dutch does not need it.

## Semantics

- Exactly one report per readiness transition, in binding-revision order. A duplicate is harmless
  and is identified by `eventId`.
- `ERASED` and `DELETION_PENDING` are restrictive facts: Dutch must not associate or keep a live
  mapping for them. A later `ACTIVE` for a **new generation** may associate again; a stale
  `ACTIVE` for a retired generation must not.
- A report for an unknown `appSubject` is Dutch's problem to reconcile; UMS never auto-creates a
  member and never retries with a different key.
- An unavailable Dutch consumer does not roll back a profile. The report stays retryable in the
  UMS outbox and is delivered at least once.
- A member without a profile and a profile without a member are both left unreconciled and visible;
  neither side invents the other.

## Failure modes and reconciliation

| Failure | UMS behaviour | Dutch behaviour |
|---|---|---|
| Dutch endpoint down | outbox retries with backoff | — |
| duplicate delivery | same `eventId`, harmless | discard second |
| erased profile reported | report still sent as `ERASED` | drop mapping |
| report for retired generation | not sent again after erasure | refuse association |
| member exists, no profile | nothing sent | keep member unreconciled |
| profile exists, no member | report sent | record unreconciled |

## Open questions for the Dutch owner

1. Is `appRegistrationId` the right key, or should the report use an app reference UMS already
   holds?
2. Should `profileRevision` be a string (current UMS version wire form) or a number?
3. Does Dutch need a separate `mappingVersion` acknowledgement back to UMS, or is at-least-once
   enough?
4. Is one stream per app subject acceptable, or does Dutch require one stream per member?
5. Which lifecycle states must Dutch receive at all, given it owns Member retention?

## Acceptance (when agreed)

- A versioned schema with positive and negative fixtures, validated by both sides from one source.
- A profile that becomes ready produces exactly one report with only the fields above.
- An unavailable consumer leaves the profile unchanged and the report retryable.
- A duplicate creates no second association.
- An erased profile cannot be associated; a member without a profile stays unreconciled.
- No Dutch placeholder, claim, canonicalization, or Ledger rule appears in UMS.
