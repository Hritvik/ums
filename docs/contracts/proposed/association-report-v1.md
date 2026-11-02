# PROPOSED — not accepted

> This is a proposal for the Dutch owner to accept, amend, or reject. No UMS code may depend on
> it until the Dutch owner accepts a version and both sides validate the same fixtures.
>
> **Review status:** independently reviewed; the first draft was rejected for contradicting the
> accepted Dutch mapping contract and for an incompatible envelope. This revision fixes that.

# Association correlation report v1 (UMS → Dutch), proposal

## What this is, and what it is not

The Dutch W10 contract is accepted and complete: **Dutch publishes**
`dutch.member-profile.mapping-changed.v1` (`appRegistrationId, appSubject, memberId,
mappingVersion, profileState, lifecycleState`) on the pinned IAM envelope. Dutch owns the member,
the mapping, and its version counter. UMS does not publish mappings and never sends `memberId` or
`mappingVersion`.

What UMS can supply is the **correlation input** Dutch needs to emit that mapping fact: the
app-scoped profile state keyed by the same `appRegistrationId` and `appSubject` Dutch already
uses. Today UMS's public profile event carries `state.scope` as UMS UUIDs, which Dutch cannot
correlate to an `aps_` subject. This report closes that gap only.

If the Dutch owner decides the existing UMS event plus an adapter is sufficient, this contract is
withdrawn; that decision is theirs.

## Ownership

| Decision | Owner |
|---|---|
| Member id, mapping lifecycle, claim, Ledger rules | Dutch |
| Profile lifecycle, readiness, revision, erasure | UMS |
| This report's fields and trigger | this contract (joint) |
| What Dutch does with a report | Dutch |

UMS states what it observed. It does not tell Dutch to create, keep, or drop a mapping; the
mapping rules remain Dutch's.

## Envelope and transport

UMS publishes on its outbox; Dutch consumes. The envelope is the platform event envelope, not a
new shape:

```json
{
  "schemaVersion": 1,
  "eventId": "evt_0123456789abcdefghij",
  "eventType": "ums.profile.association-report.v1",
  "producer": "ums",
  "environment": "production",
  "occurredAt": "2026-11-02T09:00:00Z",
  "correlationId": "safe-correlation",
  "causationId": "safe-causation",
  "aggregate": { "type": "UMS_PROFILE_BINDING", "id": "aps_…", "version": 7 },
  "destination": { "appRegistrationId": "dutch-development" },
  "payload": {
    "appSubject": "aps_…",
    "profileState": "PROFILE_READY|PROFILE_REQUIRED",
    "lifecycleState": "ACTIVE|CLOSED|DELETION_PENDING",
    "lifecycleReason": "DELETION_REQUESTED|DELETION_CANCELLED|CLOSED|REOPENED",
    "generation": 3,
    "profileRevision": "5",
    "contentObligation": false
  }
}
```

An erasure report is the tombstone shape, with the live fields absent:

```json
{
  "aggregate": { "type": "UMS_PROFILE_BINDING", "id": "aps_…", "version": 8 },
  "payload": {
    "appSubject": "aps_…",
    "profileState": "PROFILE_REQUIRED",
    "lifecycleState": "ERASED",
    "lifecycleReason": "ERASED",
    "retiredGeneration": 3,
    "contentObligation": false
  }
}
```

- `aggregate.type` is `UMS_PROFILE_BINDING`; `aggregate.id` is the **app subject** (`aps_…`), which
  is stable across generations, so a tombstone remains addressable even though the profile
  reference and generation are retired.
- `aggregate.version` is the binding revision, an integer. It is the ordering key: a report with a
  lower version for the same aggregate is a duplicate or delayed fact, not a new state. Dutch must
  not infer order from timestamps.
- `generation` and `profileRevision` are present only while the binding is not `ERASED`. An `ERASED`
  report carries `retiredGeneration` instead, because the tombstone shape forbids the live fields
  (`contracts/v1/schemas/binding-state.json`). `profileRevision` is a UMS wire string; the envelope's
  `schemaVersion` and `aggregate.version` are integers.
- `lifecycleState` uses the real UMS enum `ACTIVE|CLOSED|DELETION_PENDING|ERASED`; a distinction
  such as "deletion cancelled" is carried in `lifecycleReason`, not invented as a lifecycle value.
- `contentObligation` is a boolean only: it is `true` when the erasure still owes a content-cleanup
  confirmation. It never carries the asset reference, which stays in the UMS obligation record.
- `eventId` uses the platform `evt_` form so the Dutch envelope validator accepts it.
- The outbox also carries legacy-shaped `ums.profile.state-changed.v1` events for existing
  consumers. This report uses the pinned envelope; the two shapes coexist, and only this report is
  validated against the Dutch envelope.

## Trigger

One report per binding-revision change that alters readiness or lifecycle, including a deletion
request, a cancellation, closure, expiry, and every erasure kind. A cancellation is reported as
`lifecycleState: ACTIVE` with `lifecycleReason: DELETION_CANCELLED` — it returns to active
**without** a new generation. A display-only edit does not report.

## Semantics

- At most one report per binding revision; duplicates are identified by `eventId` and are
  harmless, so UMS may deliver the same report more than once.
- An `ERASED` report carries `retiredGeneration`; a later `ACTIVE` for a new generation is a new
  life. Dutch distinguishes the two by `generation` against `retiredGeneration`, never by
  timestamp.
- A report for an unknown `appSubject` is Dutch's to reconcile; UMS never invents a member and
  never re-sends under a different key.
- An unavailable Dutch consumer does not roll back a profile. The report is retried by the UMS
  outbox (bounded backoff, quarantine, repair as documented).

## Failure modes and reconciliation

| Failure | UMS behaviour | Proposed Dutch behaviour |
|---|---|---|
| Dutch unavailable | outbox retries; quarantine after the attempt limit | — |
| duplicate delivery | same `eventId` | discard the second |
| restrictive fact lost to expiry | outbox retains 7 days then removes; see resync | pull the current state (below) |
| report for a retired generation | labelled with that generation | refuse association |
| unknown `appSubject` | nothing else sent | hold unreconciled, visibly |
| member without a profile | nothing sent | hold the member unreconciled |
| profile without a member | report sent | hold the profile unreconciled |

**Resync path (proposed, jointly gated).** A restrictive fact older than the outbox retention
(7 days) can be lost to a consumer that was down. UMS proposes a Dutch-initiated,
owner-authenticated pull of the current association view (bounded cursor, app-scoped, no display
data, explicit app-subject enumeration bound). The internal route is gated on an IAM workload token
that is not published today, so this cannot be switched on alone. Until it exists, the 7-day
horizon is an accepted loss window that the Dutch owner must accept, and Dutch should hold
unreconciled members visibly rather than assume silence means active.

## Source values (Dutch owns the mapping)

UMS supplies readiness, lifecycle, and `lifecycleReason`; it does not define Dutch's
`ACTIVE|SUSPENDED|DEPARTED|PROFILE_PROVISIONING` vocabulary. The table below records what UMS can
send so both sides can agree the mapping, without UMS dictating it.

| UMS readiness / lifecycle / reason | What it means for Dutch to interpret |
|---|---|
| `PROFILE_REQUIRED` / `ACTIVE` | profile exists but is not ready |
| `PROFILE_READY` / `ACTIVE` | ready, no restriction |
| `ACTIVE` with `lifecycleReason: DELETION_CANCELLED` | a request was withdrawn; same generation |
| `CLOSED` | retained, expiry scheduled |
| `DELETION_PENDING` | restriction accepted, deadline scheduled |
| `ERASED` + `retiredGeneration` | irreversible; never associate this generation |
| `contentObligation: true` | a cleanup confirmation is still owed; not full erasure |

## Open questions for the Dutch owner

1. Is the correlation report needed at all, or does Dutch prefer an adapter over the existing UMS
   event?
2. Does Dutch accept the app subject as the stable `aggregate.id`, or does it require an id it
   issues for the binding?
3. Does Dutch accept the 7-day event horizon plus the pull-based resync, or does it require a
   longer replay window?
4. Which lifecycle states must Dutch receive, given it owns member retention?
5. Who owns the shared schema location and its fixtures?

Already decided on the UMS side and not open: `profileRevision` is a wire string while
`schemaVersion` and `aggregate.version` are integers, `appRegistrationId` is the registered app
key, and one stream per app subject matches the binding aggregate.

## Acceptance (when agreed)

- A versioned schema with positive and negative fixtures, held in one named repository and
  validated by both sides.
- A profile that becomes ready produces exactly one report with only the fields above; a scan
  proves no display value, token, `hid_`, or identifier appears anywhere, and `aps_` appears only in
  `payload.appSubject` and `aggregate.id`.
- An unavailable consumer leaves the profile unchanged and the report retryable.
- A duplicate creates no second association.
- An erased profile cannot be associated; a stale `ACTIVE` for a retired generation is refused by
  `generation`/`aggregate.version`, not by timestamps.
- A member without a profile is reported as unreconciled and never auto-created.
- No Dutch placeholder, claim, canonicalization, or Ledger rule appears in UMS.
