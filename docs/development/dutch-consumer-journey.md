# Local Dutch consumer journey

This is a UMS-owned integration harness using a test-only product consumer.
It is not Dutch implementation, owner acceptance, transport verification, or
a real login-to-Ledger end-to-end journey. Dutch's working tree is unchanged.
The production gate remains the [Slice C integration boundary](iam-integration.md).

## Intended observable journey

1. An adopted application's trusted IAM onboarding event enters the real UMS
   adapter and provisioning service. Before delivery, status remains pending;
   reading status does not provision a profile.
2. PostgreSQL commits one app-isolated binding and its canonical state outbox
   event. A strict policy can leave the profile incomplete without invalidating
   the user's IAM session.
3. A simulated same-app product consumes the actual persisted event. Its own
   Member association and domain permission remain outside UMS authority.
4. The user edits permitted fields through the real internal editing service.
   Readiness advances with one profile/binding revision and one outbox fact.
   The product reads permitted display fields through the protected application
   seam, rather than copying personal fields from events.
5. Duplicate/stale delivery cannot create another association or regress state.
   A revision gap requires repair and cannot establish fresh readiness. Foreign
   application facts cannot enter the product projection.
6. A ready profile satisfies only the profile prerequisite. A separate
   Dutch-owned domain permission is still required for a Ledger action.

Authority objects used by these tests are explicitly trusted local seams. They
do not demonstrate IAM token validation, sender constraints, delegated workload
authority, current security-version validation, or network authorization.

## Real consumer handoff

| Responsibility | Required implementation or decision | Current boundary |
| --- | --- | --- |
| IAM | Running activation/onboarding producer and authenticated delivery | Contracts and local adapter tests exist; producer flow remains absent |
| UMS | Production caller validation, scoped status/display routes, relay and repair | Application seams exist; business HTTP routes remain denied |
| Dutch | App-subject/profile-to-Member association and its published mapping event | Owned by Dutch; no shared-UMS Member service is introduced |
| Dutch | Profile-required UI, retry behavior, permitted display retrieval | Simulated locally; actual Gateway/mobile integration remains absent |
| Dutch | Ledger membership/role checks independent of profile readiness | Test-only policy separation; real domain acceptance remains outstanding |
| Both consumers/providers | Delay/outage/replay/gap and wrong-authority evidence against real services | Local harness evidence cannot mark this gate accepted |

No provider commitment or consumer expectation changes solely because this
harness passes. The central portfolio capability record retains ownership of
those acceptance decisions.

## Harness limits

The simulated consumer is in-memory and handles one binding. It is a test
fixture for observable integration behavior, not a reusable SDK or durable Dutch
inbox. Its explicit Ledger grant is a test stand-in for Dutch's real domain
authorization. A simulated denial proves the two concepts are separable; it
does not prove Dutch's production permission checks.

A missing message is represented by withholding delivery, and a UMS failure by
an injected PostgreSQL outbox constraint failure. The harness does not stop a
live service, verify broker retries, simulate network partitions, or preserve
real IAM sessions. Gap recovery uses missing-event delivery followed by an
explicit retry; no authenticated snapshot implementation is implied.

## Recorded verification

On 24 October 2026, `./gradlew clean check bootJar --no-daemon --console=plain`
passed using the JDK 21 and Colima settings in [foundation](foundation.md):
69 JVM tests, zero failures/errors/skips, six contract-suite tests, and seven
producer/input checks. JaCoCo reported 99.41% line and 90.84% branch coverage.

Five PostgreSQL journey tests exercise the real IAM adapter and UMS application
services with actual persisted outbox JSON. Generated event, status, and display
artifacts are schema-validated and cross-checked for the same profile reference,
revision, readiness, and state. Protected projection reads enforce an explicit
field allowlist and return unavailable for a foreign application.

RED evidence includes the initially missing test-support consumer, missing
journey artifacts, and a runnable first-gap subject-contamination regression.
The simulator now pins its single subject before recording gap or revision
state. Final read-only review found no remaining material finding within this
local harness scope. No production code or upstream repository changed in this
step.
