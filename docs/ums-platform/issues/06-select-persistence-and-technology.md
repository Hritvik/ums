Type: grilling
Status: resolved
Assignee: Codex
Parent: [Specify the shared User Management Service](../map.md)
Blocked by: 04, 05

# Select persistence and implementation technology

## Question

What language/runtime, modular boundaries, database model, migrations, outbox/inbox, independent lifecycle durability, and adapter strategy satisfy UMS's app isolation, lifecycle, and best-effort operating tier with minimal portfolio operational variety? Compare the existing Kotlin/Mongo prototype with IAM's Java/Spring/PostgreSQL direction without treating either as an automatic mandate or assuming unverified cloud capabilities.

## Answer

The user explicitly selected Kotlin to learn the language through this service. Build UMS as an idiomatic Kotlin/JVM Spring Boot modular application with PostgreSQL, Flyway migrations, explicit Spring JDBC persistence, and transactional inbox/outbox. Keep Gradle Kotlin DSL. Pin compatible supported versions during implementation planning rather than freezing the prototype's versions into the specification.

### Rationale and alternatives

- Kotlin is an intentional learning and maintainability choice, not a requirement imposed on consumer products. Use immutable domain values, explicit nullability, exhaustive state handling, and small application interfaces to make the language and service behavior legible.
- Java would align source-language conventions with IAM, but the user's Kotlin learning goal takes precedence. Both services can still share JVM operational knowledge, database tools, contract validation, and deployment conventions without sharing domain libraries or database state.
- Keep Spring Boot as the established JVM application foundation. UMS is a resource server, not another authorization server; do not embed IAM ceremonies or Spring Authorization Server in UMS.
- Select PostgreSQL for explicit relational uniqueness, versioned state transitions, local transactions spanning profile/inbox/outbox/audit, and portfolio operational consistency. The existing MongoDB model is CRUD evidence, not a reason to preserve its storage boundary.
- A rewritten MongoDB transactional design remains possible but offers no evidenced requirement advantage here. Do not operate two authoritative profile stores or add dual-write migration solely to preserve an unused prototype.
- Prefer explicit JDBC repositories and visible transaction boundaries over an entity graph that silently persists mutations. Domain values remain immutable; repositories map dedicated persistence records. No reactive stack or ORM is required by current workloads.

### Runtime and module boundaries

- Use a conventional request/transaction model with Spring MVC and blocking JDBC initially. Bound request concurrency, connection pools, jobs, and outbound calls. Do not mix blocking JDBC into event-loop handlers.
- Coroutines/Flow are not mandatory for ordinary CRUD. Introduce structured coroutine concurrency only for a measured independent-I/O requirement with explicit cancellation, timeouts, and transaction/thread boundaries. Learning Kotlin should not require an additional reactive programming model at the same time.
- Logical modules: application registration/profile policy, profile binding and presentation, provisioning/readiness, lifecycle/privacy, authorization adapters, integration inbox/outbox/repair, and operations/audit. Each module owns its interfaces and persistence access; prohibit domain access through web DTOs or another module's repository internals.
- Keep API DTOs, immutable domain types, and persistence mappings separate. Small value types represent app/subject/profile references and versions, preventing accidental identifier interchange. Runtime validation is still required at every external boundary.
- One versioned application artifact runs API, relay, and scheduled maintenance modes initially. Worker leases/fencing and database constraints protect duplicate execution. Independent worker deployment is an operational mode, not an invented new microservice.
- Keep provider-neutral ports for clock/IDs, token verification, IAM snapshots, event transport, content references, notices, protected exports, secrets/key access, and lifecycle recovery evidence. Do not introduce generic plugin machinery or a universal repository abstraction without a domain need.

### Durable model and transactions

Own one UMS database and credentials per environment. IAM and products never read it directly, even when databases share a physical host.

| Record group | Authoritative responsibility |
| --- | --- |
| App configuration / profile policy revisions | Reviewed adoption, callers, field requirements, policy history |
| Profile binding | Unique issuer/environment + app + App Subject, active generation, binding revision, minimal erasure fence |
| Profile generation | Non-reused Profile Reference, typed presentation fields, profile revision |
| Lifecycle causes / privacy operations | Closure, independent app/global deletion causes, authoritative deadlines, cancellation/finalization state |
| IAM source checkpoints | Separate activation/security/deletion/onboarding source versions; never one cross-stream counter |
| Inbox / outbox | Producer/event deduplication, delivery intent, replay and repair metadata |
| Command idempotency | Actor/app/operation/target key, fingerprint, safe outcome reference |
| Export / notice / content cleanup jobs | Scoped work status, retry leases, lifecycle fence, provider acknowledgement |
| Audit / recovery metadata | Minimal protected security/lifecycle evidence and restore checkpoints |

- Use database uniqueness and foreign-key/check constraints as correctness boundaries, including one current binding per app subject and non-reused profile references. Archive or erase old generations without reassigning their references.
- Lock or compare expected binding/operation versions in one short transaction. Persist state, relevant input checkpoints, idempotency outcome reference, audit, and outbox together. Commit before network operations.
- Separate typed columns for the initial supported fields and lifecycle causes; no arbitrary product-state JSON bag. JSON is appropriate for versioned allowlisted immutable contract payloads, not unvalidated domain extensions.
- Store timestamps in UTC and evaluate deadlines through an injected clock. Use database-enforced concurrency and authoritative deadline comparisons rather than assuming one scheduler instance or reliable local timer.
- Repositories use parameterized statements and explicit selected columns. No entity serialization into HTTP/events, broad SELECT-to-DTO exposure, or unrestricted bulk profile update.
- Jobs claim bounded batches using expiring leases and fencing/version checks. A stale worker cannot attach an export, cancel an operation, or publish readiness after newer erasure.
- Outbox publication can duplicate across crash/retry; preserve immutable event identity and rely on transactional consumer deduplication. Do not promise exactly-once transport.

### Migrations, storage security, and restore

- Flyway owns forward versioned migrations and database checks. Test empty install and upgrades with realistic constraints, concurrent writes, interrupted jobs, and profile-erasure fixtures.
- Use expand/contract for compatible deployment overlap, with a forward repair plan. A rollback cannot restore retired personal fields, reverse a lifecycle version, or silently reinterpret old event payloads.
- Encrypt database volumes/backups and protected export storage; obtain runtime credentials/keys externally under least privilege. This decision does not invent a provider-specific KMS or require IAM's identifier-vault key model for ordinary profile text.
- Protect app-scope filters at the application/repository boundary and test every query. A database isolation mechanism may reinforce these checks, but does not replace validated caller authority or justify shared product access.
- Independent recovery of post-backup deletion/closure/cancellation evidence is a deployment prerequisite, not solved merely by adding an outbox table in the same database. A WAL/PITR or separately durable minimized lifecycle-journal design must document acknowledgement, ordering, replay fencing, and failure behavior before privacy operations launch.
- If replication/archive lag could lose an acknowledged privacy transition, do not claim it durable. Select a proven acknowledgement protocol or explicitly gate the capability until the cloud design meets the invariant. Do not add a second-store dual write and call it atomic.
- Best-effort uptime does not relax no-resurrection: a restore with insufficient privacy evidence remains restricted until reconciled. Provider, durability placement, and achieved recovery remain pending cloud reconciliation.

### Prototype disposition and learning-oriented implementation

- Retain the existing code until a separately scoped implementation foundation replaces it; do not modify runtime source during Wayfinder.
- Inventory old Kotlin/Spring code into port, replace, retire. The raw email/phone User document, unrestricted CRUD/list-all routes, Mongo persistence, application gateway role, and reactive composition do not define the target contract.
- No live production population has been established for standalone UMS. Verify actual data ownership before implementation; do not fabricate production migration obligations or delete development/private data without a scoped plan.
- Build through vertical behavior tests and small idiomatic Kotlin changes. Explain domain/value types, nullability, sealed state handling, transaction boundaries, and error modeling as each is introduced. Avoid clever DSLs, unnecessary reflection, hidden mutation, or abstraction built solely to demonstrate language features.
- External OpenAPI/events remain language-neutral. Consumers do not import Kotlin data classes, Java serialization, Spring internals, or UMS database schemas.

### Verification requirements

- Test uniqueness and lifecycle concurrency against real PostgreSQL through isolated integration fixtures, including duplicate provisioning, cancellation/finalization races, separate IAM stream checkpoints, and retired generations.
- Prove state+inbox/outbox atomicity under rollback, relay crashes, worker lease loss, and expected-version conflicts.
- Verify query-level app isolation, field validation, no raw identifier/event leakage, privacy-safe idempotency outcomes, and no cross-service database access.
- Exercise migrations, retention sweeps, restore fences, and post-backup privacy recovery before deployment acceptance.
- Align with mandatory TDD and coverage gates in the implementation handoff; this architecture decision is not an executed build/test result.
