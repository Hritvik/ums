# PROPOSED — not accepted

> This is a proposal for the content owner to accept, amend, or reject. No UMS code may depend on
> it until the owner accepts a version. Avatar attachment stays disabled until then; UMS continues
> to support no-avatar profiles.

# Content attachment contract v1 (content owner → UMS), proposal

## Why

An avatar is currently a stored reference because no content provider has agreed how attachments
are created, revoked, or cleaned up. Until this contract is accepted, a person cannot attach one
and erasure cannot honestly claim the image is gone.

## Ownership

| Decision | Owner |
|---|---|
| Asset storage, bytes, formats, scanning, CDN | content owner |
| Reference format and its lifecycle | content owner |
| Whether a subject may attach a given asset | content owner |
| When an association is cleared and cleanup confirmed | this contract |
| Profile field and revision semantics | UMS |

## Reference

A reference is an opaque, single-use, app-and-subject-bound token, for example
`asset_ref` with a fixed prefix and a bounded length. It is never a URL, never contains an email,
name, or subject, and UMS never fetches it. UMS stores the token as display data, exactly like the
current `avatarReference` field.

## Attach

UMS accepts an attach only through a validated reference:

1. The caller supplies `avatarReference` in the profile patch with the current
   `expectedVersion`.
2. UMS validates the reference against the content owner's **ownership check** before storing it
   (see below). A reference that fails is rejected; the profile is unchanged.
3. UMS stores the reference, increments the profile revision, and reads back the display.

## Ownership check (proposal)

A read-only call from UMS to the content owner, with the reference, app, and app subject:

```json
{ "reference": "asset_…", "appRegistrationId": "dutch-development", "appSubject": "aps_…" }
```

Response: `{"valid": true|false, "state": "READY|UNAVAILABLE|REVOKED", "ownerAppSubject": "aps_…"}`.
UMS accepts only `valid:true` with `state:READY` and a matching owner. The check is bounded (short
timeout, no retries inside the profile transaction) and never follows a supplied URL.

## Revoke and clear

- An explicit `avatarReference: null` patch clears the association and increments the revision.
- Revoking or invalidating the reference at the content owner invalidates the association: UMS
  clears it on the next validated read or lifecycle fact, and uses the neutral avatar meanwhile.
- UMS never revokes an asset owned by another subject. A reference that fails the ownership check
  is dropped, not revoked.

## Erasure and cleanup obligations

- Local erasure clears every display field, including the association, in the same transaction as
  the rest of the erasure.
- If the content owner has not confirmed cleanup, the erasure is **blocked** and reports an
  outstanding provider obligation; UMS does not claim the image was deleted.
- The obligation is tracked separately from local erasure, is visible to operators, and is
  finishable once cleanup is confirmed.
- Failed cleanup is retryable and visible; a completion path exists for a global finalization that
  arrived while an association existed.

## Failure modes

| Failure | UMS behaviour |
|---|---|
| ownership check unavailable | attach rejected with a safe error; profile unchanged |
| reference invalid or revoked | association cleared, neutral avatar |
| cleanup not confirmed | erasure blocked, obligation reported |
| cleanup call fails | retryable, visible, never silently dropped |
| asset owned by another subject | never revoked by UMS |

## Open questions for the content owner

1. What is the exact reference format and length bound?
2. Is the ownership check a request/response call, a signed reference, or a lifecycle fact?
3. What is the cleanup-confirmation mechanism (callback, poll, signed acknowledgement)?
4. How long after revocation must UMS keep the association before cleanup can be confirmed?
5. Which content states map to `UNAVAILABLE` for the neutral avatar?

## Acceptance (when agreed)

- A versioned contract with fixtures both sides validate.
- An attachment is stored only after the reference passes the ownership check; an explicit clear
  removes it.
- Erasure with an outstanding obligation reports the obligation while local fields are erased.
- Cleanup failures are retryable and visible; a reference owned by another scope is never revoked.
- Automated tests cover attach validation, clearing, erasure with and without an obligation, and
  the retry path.
