# Runbooks

Operator procedures for the failure scenarios the design names. Alert content is counts and
failure classes only: no profile value, reference, token, or download link appears in a log,
gauge, or page.

## Signals and thresholds

| Signal | Source | Threshold |
|---|---|---|
| `ums.alert.overdue.erasures` | pending requests with `deadline <= now` | any |
| `ums.alert.overdue.beyond.objective` | pending requests more than 24 h past deadline | any |
| `ums.alert.poisoned.outbox` | quarantined outbox facts | any |
| `ums.alert.ingestion.quarantine.open` | open ingestion quarantine | any |
| `ums.alert.failed.retention` | failed deletes in the last sweep | any |
| `ums.alert.backup.failed` | failed backup or last success older than 26 h | any |
| `ums.alert.authorization.failures` | caller denials | 5 since the previous check |
| `ums.outbox.*`, `ums.retention.*`, `ums.backup.*` | delivery, sweep, backup state | see each doc |

## Overdue erasure

1. Read `ums.alert.overdue.erasures` and the maintenance-worker log; both carry counts only.
2. Check the maintenance worker is enabled (`ums.maintenance.enabled`) and not blocked by its
   lease (`ums_worker_lease` row older than 10 minutes clears itself).
3. If the worker runs, inspect `ums_profile_deletion_requests` for `PENDING` rows with an old
   deadline and their `failure_class` evidence; a content-authority block is expected until the
   content owner confirms cleanup.
4. If nothing moves, run the batch once with a small limit and read the outcomes
   (`LOCAL_COMPLETED`, `BLOCKED`, `RETRYABLE`). Do not hand-edit lifecycle rows.

## Poison event

1. The outbox row is quarantined as `TRANSPORT_REJECTED` or `RELAY_INTERRUPTED`; the stream is
   held so later revisions do not overtake it.
2. Confirm the consumer endpoint and payload schema, fix the cause, then re-drive exactly one
   fact with `UMS_OUTBOX_REPAIR_EVENT_ID=<event id>`.
3. Never edit or delete a quarantined row to "unblock" the stream.

## Stuck provisioning

1. Look for profiles with readiness `PROFILE_REQUIRED` and an old `binding_revision`.
2. Check the IAM ingestion quarantine and the input checkpoints; a gap pauses permissive effects
   rather than guessing.
3. Request an owner-authenticated snapshot repair from IAM; never reconstruct order from
   timestamps.

## Event-gap repair

1. Use the owner-authenticated snapshot: current app-scoped authority, revisions, lifecycle,
   causes, per-stream checkpoints.
2. Persist the checkpoint and repaired state atomically; never import another database's events.
3. A snapshot cannot remove a newer tombstone or an erasure fence.

## Failed notice

1. Read the intent state (`FAILED`) and failure class; the notice owner, not UMS, owns delivery.
2. Re-drive with the notice API after the cause is fixed; do not delete the intent.
3. A notice for an erased profile is suppressed and a withdrawal fact published; if a downstream
   queue already holds it, the withdrawal is the correction.

## Database or backup loss

1. Follow [the restore drill](../development/restore-drill.md): stop serving, restore into an
   isolated database, mount the erasure journal, start, verify the reconciliation counts.
2. Never set `ums.recovery.allow-missing-evidence` in production; a missing journal is a stop.
3. Record backup age, lost changes, restored fences, migration result, checkpoint convergence,
   and elapsed recovery.

## Profile data exposure

1. Treat the journal, backups, and logs as suspect; rotate credentials and keys.
2. Identify affected scopes from audit and fences only — never by dumping profile fields.
3. Erase or restrict through the normal lifecycle paths; restore only after the exposure is
   contained.

## Compromised workload

1. Revoke the workload credential at IAM and stop accepting its tokens; the caller resolver
   rejects a token whose registration or audience no longer matches.
2. Watch `ums.alert.authorization.failures` for a burst; it counts denials only.
3. Re-issue credentials and confirm no profile change came from the compromised identity.

## Content revocation

1. A revoked asset invalidates the association and increments the profile revision; the neutral
   avatar applies until a valid reference exists.
2. Erasure with an outstanding provider obligation is blocked and reports the obligation; finish
   cleanup, then re-run the finalizer.

## Safe restore

See [the restore drill](../development/restore-drill.md). The short form: stop, restore isolated,
mount the journal, start, verify counts, then rotate in. Application rollback is not a restore:
it cannot restore erased fields or reverse lifecycle and security versions.
