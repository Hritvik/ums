# Upgrade and rollback

The service is upgraded while it holds real data, including erased profiles and open deletion
requests. The forward path is rehearsed; the rollback path is explicitly limited.

## Forward upgrade

1. Take a backup and confirm the backup monitor reports success.
2. Stop the old instance and start the new one. Flyway applies pending migrations before the web
   server opens; each migration is transactional on PostgreSQL, and the restore-reconciliation
   lifecycle runs after migration and before the port opens.
3. Verify `flyway_schema_history` shows the new version with no failed row, and
   `ums.retention.last.completed.seconds`, `ums.backup.age.seconds`, and the alert gauges are
   healthy within one cycle.
4. Watch the maintenance worker and outbox for the first pass; a delayed pass alerts rather than
   silently missing its objective.

`SchemaUpgradeTest` proves every earlier schema state (1 through 22) upgrades to the current one
and validates, and that a database carrying an erased tombstone, its fence, and a pending deletion
request keeps all three across the upgrade.

## Interrupted migration

A migration that fails is not recorded as applied. On PostgreSQL the statement transaction rolls
back, so the schema stays at the last successful version, `flyway_schema_history` has no success
row for the failed version, and the service refuses to start until the upgrade is complete.

- Detect: startup fails with a Flyway error, and `flyway.info().pending()` lists the version.
- Resume: fix the cause (disk, lock, permissions) and start again; Flyway applies the pending
  version. If the database was manually altered, `flyway repair` realigns the history before
  retrying.
- `SchemaUpgradeTest` covers the interrupted-then-resumed path with a deliberately broken
  migration.

## Rollback

Rolling the application back is **not** a data rollback:

- It cannot restore erased fields. Erasure is irreversible and the fence keeps it that way.
- It cannot cancel an acknowledged erasure or reopen a retired generation.
- It cannot move a lifecycle or security version backwards; older code must tolerate the newer
  rows or stay stopped.
- It cannot undo a published fact. Consumers may have applied it; a rollback needs a new forward
  version, not a rewind.

For data recovery after a bad upgrade, use [the restore drill](../development/restore-drill.md):
restore the pre-upgrade backup into an isolated database, mount the erasure journal, start the
new version against it, and verify the reconciliation counts. The journal, not the rollback,
keeps erased profiles erased.

## Rehearsal record

The restore drill runs the upgrade against the restored copy before reconciliation and records the
migration result; see `docs/development/restore-drill.md`. Both are required before a deployment is
called ready.
