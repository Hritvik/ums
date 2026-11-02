# Slice F2: retention sweeps

Every record the service keeps has a stated lifetime. This slice removes records when that
lifetime elapses, in bounded batches, while keeping erasure fences and the minimal audit that
proves what happened.

## Schedule

`RetentionSchedule` holds the periods in one place; the table below maps each sweep to the
retention table in the plan.

| Sweep | Records | Period | Table |
|---|---|---|---|
| `export_capabilities` | download capabilities | 1 h after expiry | `ums_profile_export_capabilities` |
| `exports` | export status rows | 24 h, kept while a receipt needs it | `ums_profile_exports` |
| `export_receipts` | export command identity | 7 days | `ums_profile_export_receipts` |
| `patch_receipts` | profile-edit command identity | 7 days | `ums_profile_patch_receipts` |
| `privacy_receipts` | deletion-command identity | 7 days | `ums_profile_privacy_receipts` |
| `published_outbox` | published outbox facts (with payload) | 7 days after publication | `ums_event_outbox` |
| `processed_inbox` | processed event dedupe rows | 7 days after processing | `ums_event_inbox` |
| `privacy_audit` | minimal command audit | 365 days | `ums_profile_privacy_audit` |
| `lifecycle_evidence` | closure/retention evidence | 365 days | `ums_profile_lifecycle_evidence` |
| `onboarding_receipts` | IAM onboarding replay fence | retained, never swept | `ums_iam_onboarding_receipts` |
| `erasure_fences` | erasure obligations | retained, never swept | `ums_profile_erasure_obligations` |

Never swept by design: erasure and expiry fences, open ingestion quarantine, deletion requests
and their causes, profile bindings, and notice intents. A fence is safety metadata, not
operational history; deleting it because a table's lifetime elapsed would resurrect erased
profiles.

## Behaviour

- Each class selects a bounded batch (`ums.retention.sweep.batch-size`, default 100) of rows
  whose timestamp is older than the period, oldest first.
- Each row is deleted in its own transaction. A failing row is counted as failed and the sweep
  continues with the rest, so one bad record cannot stop the pass.
- Counts are reported per class and in total: swept, retained, failed. `retained` covers records
  that are older than the period but still needed: an export status row whose replay receipt is
  still live, the onboarding replay fence, and the erasure fences.
- Published outbox rows are only removed once published; unpublished and quarantined facts are
  never swept. Processed inbox rows are removed only after their dedupe window; the binding's
  revision check and erasure fences still reject stale traffic.
- The sweep runs hourly by default (`ums.retention.sweep.enabled`, interval, initial delay) and
  logs counts only — no identifier, subject, reference, or payload value.

## Configuration

| Property | Default |
|---|---|
| `ums.retention.sweep.enabled` | `true` |
| `ums.retention.sweep.interval` | `PT1H` |
| `ums.retention.sweep.initial-delay` | `PT5M` |
| `ums.retention.sweep.batch-size` | `100` |

## Verification

`RetentionSweeperTest` runs against real PostgreSQL and asserts both sides of each boundary:
receipts at 8 days are removed and 6 days kept; published outbox and processed inbox rows at
8 days are removed while unpublished and quarantined facts survive; an export row at two days
is removed unless a live receipt still needs it, and recent exports and artifacts are untouched;
lifecycle evidence at 400 days is removed and at 100 days kept; onboarding receipts and erasure
obligations are reported as retained and never appear in a delete rule; a simulated per-row
failure leaves that row and removes the rest; the batch limit is respected; and the scheduler
runs the sweep.

## Deliberate limits

- The sweep is time-based, not clock-injection across replicas; each instance sweeps the same
  rows, and deletes are idempotent.
- Retention periods are compiled constants, matching the plan's operating defaults. Changing one
  is a recorded privacy decision, not a configuration change.
- `pending` ingestion quarantine and notice intents have no sweep yet; they are owned by an
  operator until the plan assigns them a period.
