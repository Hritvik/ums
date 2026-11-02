# Slice F3: erasure survives a restore

A backup taken before an erasure contains the data the erasure removed. Restoring it would
resurrect an erased profile unless the fact of the erasure lives somewhere the backup does not
cover. This slice adds that evidence, replays it over a restored database before serving, and
reports whether backups are current.

## Evidence: the erasure journal

Every acknowledged local erasure appends one minimized fact to an append-only JSON-lines file at
`ums.recovery.erasure-journal-path`. The file starts with a fixed header line
(`{"version":1,"journal":"ums-erasure"}`); an empty file or a different first line fails closed.
A torn trailing line from a crash is dropped and truncated before the next append, so a crash
cannot make the journal permanently unreadable:

```json
{"version":1,"feedReference":"…","issuerEnvironmentRef":"…","appReference":"…",
 "subjectReference":"…","retiredProfileReference":"…","retiredGeneration":3,
 "erasedBindingRevision":7,"erasedAt":"2026-11-02T09:00:00Z"}
```

It carries references and a generation, never a display value, so the journal is not a copy of
the data it protects. The entry is written to a feed table inside the erasure transaction
(`ums_erasure_journal_feed`) and appended to the journal by a flusher, which forces the file to
disk before marking the row appended. A failed append is retried every 15 seconds, so an
acknowledged erasure is never silently missing from the evidence.

The journal must live on storage independent of the database backup (separate volume or an
object-store mount). Colocating it with the database defeats the mechanism.

## Reconciliation before serving

`RestoreReconciliationLifecycle` runs in a lifecycle phase below the web server, so
reconciliation finishes and a failure aborts startup before the port opens:

1. Every pending feed row is flushed to the journal (all batches, not just one).
2. Every journal entry after the stored cursor is applied to the current database. The cursor
   (`ums_erasure_reconciliation`) counts journal entries in append order, so it travels with the
   data, a restored backup carries its old cursor and still replays everything that happened
   after it, and no clock or database sequence can reorder it. Repeated boots process only new
   entries. If the journal is shorter than the cursor — a wiped or truncated file — startup
   fails rather than serving the difference. If the binding is already erased,
   nothing happens. If its current generation differs from the retired one, it is a deliberate
   re-onboarding after the erasure and is left alone. Otherwise the binding is erased again
   (fields nulled, lifecycle `ERASED`, profile and generation retired, revision bumped) and an
   erasure obligation is recorded with the original kind and cause so the fence survives, any
   pending deletion request is superseded, causes are cleared, exports invalidated, and notices
   suppressed through the canonical path, which bumps the notice revision and publishes the
   withdrawal fact so a queue holding the pre-erasure notice learns about it. Replay is safe
   without a content-authority re-check because a journal entry only exists for an erasure that
   already passed that guard when it was acknowledged.
3. Counts are logged: entries, erased, already erased, newer generations, missing scopes, late
   appends.

If the journal path is configured but the file is missing, or pending feed rows cannot be
appended, startup fails: serving a restored database without evidence would be exactly the
resurrection the gate forbids. A first-time deployment sets
`ums.recovery.bootstrap-journal=true` once to create the header; after that the flag stays off,
so a missing journal in a restored environment is fatal. Appending to a file whose header is not the journal header is
refused, so a rotated or foreign file cannot silently swallow evidence. Setting
`ums.recovery.allow-missing-evidence=true` is the explicit, logged degraded mode for a
non-privacy environment.

## Backup signals

`BackupMonitor` reads the status file the backup job writes:

```json
{"completedAt":"2026-11-02T03:00:00Z","failed":false}
```

It exports `ums.backup.age.seconds` and `ums.backup.failed`, and logs an error when the last
successful backup is older than 26 hours or when the job reports a failure. A status time in the
future, a malformed `failed` value, a missing file, or an unparseable status all count as
failure, and the over-26-hour case sets `ums.backup.failed` so alerting does not depend on log
parsing alone. Before the first
check both gauges are `NaN`, so "unknown" never reads as "healthy".

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ums.recovery.erasure-journal-path` | unset | enables the journal, flusher and reconciliation; required for privacy mode |
| `ums.recovery.allow-missing-evidence` | `false` | starts in logged degraded mode when the journal is missing |
| `ums.recovery.bootstrap-journal` | `false` | creates the header on a first-ever start; never set it on a restore |
| `ums.recovery.journal-flush.interval` | `PT15S` | retry interval for pending appends |
| `ums.recovery.journal-flush.batch-size` | `100` | appends per pass |
| `ums.recovery.backup-status-file` | unset | enables backup monitoring |
| `ums.recovery.backup-check.interval` | `PT5M` | status polling |

## Verification

`RecoveryTest` covers the journal round-trip and minimisation, refusal of a corrupt line, the
feed being appended and marked, reconciliation of a resurrected generation, preservation of a
newer generation, a missing scope, fail-closed startup with and without the degraded override,
and backup age/failure reporting. `RestoreDrillTest` performs the real drill described in
[restore-drill.md](restore-drill.md). `RecoveryConfigurationTest` covers the conditional beans
and the unknown-then-failed gauge transitions.

## Deliberate limits

- The journal is a local file; independence from the backup depends on the operator placing it on
  separate storage. A managed append-only store would be stronger.
- A crash between the erasure commit and the first journal append leaves the feed row pending; it
  is retried, but if the database is lost inside that window the journal misses that one fact.
- Reconciliation is silent local repair plus a log line; it does not emit a lifecycle event.
- The backup monitor observes a status file; it does not run or verify the backup job.
