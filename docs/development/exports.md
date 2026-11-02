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
| `GET /api/v1/profile/exports/downloads/{token}` | consumes the capability once and returns the artifact |

## Lifetime and invalidation

The artifact expires 24 hours after the request and its payload is dropped at that point;
the row remains only as status. A capability expires after five minutes and is consumed on
first successful download — a retry, replay, or second reader is unavailable. The token is
stored only as a SHA-256 hash, and it never appears in an event.

Any restriction revokes outstanding exports: an app-only deletion request, an IAM-global
pending fact, and every erasure path all mark READY exports INVALIDATED and drop the
payload inside the same transaction. Requesting an export after erasure is unavailable.

## Contract additions

`export-download.json` and `export-artifact.json` are new schemas, and the two download
paths are added to the published OpenAPI with the full safe-error response set. The
artifact contains only UMS-owned display fields, readiness, lifecycle, and revision facts —
no app subject, identity, contact detail, credential, or token.

## Deliberate limits

- **Storage is a database copy.** The artifact is JSONB in the UMS database behind the same
  access controls; there is no object store or encryption-at-rest beyond the database.
- **Sweeping is not scheduled.** `sweep()` exists and is tested, but wiring it to a worker is
  part of the operations slice; until then an expired artifact keeps its row (payload already
  dropped on the first status read or sweep call).
- **No download URL.** The baseline deliberately omits a URL; the capability token is returned
  to the caller and exchanged directly.
- **Not independent recovery.** Revocation is transactional, not an independently durable
  lifecycle journal; that remains the privacy-deployment gate.

## Verification

233 JVM tests, 7 contract tests, 23 producer/input checks, 96.7% line / 80.1% branch.
Service tests cover the closed artifact and idempotent replay, single use, the five-minute
capability boundary, the 24-hour artifact boundary with payload removal, invalidation by
restriction, and weak or stale authority. HTTP tests cover request, status, capability,
one successful download, the refused second download, unknown references, and weak
authority. Producer checks validate the actual persisted artifact and capability against
the published schemas.
