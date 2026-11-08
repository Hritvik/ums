# Dutch consumer journey — UMS provider bindings (v1)

The bindings UMS needs to take part in the real Dutch journey without inventing IAM or Dutch
behaviour. Everything here is secret-free and synthetic; live credentials belong to the deployment.
The executable companion is the [synthetic harness](../../src/main/resources/harness/README.md);
the published wire artifact is the [association report pin](../../src/main/resources/bindings/association-report/v1/MANIFEST.json).

Status 2026-11-08: UMS is ready to be configured for the journey. IAM ingress authentication
(`iam/DEP-2`), the workload profile and snapshot authentication (`iam/DEP-3`), and the Dutch side
(`dutch/PROFILE-BINDING`) are still external. `ums/W-18` joins them and is the only UMS task left.

## Ownership

| Responsibility | Owner |
|---|---|
| Issuer, JWKS, audience, caller registrations, token profiles | IAM |
| Authenticated IAM ingress and snapshot transport | IAM (`DEP-2`, `DEP-3`) |
| Profile state, lifecycle, erasure, association report content | UMS |
| Member mapping, Ledger permissions, `/me` translation, mobile UI | Dutch |
| Process configuration, stores and delivery bindings on the UMS side | UMS (this document) |

UMS never reads IAM persistence, never becomes the Dutch Member service, and never treats profile
readiness as a Ledger grant.

## Topology

| Component | Version | Store | Notes |
|---|---|---|---|
| UMS | local boot jar / release tag | PostgreSQL 17.6, database `ums`, migrations 25 | real application |
| IAM producer | `iam/DEP-2` release | IAM-owned | authenticated onboarding delivery |
| IAM snapshot | `iam/DEP-3` release | IAM-owned | workload profile and repair endpoint |
| Dutch consumer | `dutch/PROFILE-BINDING` release | Dutch-owned | separate consumer and store |
| Harness stubs | `python:3.12-alpine` | UMS: PostgreSQL; Dutch stub: own SQLite | synthetic peers only |

No store is shared between products. The binding manifest records the versions so a topology
change is visible:
[`dutch-consumer-binding.json`](../../src/main/resources/bindings/dutch-consumer-binding.json).

## Environment trust and app scope

UMS accepts an app-scoped identity, not a global one. The values below are supplied by the
deployment; none has a production default.

| Property | Source | Constraint |
|---|---|---|
| `ums.environment` | `UMS_ENVIRONMENT` | `development`, `test`, `staging`, or `production` |
| `ums.security.issuer` | `UMS_IAM_ISSUER` | HTTPS, no credentials or fragment |
| `ums.security.jwk-set-uri` | `UMS_IAM_JWK_SET_URI` | HTTPS |
| `ums.security.audience` | `UMS_RESOURCE_AUDIENCE` | must equal the registration audience |
| `ums.security.registrations` | `UMS_CALLER_REGISTRATIONS` | JSON array, reviewed, no wildcard |
| `ums.iam.issuer-environment-ref` | `UMS_IAM_ISSUER_ENVIRONMENT_REF` | UUID of the trusted IAM environment |
| profile catalogs and receipt key | `UMS_PROFILE_*` | versioned catalogs; key ≥ 32 bytes |

The registration binds one `appId`, one `clientId`, one HTTPS audience, an explicit scope set, and
whether DPoP is required. The Dutch development registration is `dutch-development` /
`dutch-development-web`; it is a reviewed value, not an inference from a token.

## Ingress binding (IAM → UMS)

| Element | Binding |
|---|---|
| Event | `iam.app-subject.onboarding-ready.v1` only |
| Producer identity | `iam`; the transport attributes it, the body never does |
| Transport | workload mTLS (`DEP-2`); workload bearer is the documented fallback |
| Path | reserved as `/internal/v1/iam/events`; the route is not exposed until `DEP-2` lands |
| Body bound | 32 KiB, strict encoding, no trailing bytes |
| Correlation | `^[A-Za-z0-9._:-]{1,128}$`, taken from the authenticated request |
| Bad payload | quarantined with a safe class and digest; never retried forever |
| Gap or stale order | `RECONCILIATION_REQUIRED`, never a new profile |

UMS already owns the ingestion service, strict parser, application mapping, quarantine and
reconciliation outcomes. The HTTP seam stays closed (`anyRequest().denyAll()`) until the
authenticated transport exists; the adapter is not an authentication mechanism.

## Snapshot repair binding

| Element | Binding |
|---|---|
| Transport | workload bearer from the `DEP-3` profile |
| Path | reserved as `/internal/v1/iam/snapshot` |
| Page | explicit subject, bounded page (the bound is DEP-3's contract), explicit correlation |
| Outcomes | `NO_LOCAL_BINDING`, `NO_EVIDENCE`, `PAGE_INCOMPLETE`, `NO_CHANGE`, `CHECKPOINT_CONVERGED`, `RECONCILIATION_REQUIRED`, `REFUSED_ERASED`, `REFUSED_RESTRICTED`, `REJECTED` |
| Danger | a snapshot never creates a profile and never restores an erased or restricted one |

The repair service exists and is tested; the route waits for `DEP-3`, which also publishes the
workload profile UMS validates.

## Egress binding (UMS → Dutch)

| Element | Binding |
|---|---|
| Event | `ums.profile.association-report.v1`, schema version 1 |
| Channel | transactional outbox → relay → HTTPS consumer |
| Endpoint | `DUTCH_UMS_REPORT_URI` → `ums.outbox.consumer-uri`; absent means the relay stays off |
| Auth | deployment credential (`DUTCH_UMS_REPORT_TOKEN`) or workload identity |
| Ordering | one head per application stream, at-least-once, dedupe by `eventId` |
| Retry | 8 attempts, 30 s doubling to 1 h, 5 min lease by default |
| Failure | `TRANSPORT_UNAVAILABLE`/`TRANSPORT_REJECTED` visible and repairable; the head holds its stream |
| Correlation | forwarded from the source fact; causation is the source event id |
| Content | lifecycle, readiness, generation/revision, `contentObligation`; never a name, email, token, or asset reference |

`Application` and `stream` are the UMS app reference and binding, so a later revision can never
overtake a failed head. One cross-contract constraint is recorded rather than hidden: the runtime
accepts a correlation of `^[A-Za-z0-9._:-]{1,128}$`, while the report schema also requires the first
character to be alphanumeric. The emitter inherits the source correlation, so the ingress must not
forward a leading `.` or `:` into a fact that can reach the report; tightening that is a follow-up
outside this binding slice. The relay is disabled by default; enabling it without a consumer URI fails
startup instead of accumulating retries. See [outbox relay](outbox-relay.md) for the full state
machine.

## Published pin (W-8)

Dutch consumes the report as a read-only pin. UMS publishes a versioned bundle with a manifest and
hashes. The schema and `valid-live.json` are canonical under `contracts/v1/`; the reject fixtures
are published copies and the tombstone is derived for this pin from the published rules.

| Artifact | Path |
|---|---|
| Schema | [`association-report/v1/association-report.schema.json`](../../src/main/resources/bindings/association-report/v1/association-report.schema.json) |
| Shared definitions | [`association-report/v1/common.json`](../../src/main/resources/bindings/association-report/v1/common.json) |
| Manifest | [`association-report/v1/MANIFEST.json`](../../src/main/resources/bindings/association-report/v1/MANIFEST.json) |
| Valid live fixture | `fixtures/valid-live.json` |
| Valid tombstone | `fixtures/valid-tombstone.json` |
| Rejected fixtures | extra payload property, unknown environment, tombstone with live fields |

Compatibility is additive only within v1; a consumer rejects unknown properties and pins the
manifest hash. Run the read-only check from anywhere:

```bash
python3 src/main/resources/bindings/association-report/v1/verify.py
```

It checks every manifest hash and size, asserts each fixture is accepted or rejected for the stated
reason, and — when run inside the UMS repository — proves the published schema is byte-identical to
the canonical one. Dutch can run the same file against its own copy; it then verifies the pinned
bytes without needing the UMS tree.

## Default-off capabilities and the erasure journal

The shipped profile keeps privacy-sensitive capabilities off until a deployment turns them on:

| Capability | Default | Property |
|---|---|---|
| Outbox relay / Dutch egress | off | `ums.outbox.relay.enabled` |
| Content authority / avatars | off | `ums.content.authority-uri` |
| Privacy mode / erasure journal | off | `ums.recovery.erasure-journal-path` |
| Retention sweep and maintenance | deployment choice | `UMS_RETENTION_SWEEP_ENABLED`, `UMS_MAINTENANCE_ENABLED` |

The erasure journal is the deployed evidence that an erasure survives a restore. A privacy-mode
deployment must set `UMS_RECOVERY_ERASURE_JOURNAL_PATH` to durable storage, run
[the restore drill](restore-drill.md) with reconciled counts, and keep the journal outside the
database backup. The harness deliberately leaves it off: it must not report a privacy posture it
did not exercise. `allow-missing-evidence` stays false.

## Synthetic harness

[`src/main/resources/harness`](../../src/main/resources/harness/README.md) stands up UMS, its own
PostgreSQL, a separate Dutch consumer with its own SQLite store, and a producer stub. `./smoke.sh`
verifies the binding and pin, starts the topology, checks health, the `401` on the anonymous
surface, the default-off flags, migration 25, an empty Dutch store while egress is off, and that
enabling egress without a consumer URI fails startup.

It does not prove IAM authentication, a real Dutch consumer, the mobile client, or privacy
acceptance. Those stay with `DEP-2`, `DEP-3`, `dutch/PROFILE-BINDING`, and `dutch/S7`.

## Handoff and remaining gates

| Gate | Needs |
|---|---|
| `iam/DEP-2` | authenticated onboarding delivery to the reserved ingress path |
| `iam/DEP-3` | workload profile and snapshot authentication |
| `dutch/PROFILE-BINDING` | Dutch consumes the pin and preserves `/me` through replay |
| `ums/W-18` | joins the bindings and runs the journey against the real runtime |
| `iam/W-13` → `dutch/S7` | delivery faults and the controlled launch |

The local in-memory journey tests (`LocalConsumerJourneyTest`, `SimulatedDutchConsumer`) remain
useful regression evidence for event shape and authorization separation, but they are not the
real journey and never were. Their earlier record: 69 JVM tests, six contract-suite tests, seven
producer checks, 99.41% line / 90.84% branch at the time.
