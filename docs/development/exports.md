# Slice E2: scoped export with single-use download

The export service and its routes are implemented and verified. An export is a second
copy of UMS-owned personal data, so it expires, is revoked by restriction, and is only
reachable through a short-lived single-use capability.

## Routes

| Route | Behaviour |
|---|---|
| `POST /api/v1/profile/exports` | recent-Strong authority; creates a READY artifact and answers `202` |
| `GET /api/v1/profile/exports?operationReference=…` | own operation status (`LOCAL_COMPLETED`, `EXPIRED`, `FAILED`) |
| `POST /api/v1/profile/exports/{id}/downloads` | issues a five-minute, single-use capability (`201`) |
| `POST /api/v1/profile/exports/downloads` | consumes the capability from the JSON body once and returns the artifact |

## Lifetime and invalidation

The artifact expires 24 hours after the request and its payload is dropped at that point;
the row remains only as status. A scheduled sweep (`ProfileExportSweepScheduler`, every 15
minutes by default and configurable through `ums.privacy.export-sweep.*`) does this even when
nobody polls the export, and it deletes expired capability rows.

A capability expires after five minutes and is consumed on first successful download — a
retry, replay, or second reader is unavailable. Only one live capability exists per export and
the database enforces it (partial unique index in `V17`); a new request replaces the previous
token, so a lost response can be recovered and "single use" cannot be traded for many
redeemable tokens. The token is stored only as a
SHA-256 hash, it travels in a request body rather than a URL, and it never appears in an event.
The caller's own app subject must still match the export: a capability stolen by another
subject is unavailable.

Any restriction revokes outstanding exports: an app-only deletion request, an IAM-global
pending fact, app closure, and every erasure path all mark READY exports INVALIDATED and drop
the payload inside the same transaction. Requesting an export after erasure is unavailable.
The export is built under the same registered-policy row lock as edits, deletion, closure and
expiry, so it can never be assembled from a binding those paths are replacing.

## Contract additions

`export-download.json`, `export-download-request.json`, and `export-artifact.json` are new
schemas; the capability and download paths are in the published OpenAPI with their path and
body parameters and the full safe-error response set. The
artifact contains only UMS-owned display fields, readiness, lifecycle, and revision facts —
no app subject, identity, contact detail, credential, or token.

## Deliberate limits

- **Storage is a database copy.** The artifact is JSONB in the UMS database behind the same
  access controls; there is no object store or encryption-at-rest beyond the database.
- **Sweeping is in-process.** The sweep runs on the service's own schedule; alerting on a
  stalled sweep belongs to the operations slice.
- **No download URL.** The baseline deliberately omits a URL; the capability token is returned
  to the caller and exchanged for the artifact in a POST body.
- **Not independent recovery.** Revocation is transactional, not an independently durable
  lifecycle journal; that remains the privacy-deployment gate.

## Verification

250 JVM tests, 7 contract tests, 23 producer/input checks, 96.6% line / 80.7% branch.
Service tests cover the closed artifact and idempotent replay, single use including two
concurrent redemptions, one live capability including two concurrent requests, cross-subject
redemption, the five-minute capability boundary, the 24-hour artifact boundary with payload
removal and the unattended sweep, invalidation by app-only restriction, finalization,
IAM-global pending, closure and expiry, and weak or stale authority. HTTP tests cover request, status, capability,
one successful download, the refused second download, unknown references, and weak
authority. Producer checks validate the actual persisted artifact and capability against
the published schemas.
