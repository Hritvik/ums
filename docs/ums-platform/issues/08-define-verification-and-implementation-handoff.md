Type: grilling
Status: resolved
Assignee: Codex
Parent: [Specify the shared User Management Service](../map.md)
Blocked by: 06, 07

# Define verification and implementation handoff

## Question

What testable acceptance matrix, TDD vertical slices, migration/retirement strategy, contract evidence, security review, restore/erasure drills, release gates, and outstanding owner actions make this specification build-ready without claiming UMS or any provider capability is implemented, validated, or production-ready?

## Answer

The UMS requirements and provider-neutral service design are ready to enter a separately scoped, contract-first implementation effort. Kotlin is the user's explicit learning choice. Concrete provider agreements, executable schemas, infrastructure choices, and runtime evidence remain explicit build/release prerequisites. This ticket is the implementation handoff; earlier tickets remain canonical for their individual decisions.

### Sources and ownership

- UMS repository: Kotlin/Spring implementation, database migrations, UMS-owned APIs/events/policies, tests, deployable artifacts, and operational evidence. Start from this map and glossary, then follow each decision ticket rather than copying its detailed decisions into another specification.
- Central portfolio contract `app-isolated-profile-management`: consumer expectation references, adoption assessment, ownership gaps, and integration gates. Preserve existing Dutch expectation IDs and consumer authorship; no provider promise is inferred from this design.
- IAM repository: app-subject/resource binding, authentication and delegation, onboarding/lifecycle events, authoritative snapshots, and IAM manifest acceptance. UMS cannot replace a missing IAM contract with a global user ID or a caller-supplied subject.
- Dutch repository: Member identity, placeholders/claims/canonicalization, financial authorization, mapping adapter, and consumer acceptance. Shared UMS does not inherit its domain rules.
- Content/notice/cloud owners: separately accepted asset, notification, and durability contracts. No owner assignment, provider cost, or production support roster has been established by this Wayfinder.

### Verification matrix

| Requirement area | Required evidence |
| --- | --- |
| App isolation / authority | Wrong issuer/environment/audience/type/app/actor/subject denied; no cross-app profile listing or correlation; human/workload/delegated permissions tested independently |
| Provisioning / readiness | Concurrent and replayed eligible entry creates one current binding; strict/default policies differ honestly; valid IAM session survives incomplete profile; no unrelated profiles |
| Display and policy | Unicode/length/null/unknown-field rules, explicit-name versus fallback, bounded avatar references, supported locale/timezone, version conflicts, and policy-revision compatibility |
| Lifecycle | Departure versus deletion, 30-day cancellation, 90-day closure retention, explicit reactivation, independent global/app causes, exact deadline races, and no resurrection |
| Inbox/outbox / repair | Atomic state/checkpoint/event commit, relay crash duplicate, unrelated source-version collision, gaps, poisoned schemas, snapshot races, inbox expiry, and erasure fences |
| Privacy / export / assets | No personal fields/secrets in events/logs/retry stores; deleted fields not replayed from idempotency results; download invalidation and asset attach/revoke/cleanup races |
| Product boundaries | Dutch keeps Member/mapping authority; readiness does not grant Ledger access; Midas financial timezone, Jarvis behavior, Chat participation, and health data remain outside UMS |
| Operations | Notice schedules/failure outcomes, bounded retention/jobs/rate limits, backup age/alerts, key/config isolation, quarantine visibility, and consumer acknowledgement semantics |
| Recovery / migration | Upgrade from prior schema, interrupted jobs/leases, backup predating local deletion, replay of independent privacy evidence before reads, and no erased-field rollback |
| Real consumer journey | IAM entry → UMS profile/required fields → Dutch-owned Member mapping → product-safe shell, with delayed/down providers and real authority validation |

- For each implemented behavior, observe a failing behavioral test before the minimal implementation, then refactor with passing tests. Use unit tests for policies/state transitions, PostgreSQL integration tests for constraints/transactions/concurrency, contract tests for producers/consumers, and end-to-end tests for critical real client journeys.
- Require at least 80% line and branch coverage for the implemented service, with explicit complete negative/race cases for critical authorization and privacy paths. Coverage is a floor, not evidence that an untested capability exists; never exclude difficult domain modules simply to pass it.
- Use injectable clock/ID/authority/content/transport boundaries in tests. Deterministic fixtures and substitutes exist only in isolated development/test configuration; production startup rejects test authority and missing required security configuration.
- No tests were executed against a new UMS implementation during this Wayfinder. Documentation/reference validation does not count as runtime acceptance.

### Build sequence

Each slice begins with failing behavioral/contract tests and ends with a demonstrable vertical result. Do not implement all persistence first and defer integration until the end.

#### Foundation — contract bundle and Kotlin harness

- Publish UMS-owned OpenAPI/JSON Schemas and the canonical `ums.profile.state-changed.v1` binding stream, separate operation facts, policy schema, actor matrix, and positive/negative fixtures from the settled semantic contract.
- Pin supported compatible Kotlin/JVM, Spring, PostgreSQL driver, migration, and testing versions from primary documentation during implementation setup. Keep Gradle Kotlin DSL; remove legacy dependency duplication only in the scoped implementation change.
- Set up Spring MVC, explicit JDBC transaction/persistence adapters, migrations, PostgreSQL integration fixtures, coverage/static/secret/dependency checks, module boundaries, safe errors/correlation, test keys, and fail-closed configuration.
- Exit: clean checkout validates contracts, starts against an empty migrated database under test authority, and proves unsafe production configuration cannot start. No provider adapter is represented as live.

#### Slice A — app-isolated provisioning and first profile

- Implement reviewed UMS adoption/policy, app-qualified binding/generation, unique app subject, IAM inbox checkpoint, atomic profile/outbox creation, and self/status reads.
- Exit: deterministic test entry creates one profile under concurrency/replay; default policy is ready, strict policy is incomplete, and wrong-app requests fail. Real IAM integration remains gated until its subject/authority fixtures are accepted.

#### Slice B — edits, readiness, and protected display

- Add four typed fields, expected-version updates, safe idempotency outcomes, per-app field permissions, generated fallback semantics, bounded protected display batches, canonical state events, and projection snapshots.
- Exit: isolated app edits, Unicode/null/size errors, version races, no-PII event fixtures, and gap repair pass against PostgreSQL. Absent avatar works without a content provider.

#### Slice C — actual IAM and Dutch integration

- Reconcile the IAM resource/subject, registered user/delegation/workload authority, onboarding/lifecycle/snapshot contract, and historical Dutch-owned UMS wording with named provider acceptance.
- Implement real token validation and durable relay adapters. Integrate the Dutch-owned Member mapping seam without moving its claims or Ledger authority into shared UMS.
- Exit: the real first consumer journey passes delay, UMS-down, duplicate/reorder/gap, wrong-authority, and profile-required recovery tests. Existing Dutch consumer expectations remain unhonored until their owners accept the evidence.

#### Slice D — lifecycle and privacy correctness

- Implement independent lifecycle causes, closure/reactivation, app-only 30-day deletion/cancellation, IAM global deletion, erased generations/fences, restricted privacy status, and race-safe finalization.
- Exit: exact deadline and cancellation/finalization races, independent causes, replay/idempotency, and generation re-entry tests pass. Privacy capability cannot be deployed until independent recovery evidence is proven.

#### Slice E — exports, notices, and content integration

- Add fenced export jobs/download authority, 24-hour artifact expiry, acknowledged 14/7-day notice intents, and reference-aware asset attachment/revocation/cleanup where providers accept their contracts.
- Exit: export/deletion races, failed notices, unavailable providers, revoked assets, and downstream-versus-local completion semantics pass. Gate avatar and closure-expiry behavior per integration rather than replacing missing providers with silent success.

#### Slice F — recovery, retention, and best-effort deployment

- Reconcile concrete cloud/storage/network/secrets/cost choices; implement encrypted daily backup, 7-day backup expiry, independently durable privacy evidence/acceptance protocol, monitored relay/finalizers, retention sweeps, and safe restore/runbooks.
- Exit: a backup predating app-only deletion is restored without exposing erased data; actual backup age/data loss/recovery time and operator limits are recorded. No availability SLA is implied by the best-effort tier.

#### Slice G — security and release evidence

- Complete schema/client compatibility, authorization/adversarial privacy tests, worker/transport/database outage drills, dependency/secret review, migration rehearsal, workload limits, and end-to-end real consumer evidence.
- Exit: all enabled capability gates below have named acceptance/evidence, no unresolved critical/high findings, and no speculative provider or reliability claims.

### Prototype and migration disposition

- Inventory standalone Kotlin/Mongo CRUD and Dutch UMS independently. Port only tested behavior consistent with the shared contract; preserve useful history intentionally, but do not preserve unsafe raw-identifier/list-all APIs as compatibility obligations.
- Verify whether any real user/development data must be retained before destructive changes. This Wayfinder did not inspect private database contents or authorize erasure/migration of them.
- If there is no live population, prefer a clean non-production PostgreSQL foundation rather than inventing a dual-store migration. If populated data exists, produce a separately reviewed ownership/identifier reconciliation and migration plan before writing it.
- Do not copy Dutch Member/claim records into generic profiles. Retire old routes/storage only after scoped replacement tests and consumer cutover evidence; keep irreversible data changes separate from cosmetic refactoring.

### Release gates

| Stage | Minimum gate |
| --- | --- |
| Local build / merge | RED/GREEN evidence; relevant unit/integration/contract checks; coverage floor; schema compatibility; migrations; static/dependency/secret review; reviewed diff |
| Staging integration | Real accepted IAM/Dutch contracts for enabled flows, isolated data/keys, signed immutable artifact identity, relay/reconciliation evidence, personal-data scanning, privacy races, and migration rehearsal |
| Avatar enablement | Accepted content authority/safe-state/revocation/cleanup contract and negative/race evidence |
| Closure-expiry enablement | Accepted notice owner, disclosed policy, accessible status/reactivation, notice schedule/failure evidence |
| Privacy-capable deployment | Independent recovery of acknowledged local privacy transitions plus erased-data-preserving restore evidence, scoped strong authority, finalization/export/audit controls |
| Initial live service | Named operators/consumer owners, actual cost and recovery limits, runbooks/alerts, all enabled capabilities evidenced, no critical/high security issue, and explicit launch approval |

- A failed gate disables or delays the affected capability; it does not authorize weaker identity, hidden retention, or false completion. No live product should depend on an optional feature without its required gate.
- Keep consumers at `explore` and provider commitments absent until explicit party acceptance. An available implementation is not validated until the observable consumer scenarios have evidence.
- Cloud/provider topology and a durability acceptance protocol are separate deployment design deliverables. They do not block contract-first local work; they do block unsupported live privacy/reliability claims.
- No implementation, commits, pushes, external messaging, provider mutation, or deployment has been performed as part of this Wayfinder.

### Kotlin learning approach

Build reviewable vertical changes and explain the Kotlin concepts used in each: immutable data/value types, nullability, sealed state handling, typed failures, collections, and explicit transaction boundaries. Use coroutines only when a measured requirement warrants their additional model. Learning should accompany useful tested behavior rather than produce speculative frameworks or exercises unrelated to UMS.

### Handoff status

The requirements and service-design decision map is complete. The next authorized work would be a separately scoped contract/harness foundation. Executable schemas, actual provider acceptance, operational ownership, cloud durability/cost reconciliation, runtime implementation, and test evidence remain outstanding; UMS is not claimed implemented or deployment-ready.
