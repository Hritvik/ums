Type: grilling
Status: resolved
Assignee: Codex
Parent: [Specify the shared User Management Service](../map.md)
Blocked by: 02, 03

# Design app-scoped integration and recovery contracts

## Question

How do IAM onboarding/activation/lifecycle facts, UMS profile provisioning/readiness, authorized profile reads/updates, content references, and Dutch-owned member projections compose into versioned APIs/events with app isolation, PII-free notifications, idempotency, ordering, tombstones, snapshot repair, and honest outage behavior? Reconcile IAM's historical Dutch-owned UMS wording without expanding global subject visibility or substituting profile readiness for product authority.

## Answer

Use versioned HTTP commands/queries and immutable app-scoped events with transactional outbox/inbox, broker-independent at-least-once delivery, and authenticated snapshot repair. UMS has one independently owned contract source. No exact endpoint, schema, adapter, or capability is implemented or validated by this decision.

### Application and actor binding

- Register UMS as an independently authorized resource/audience in IAM. Each integrated application's reviewed configuration declares UMS adoption, allowed callers/scopes, profile policy, and event/snapshot subscriptions. UMS maintains its own controlled policy registry; it does not read IAM's database or trust a request body as registration evidence.
- Maintain a UMS-owned, app-qualified lifecycle binding keyed by issuer/environment, App Registration, and App Subject, including active profile generation or minimal erasure tombstone. The binding and each generation have durable monotonic revisions. A deliberate post-erasure onboarding uses a fresh Profile Reference; a unique current binding prevents simultaneous active profiles. Retired references never resolve to a replacement profile.
- A human request must carry a UMS-audience token preserving the originating application and its App Subject. Supported native sender constraints and security-version rules follow IAM's registered client profile. A UMS audience alone does not justify replacing the app's subject with a shared UMS-wide subject. This exact app/resource claim binding requires an IAM conformance fixture before integration can launch.
- A product API/BFF calling for a user uses an explicitly registered one-hop delegation workflow with UMS as sole target audience, the app-scoped user subject, workload actor, narrow action scope, purpose, and required assurance. Default-denied IAM token exchange remains default-denied until that named workflow is registered; this design does not grant it implicitly.
- An own-behalf workload token may perform only separately allowed provisioning, projection, reconciliation, or operational tasks for its registered application. It cannot fabricate recent human authentication, impersonate a user, or initiate/cancel user deletion merely by supplying a subject ID.
- Derive caller application/actor from validated token and resource policy, and compare all supplied resource identifiers to that binding. Reject wrong issuer/environment/audience/type/app, stale security authority, forbidden field access, and lifecycle restrictions. No global identity ID, cross-app directory, or subject-correlation endpoint exists.
- Browser integrations use the product BFF and its protected session/CSRF boundary according to IAM's client policy. UI hosting and product screens are separate from this API contract.

### HTTP surface and command semantics

Canonical task-specific operations, to be published as OpenAPI before implementation:

| Surface | Purpose | Authority |
| --- | --- | --- |
| `/api/v1/profile` | Read/update the caller's app profile | UMS-targeted human/delegated authority, field allowlist |
| `/api/v1/profile/status` | Read provisioning/readiness/lifecycle and safe next actions | Same-app self authority, including restricted privacy journey |
| `/api/v1/profile/deletion-requests` | Request app-only deletion and inspect its status | Recent Strong human/delegated privacy authority |
| `/api/v1/profile/deletion-requests/{requestId}/cancellation` | Explicitly cancel an eligible request | Same subject/app, recent Strong, deadline/version checks |
| `/api/v1/profile/exports` | Request/inspect app-scoped export | Recent Strong privacy authority; separately protected download |
| `/internal/v1/apps/{appId}/profile-bindings/{subjectRef}/snapshot` | Repair one authorized app-scoped binding | Narrow registered projection/reconciliation workload |
| `/internal/v1/apps/{appId}/profiles/display-reads` | Bounded batch of explicitly requested profile display fields | Registered product projection workload; per-item scope check |
| `/internal/v1/apps/{appId}/profile-policies` | Reconcile reviewed policy revisions | Separate configuration authority, not ordinary product-user scope |

- Profile provisioning normally consumes IAM facts, not arbitrary public create-user CRUD. An internal ensure-provisioned repair command may request reconciliation but cannot bypass authoritative eligible entry, closure, erasure, or generation checks.
- No delete-all-users, unrestricted list/search, arbitrary profile object replacement, email/phone lookup, generic admin impersonation, or global identity rebinding API.
- Reads do not provision or mutate. Successful queries use `{data: ...}`. Profile status can return `200` with PROFILE_REQUIRED and safe completion instructions; a product action gated on readiness can return `409 profile_required`. Accepted asynchronous commands return `202` and a scoped operation reference; durable synchronous creation uses `201` where applicable.
- Use IAM's safe error envelope with stable code, message, optional schema-bound details, and correlationId. Use `400` malformed input, `401` invalid authentication, `403` forbidden action, non-enumerating `404` for hidden targets, `409` state/version/idempotency conflict, `422` semantic validation, `429` or retryable `503` with Retry-After, and generic `500`.
- Use explicit expectedVersion in mutation DTOs to align conflict handling with IAM. State-changing commands require UUID Idempotency-Key scoped to authenticated actor, app, operation, and target, plus a canonical request fingerprint. Different content under the same key fails with `409 idempotency_key_reused`.
- Reauthenticate/re-authorize each retry before returning its stored safe outcome. Never replay an old response containing deleted fields, export capability, or revoked access after lifecycle changes. Persist minimal operation references, not recoverable tokens or personal-value snapshots in idempotency rows.
- Reject unknown mutation fields and apply bounded input/batch/body sizes, per-app/actor/subject quotas, bounded concurrency, and stricter privacy-operation limits. Exact numerical limits and supported field formats are release-contract fixtures, not implicit unlimited behavior.
- Profile/display/privacy responses are private and non-shared-cacheable. Correlation/trace metadata is bounded and contains no user fields, tokens, or identifier values. Logs do not record raw request/response bodies. App-scoped opaque references remain access-controlled pseudonymous data, not anonymous telemetry.

### Event families and privacy

- Consume IAM-owned onboarding-ready, activation-change/departure, security-version, and app-subject deletion-pending/cancelled/final facts using IAM's published app-routed contracts. Do not fork IAM event definitions. The deprecated identity-verified fixture cannot become the production shared-profile trigger without its app-subject semantics being corrected.
- Publish UMS-owned `ums.profile.provisioned.v1`, `ums.profile.changed.v1`, `ums.profile.readiness-changed.v1`, `ums.profile.closed.v1`, `ums.profile.reactivated.v1`, `ums.profile.deletion-requested.v1`, `ums.profile.deletion-cancelled.v1`, and `ums.profile.erased.v1`.
- All UMS events for a binding share its ordered bindingRevision; eventId deduplicates individual facts and profileRevision describes the particular generation. If a transaction emits several facts, use documented event ordinal/count or a single grouped change, not competing events whose identical version appears to be a duplicate. Canonical schema must choose one representation consistently before coding.
- Envelope: immutable eventId/type/schemaVersion, producer/environment, app-qualified aggregate reference, bindingRevision, occurredAt, correlationId, causationId, and allowlisted payload. Payload includes only necessary app/subject/profile-generation references, profile/policy revisions, readiness/lifecycle, request/deadline/version facts, and safe reason classes appropriate to that event.
- Exclude global Human Identity IDs, email/phone, display name, avatar URLs/references, raw locale/timezone values, credentials, tokens, free-text notes, arbitrary metadata, and content bytes. A change event invalidates cached display; an authorized read retrieves the actual fields. These are identifier-minimized facts, not a claim that opaque references eliminate privacy obligations.
- Route events only to registered consumers within the originating app; shared physical infrastructure conveys no right to consume another app's stream. Export URLs and credentials never travel on the event bus.

### Commit, ordering, and repair

1. Validate producer identity, environment, destination app, schema, size, aggregate/stream semantics, and field allowlist before processing.
2. Consume in a local transaction: unique inbox event ID plus input checkpoint, locked/expected-version binding state, profile/lifecycle effect, audit, and resulting outbox. Database uniqueness is necessary in addition to inbox deduplication.
3. Relay immutable outbox facts with retry and observable delivery status. A broker acknowledgement means publication, not downstream completion. No network call occurs inside the authoritative local transaction.
4. Track versions per source stream; IAM activation, security, deletion, and UMS revisions are not one comparable clock. Maintain independent causes/checkpoints. Never infer cross-stream order from timestamps or reuse a single highest-version counter for unrelated streams.
5. Duplicates are harmless; documented older events cannot regress state. A gap pauses permissive effects for the affected binding and requests scoped repair. Authenticated restrictive facts can install a provisional deny while repair runs; a delayed onboarding event cannot undo it.
6. Repair uses an owner-authenticated snapshot containing current app-scoped authority, generation/binding revisions, lifecycle/readiness, relevant independent causes, and explicit per-stream checkpoints. Persist checkpoint plus repaired state atomically. No direct cross-database reads or reconstruction from guessed event order.
7. A snapshot cannot remove a newer local tombstone or independent deletion cause. A retained erasure fence survives operational inbox expiry. Re-entry requires a distinct eligible intent/version accepted through the lifecycle contract, not a stale ACTIVE snapshot. Cross-owner ordering ambiguities remain restricted until reconciliation resolves them.
8. Bounded bulk repair uses an app-bound opaque cursor and a documented snapshot watermark/replay checkpoint. Unknown or hidden profiles are handled without cross-app existence disclosure. Quarantined poison/incompatible events stay visible with immutable identity; no silent discard.

### Provisioning, projections, and outage behavior

- IAM commits app eligibility/onboarding intent, then UMS provisions idempotently and publishes readiness. Optional IAM readiness acknowledgement is versioned and reconciled; acknowledgement of delivery is not profile completion and cannot itself change activation authority.
- Dutch's adapter consumes UMS profile facts, associates its own Member ID, retrieves permitted display data, and publishes Dutch-owned member/mapping authority. Ledger authorizes from its own valid Dutch projections, not from a UMS profile event alone.
- UMS outage pauses new provisioning, profile changes, and privacy command acceptance unless durably accepted by their authoritative owner. Clients show pending/retry without discarding valid IAM sessions. Products may continue operations from valid local projections where their domain/security policy allows; they cannot infer fresh profile readiness or erase restrictions from cache.
- IAM outage follows IAM policy: valid resource tokens/projections may work locally, while first entry, new delegation, refresh/reauthentication, and authority-dependent privacy operations pause. Do not accept an asynchronous deletion cancellation without its required current authority.
- Separate local erasure, event publication, consumer acknowledgement, and downstream cleanup outcomes. Dashboards and alerts report lag and failures without centralizing product fields.

### Avatar/content integration

- UMS accepts only a content-owner-issued app/subject-bound asset reference with an allowed ready/safe state, validated through an authenticated contract. Never fetch an arbitrary supplied URL.
- Attachments must be checked before adoption and cannot grant public visibility. Where validation crosses a network, prepare outside the profile transaction and use bounded reference/version evidence; concurrent revocation is repaired through content lifecycle facts and protected reads.
- Content deletion/revocation invalidates the profile association and increments its revision; use a neutral avatar while unavailable. Profile deletion detaches the association and requests idempotent content-owner cleanup subject to reference ownership, with independently visible acknowledgement.
- No content provider has committed to these guarantees yet. Avatar upload/attachment cannot launch without that provider contract; the core profile supports no-avatar behavior.

### Ownership reconciliation and contract delivery

- IAM remains canonical for identity, subject/activation, token/delegation, lifecycle facts, and IAM snapshots. UMS owns its profile APIs/events/policies and isolated runtime. Dutch owns Member/mapping/claim integration. Each publishes one pinned, versioned contract source.
- IAM's resolved app-integration and implementation-handoff tickets currently describe Dutch-owned UMS. This decision proposes replacing that runtime assumption with an app-isolated shared profile service, while retaining Dutch-owned Member integration. Do not silently rewrite those historical decisions or mark provider acceptance complete.
- Before integrated implementation, obtain matching IAM app/resource subject-binding and onboarding fixtures, Dutch Member seam fixtures, and content-owner acceptance where avatars are enabled. Record unresolved capability dispositions centrally. No IAM production delegation workflow is enabled merely by naming it here.
- Publish OpenAPI, JSON Schemas/AsyncAPI, field limits, operation/actor permission matrix, positive/negative fixtures, and conformance tests before runtime implementation. This ticket fixes their semantics; it does not claim those machine-readable artifacts already exist.
- Breaking subject, lifecycle, field, security, or event meaning requires a new version with explicit consumer migration and bounded dual publish/replay; never reinterpret old events. Support current and immediately previous stable version for a declared migration window, with separately documented emergency security retirement.

### Required acceptance evidence

- Reject wrong-app/issuer/audience/type, forged body app IDs, unknown workloads/workflows, actor-less delegation, scope escalation, stale authority, and another user's asset reference.
- Prove duplicate/concurrent onboarding, out-of-order restrictive facts, per-stream version collisions, lost acknowledgements, event gaps, incompatible schemas, expired inbox history, and snapshot races converge without duplicate or resurrected profiles.
- Scan payloads/logs/traces and retry stores for personal fields/secrets; reject malicious names, oversized batches, URL-based avatar SSRF, and hidden-target enumeration.
- Prove incomplete profiles preserve sessions; UMS/IAM/content/consumer outages produce documented scoped failures; product projections never acquire authority from profile readiness alone.
- Prove idempotent privacy retries and cached responses cannot disclose erased fields, reuse download authority, or cancel deletion after its deadline.

These are design requirements, not test results. The next decision fixes operational retention and recovery parameters; machine-readable contracts, provider reconciliation, and implementation remain outstanding.
