# Operating tier

This is a best-effort, single-operator tier. The design does not pretend otherwise, and the
limits below are accepted rather than hidden.

## What runs on its own

| Work | Cadence | Guard |
|---|---|---|
| Due-deletion finalization | every 5 min | worker lease; batches of 50, up to 20 pages per app |
| Retention notices | every 5 min | one intent per class and closure instance |
| Outbox relay | every 30 s | claim lease per fact |
| Export sweep | every 15 min | transactional |
| Retention sweeps | hourly | bounded batches, per-row transactions |
| Backup check and alerts | every 5 min | status file, 26-hour threshold |
| Restore reconciliation | at startup, before the port opens | journal and cursor |

Two workers running the same job do not duplicate transitions: the database lease makes a pass
single-flight, and every transition underneath is idempotent (finalization fence, notice dedupe,
outbox claim, delete-by-key).

## What is not promised

- No availability percentage, multi-zone promise, or guaranteed RPO/RTO.
- No 24/7 rotation or independent approver; one operator is the accepted limitation.
- No managed durability. Backups are encrypted at rest by the operator's storage, restricted, and
  rolled over for seven days; the service only reports age and failure.
- Alert delivery is the operator's monitoring stack reading the `ums.*` gauges and logs; this
  repository does not ship a pager integration.
- A consumer more than seven days behind cannot be gap-repaired from the outbox.

## Before calling a deployment ready

1. Restore drill completed and recorded, with the journal on storage independent of the backup.
2. Retention sweeps and the maintenance worker enabled and observed for a full cycle.
3. Every gauge in [runbooks.md](runbooks.md) wired to an alert with the stated threshold.
4. The runbooks walked through by a second person.
5. Actual cost, backup capability, restore result, and monitoring coverage recorded.
