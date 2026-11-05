# Content attachment and cleanup

Avatar attachment follows the accepted
[content attachment contract](../contracts/proposed/content-attachment-v1.md) and the central
2026-11-05 decision record (section 2). Attachment stays disabled unless a deployment configures a content authority, and it
remains gated on the contract fixtures both sides still have to validate.

## Configuration

| Property | Meaning |
|---|---|
| `ums.content.authority-uri` | Base URI of the content owner. Unset: attachments are rejected and cleanup stays visibly pending. |
| `ums.content.token` | Optional bearer credential for the content owner. |
| `ums.content.timeout-seconds` | Bounded call timeout, default 5. |
| `ums.content.cleanup.max-attempts` | Retry budget before a debt reads `FAILED`, default 8. |
| `ums.content.cleanup.interval` | Cleanup sweeper cadence, default `PT5M`. |

The scheduler bean exists only when `ums.content.authority-uri` is set.

## Attach

A reference must match `^asset_[A-Za-z0-9_-]{16,240}$`. For a non-null avatar the patch controller
asks the owner's ownership check **before** the profile transaction opens, so the profile row is
never locked across the network. The owner must answer `valid:true`, `state:READY`, and matching
`appRegistrationId`/`appSubject`; anything else rejects the attach. `state:UNAVAILABLE` (including a
timeout or 5xx) returns `503` with `Retry-After`; `REVOKED`, an ownership mismatch, and a malformed
reference return `422`. The patch service also refuses an attachment whose scope differs from the
authority, so a direct caller cannot bypass the check.

An explicit `null` or a replacement records a cleanup request for the previous reference in the
same transaction as the edit.

## Cleanup and repair

`ContentCleanupWorker` sweeps three things, and logs counts only:

1. erasure fences with `content_state = PENDING` (app deletion, closure expiry);
2. explicit clear/replacement requests in `ums_content_cleanup_requests`;
3. attachments the owner now reports `REVOKED` or owned by another scope, which are cleared with
   one canonical `FIELDS_CHANGED` event. `UNAVAILABLE` keeps the association. A mismatch never
   asks the owner to revoke the other scope's asset. The oldest check rotates first and a checked
   association is not queried again for an hour, so one pass cannot starve a later profile; if the
   cleared field is required, readiness drops to `PROFILE_REQUIRED`.

Cleanup is confirmed only by an explicit `{"confirmed": true}` answer. A `2xx` acknowledgement
(`202`/`accepted`) is not confirmation. A failed attempt increments `content_attempts` and reads
`FAILED` once the budget is exhausted; it never silently drops the debt.

The worker returns the number of confirmed cleanups plus repaired associations; the scheduler log
reports that total as actions, never a profile value.

Metrics: `ums.content.cleanup.pending` and `ums.content.cleanup.failed` count both erasure fences
and clear/replacement requests.

## Erasure honesty

App-only deletion and closure expiry erase every local field and keep the dropped reference on the
erasure fence as a provider cleanup debt. The association report carries `contentObligation: true`
only while that debt is `PENDING` or `FAILED`, so a consumer never reads local erasure as full
downstream erasure.

An IAM-global authoritative final is blocked while an association exists: it records
`GLOBAL_FINAL_BLOCKED` evidence and writes the same kind of cleanup fence. A redelivered final
erases once the owner confirms cleanup, or once the retry budget is exhausted (`FAILED`), and keeps
the debt visible either way. A replacement that races the pending transition gets its own fence
before the final can proceed.

## Remaining gates

- The signed-reference wire format (decision 2) is not implemented because the contract does not
  fix its encoding; the ownership-check call is the implemented fallback. Add it when the content
  owner publishes the signature format and fixtures.
- The signed acknowledgement callback (decision 3) is not implemented; polling is.
- The sweeper has no distributed lease and uses a fixed cadence rather than bounded backoff; run
  one active replica (as with the other maintenance workers) until a lease is added. Its repair
  sweep rechecks each association at most hourly.
- Confirmed cleanup rows are retained for audit, not pruned.
