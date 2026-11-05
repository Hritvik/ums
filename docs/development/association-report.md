# Slice C4: the Dutch association report

UMS publishes the correlation input Dutch needs to emit its own
`dutch.member-profile.mapping-changed.v1`. Dutch owns the member and the mapping; UMS only reports
the app-scoped profile state keyed by the same app registration and app subject.

## What is published

One outbox fact of type `ums.profile.association-report.v1` per binding revision that changes
readiness or lifecycle, in the envelope accepted by the Dutch owner:

- envelope: `schemaVersion` (integer), `eventId` (`evt_…`), `eventType`, `producer`, `environment`,
  `occurredAt`, `correlationId`, `causationId`, `aggregate{type: UMS_PROFILE_BINDING, id: appSubject,
  version: bindingRevision}`, `destination{appRegistrationId}`, `payload`.
- payload: `appSubject`, `profileState`, `lifecycleState`, optional `lifecycleReason`,
  `generation` and `profileRevision` for a live generation, `retiredGeneration` for `ERASED`, and a
  boolean `contentObligation`.

No display value, contact detail, token, Human Identity id, or Dutch member id is ever included.
The schema is `contracts/v1/schemas/association-report.json`; a real emitted report and its tombstone
are validated by `contracts/producer-tests/test_emitted_association.py`.

## Trigger and ordering

- Emitted in the same transaction as the profile state change, on its own outbox stream per binding.
- `aggregate.version` is the binding revision and the ordering key; consumers must not infer order
  from timestamps.
- A display-only patch that leaves readiness and lifecycle unchanged is not reported; the emitter
  compares the latest report for the stream.
- A deletion cancellation reports `ACTIVE` with `lifecycleReason: DELETION_CANCELLED` and the same
  generation.
- Erasure reports only the tombstone fields, so a stale `ACTIVE` cannot be mistaken for a new life.

## Dependencies and failure behaviour

- The report requires the IAM app registration and app-subject mapping. A profile without one still
  publishes its state event; the association report is skipped and logged, because no correlation
  key exists.
- At-least-once delivery is the outbox relay's: a duplicate is harmless (same event id), an
  unavailable Dutch consumer leaves the profile unchanged, and a quarantined report is visible and
  repairable.
- A report older than the seven-day outbox retention can be lost; the Dutch side must reconcile by
  pulling the current state when that path is available, or hold the member unreconciled.

## Scope

This repository implements the UMS publisher and validates the shared fixtures. The Dutch reference
consumer lives in the Dutch adapter (`~/repositories/dutch`) and is tracked there; UMS adds no Dutch
placeholder, claim, canonicalization, or Ledger rule.

## Verification

`AssociationReportTest` runs against real PostgreSQL: provisioning, readiness change, a display-only
edit that does not report, deletion request and cancellation, closure, erasure tombstone, and a
profile without a mapping. The emitted report and tombstone are written to
`build/generated-contract-fixtures/` and validated against the published schema.
