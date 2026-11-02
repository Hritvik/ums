# Restore drill record and runbook

A backup taken before an erasure was restored, reconciled, and verified to serve no display data.
This is the evidence the privacy-capable deployment gate asks for, recorded from
`RestoreDrillTest` on 2026-11-02.

## Recorded drill

| Item | Result |
|---|---|
| Backup age at restore | 44 ms (the drill dumps and restores within one test; production age comes from the backup monitor) |
| Lost changes | 1 — the erasure performed after the backup |
| Restored fences, before reconciliation | 0 |
| Restored fences, after reconciliation | 1 (`APP_DELETION` obligation for the retired generation) |
| Migration result | 23 (restored history matches the deployed schema) |
| Checkpoint convergence | 1 checkpoint restored, unchanged by reconciliation |
| Elapsed recovery | 314 ms from restore start to verified erased state |
| Journal entries replayed | 1 |

Post-conditions verified in the restored copy: `lifecycle = ERASED`, display name and all display
fields null, `profile_reference` and `generation` retired, and the pre-reconciliation state was
`ACTIVE` with the display name present — the resurrection the drill exists to catch. The restored
copy also contained no journal feed row, so the erase came only from the independent journal.

These are local, disposable-container numbers. They prove the mechanism and the invariant, not a
production recovery time.

## Running the drill

Automated:

```
./gradlew test --tests com.vik.ums.recovery.RestoreDrillTest --info
```

The test prints one `RESTORE-DRILL` line with the numbers above.

## Runbook (operator, production-shaped)

1. **Stop the service.** Do not serve a restored database before reconciliation.
2. **Note the incident start and the backup timestamp** used for the restore.
3. **Provision an isolated database** from the backup with the normal restore tooling:
   ```
   psql -d ums_restored -f ums-backup.sql         # or pg_restore for a custom-format dump
   ```
4. **Mount the erasure journal** at `ums.recovery.erasure-journal-path`. It must be the journal
   from the same or a later time than the backup, never an older copy.
5. **Start the service** with the restored database and the journal configured. The startup
   reconciliation flushes pending appends, replays every journal entry, and logs
   `Restore reconciliation: N entries, E erased, A already erased, U newer generations,
   M missing, L late appends`.
6. **Verify** that `E` matches the erasures after the backup timestamp and that no profile that
   was erased after the backup serves display data. Move the service into rotation only after
   this check.
7. **Record** backup age, lost changes, restored fences, migration result, checkpoint
   convergence, and elapsed recovery in the incident.
8. If the journal is missing, do **not** set `allow-missing-evidence` in production. Restore the
   journal first; failing startup is the designed safe outcome.

## Expected failure modes

- **Journal older than the backup**: erasures recorded after the journal copy are not replayed.
  Treat the backup as unverifiable for privacy and restore a newer journal before serving.
- **Scope missing from the restored database**: counted as `missing` and logged. It means the
  profile was not in the backup (already gone or never present); no data can be served for it.
- **Newer generation**: a profile re-onboarded after the erasure keeps its new life; the
  journal entry applies only to the retired generation.
- **Backup older than 26 hours**: `ums.backup.age.seconds` and an error log raise it; the
  restore can still proceed, but the loss window is at least that old.
