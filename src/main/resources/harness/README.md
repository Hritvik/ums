# Synthetic Dutch consumer harness

A disposable topology that proves the UMS side of the Dutch consumer binding without a real IAM
issuer, a Dutch environment, or any credential. It is the executable half of
[the Dutch consumer journey doc](../../../../docs/development/dutch-consumer-journey.md).

## Topology

| Component | Version | Store | Purpose |
|---|---|---|---|
| `ums` | `build/libs/ums.jar`, local commit | PostgreSQL 17.6, volume `ums-db-data` | the real application, profile `dutch` |
| `ums-db` | `postgres:17.6-alpine` | own volume | UMS schema and migrations |
| `dutch-stub` | `python:3.12-alpine` | SQLite file, volume `dutch-data` | separate consumer inbox |
| `iam-stub` | `python:3.12-alpine` | in memory | producer endpoint placeholder |

No store is shared. The stubs are not IAM or Dutch implementations; they only give the binding a
reachable peer and a place to observe deliveries.

## Run

```bash
cd src/main/resources/harness
./smoke.sh
```

Prerequisites: Docker with Compose v2, `curl`, `python3`, and JDK 21 (the script builds the boot jar
if `UMS_JAR` is unset). The script removes containers and volumes on exit.

## What it checks

1. `bindings/verify.py`: topology, pinned versions, default-off capabilities, secret scan, and the
   association-report pin hashes.
2. Compose configuration is valid before anything starts.
3. UMS starts and `/actuator/health` is UP.
4. `/api/v1/profile` without a caller token is `401`, not an anonymous profile.
5. `UMS_OUTBOX_RELAY_ENABLED=false` and no content authority: both privacy-sensitive capabilities
   are off in the shipped profile.
6. The UMS store is at migration `25` and the Dutch store is separate and empty.
7. Enabling egress without a consumer URI stops startup, so a missing binding is never a silent
   accumulation of retries.

## What it does not prove

- IAM producer authentication, snapshot authentication, and real delivery are `iam/DEP-2` and
  `iam/DEP-3`. The stubs cannot accept or verify a workload identity.
- The real Dutch consumer, its Member mapping, and the mobile client remain Dutch work
  (`dutch/PROFILE-BINDING`, `dutch/S7`).
- No real personal data or credential is used, so this is not privacy or production acceptance.
- The erasure journal is deliberately not configured here. A privacy-mode deployment sets
  `UMS_RECOVERY_ERASURE_JOURNAL_PATH` to durable storage and proves restore reconciliation as
  described in [recovery](../../../../docs/development/recovery.md) before live use.
