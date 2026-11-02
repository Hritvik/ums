# PROPOSED — not accepted

> This is a proposal for the content owner to accept, amend, or reject. No UMS code may depend on
> it until the owner accepts a version. Avatar attachment stays disabled until then.
>
> **Review status:** independently reviewed; the first draft used a single-use reference, allowed a
> network call inside the profile transaction, and contradicted itself on erasure. This revision
> fixes those.

# Content attachment contract v1 (content owner → UMS), proposal

## Ownership

| Decision | Owner |
|---|---|
| Asset bytes, storage, formats, scanning, CDN | content owner |
| Reference format and its lifecycle | content owner |
| Whether a subject may attach a given asset | content owner |
| Cleanup confirmation for a cleared or erased asset | content owner |
| Profile field, revision, and erasure semantics | UMS |

## Reference

An opaque, immutable, app-and-subject-bound token: `asset_` plus a bounded alphabet and length
(the existing `avatarReference` field allows at most 256 characters of `[A-Za-z0-9_-]`). It is
**not** single-use — UMS revalidates the same reference on patch replay and after a transient
provider failure. It is never a URL, never contains a name, email, or subject, and UMS **never
fetches the bytes**. UMS stores it as display data (`contracts/v1/schemas/profile-patch.json`).

## Attach

1. The caller supplies `avatarReference` with the current `expectedVersion`.
2. **No network call inside the profile transaction.** UMS validates the reference *before*
   opening the profile transaction (or through a signed reference that needs no call), then
   re-checks only the expected version inside. This follows the accepted integration rule: prepare
   outside, re-check versions inside.
3. If validation is a call, it is bounded and outside the lock: a timeout or provider fault rejects
   the attach with a safe error and leaves the profile unchanged; it never holds the row lock.

## Ownership check

Request: `{reference, appRegistrationId, appSubject}`. Response:
`{valid, state, appRegistrationId, appSubject}` where `state` is `READY|UNAVAILABLE|REVOKED`.

| Response | UMS action |
|---|---|
| `valid:true`, `state:READY`, both keys match | accept and store |
| `valid:false` or `state:REVOKED` | reject a new attach; for an existing association, clear it and show the neutral avatar |
| `state:UNAVAILABLE` | reject a new attach; for an existing association, keep it and display the neutral avatar |
| keys do not match | reject a new attach; for an existing association, clear it and **never revoke the asset** |
| timeout / 5xx | reject a new attach with a safe error; keep an existing association |

`valid` and `state` are a matrix, not two independent flags; `UNAVAILABLE` must never be treated as
`REVOKED`.

## Revoke and clear

- An explicit `avatarReference: null` patch clears the association, increments the revision once,
  and **requests cleanup** of the asset (or the provider states it garbage-collects unreferenced
  assets; that must be an explicit answer, not silence).
- Provider revocation (`REVOKED`) and an ownership mismatch clear the association and use the
  neutral avatar; on a mismatch UMS drops its pointer and never revokes the other scope's asset.
- A transient `UNAVAILABLE` does not clear anything.
- A reference revoked between the check and the commit is repaired by the next validated read or
  content lifecycle fact; it is not retroactively rejected and does not bump the revision twice.
- UMS never revokes an asset owned by another app or subject.

## Erasure and cleanup obligations

- An app-only or expiry erasure always proceeds locally: every display field is erased in the
  erasure transaction, the reference is captured in the obligation first, and the profile becomes
  `ERASED`. An IAM-global authoritative final that arrives while an association exists is recorded
  as blocked evidence instead, matching the current guard; it is finishable once cleanup is
  confirmed. UMS never claims the image was deleted in either case.
- Before clearing the association, UMS stores the **reference itself** in a dedicated,
  access-controlled obligation record, because a hash cannot be used to confirm cleanup unless the
  provider explicitly accepts one. The obligation is the cleanup handle; clearing the field first
  would lose it. The record lives with the erasure fence (never swept) and is deleted only after the
  provider confirms cleanup.
- Reads never call the provider: the display uses the stored association and a cached status, so a
  provider outage cannot block a profile read.
- The obligation is tracked separately from local erasure, visible to operators, retryable on
  failure, and finishable once the provider confirms cleanup.
- The association report carries `contentObligation: true` as a boolean when cleanup is still
  owed, so a consumer does not read the erasure as full downstream erasure. The reference stays in
  the UMS obligation record, never in the report.
- A blocked authoritative final (global finalization while an association exists) records blocked
  evidence and is finishable once cleanup is confirmed; it is never silently dropped.
- A generic time-out source exists: if the provider never confirms, the obligation stays visible
  and ages; it does not block local erasure or retention forever.

## Failure modes

| Failure | UMS behaviour |
|---|---|
| ownership check unavailable | reject a new attach; keep an existing association; neutral avatar |
| reference invalid or revoked | clear the association; neutral avatar |
| cleanup not confirmed | obligation visible, retryable; local erasure already done |
| cleanup call fails | retry with bounded backoff; never silently dropped |
| asset owned by another app/subject | drop the reference; never revoke |
| revoke during patch | attach succeeds; later fact/read repairs it |

## Open questions for the content owner

1. What is the exact reference format and length bound?
2. Is a signed reference possible, or must UMS call the ownership check? (UMS recommends a signed
   reference so no network sits in the profile path.)
3. What is the cleanup-confirmation mechanism: callback, poll, or signed acknowledgement? Is
   cleanup required for an explicit clear, or does the provider garbage-collect unreferenced
   assets?
4. After a reference is revoked, how long is UMS expected to keep the association before the
   neutral avatar applies?
5. Which provider states map to `UNAVAILABLE` versus `REVOKED`?
6. Does the provider accept a keyed hash as the cleanup handle, or must UMS retain the reference?

## Acceptance (when agreed)

- A versioned contract with fixtures both sides validate, in a named repository.
- An attachment is stored only after the reference passes validation; an explicit clear removes the
  association and requests cleanup.
- Ownership-unavailable, revoked, cross-app, and revoke-during-patch cases behave as the matrix
  says.
- Erasure with and without an outstanding obligation: local fields are always erased, the
  obligation is always reported, and the completion path works after cleanup is confirmed.
- Cleanup failures are retryable and visible; a pre-existing or failed-cleanup reference has a
  repair path.
- A blocked global final is recordable and finishable once cleanup is confirmed.
- A profile read during a provider outage serves the cached status and neutral avatar without
  calling the provider.
