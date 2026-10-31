Type: grilling
Status: resolved
Assignee: Codex
Parent: [Specify the shared User Management Service](../map.md)
Blocked by: 04, 05

# Complete contract boundaries and provider reconciliation

## Question

What exact field limits, operation permission matrix, schema/version policy, fixtures, and producer/consumer acceptance records must accompany the settled UMS integration design? Resolve or explicitly gate IAM app/resource subject binding, Dutch Member mapping, avatar-content attachment, closure notices, and deployment durability without inventing provider acceptance. Identify which machine-readable artifacts are part of the specification handoff versus separately executable implementation work.

## Answer

Fix the initial field, permission, and fixture requirements below. Keep the central `app-isolated-profile-management` capability contract exploring: design approval is not provider acceptance or runtime evidence. Missing provider decisions are named capability launch gates, not silent assumptions. No runtime/source changes or external messages are authorized by this record.

### Field and request rules

| Field / boundary | Initial contract |
| --- | --- |
| displayName | Optional; trim surrounding Unicode whitespace and normalize NFC; 1–80 Unicode code points when present; reject control characters, explicit bidi override/isolate controls, and line separators; text rendering only |
| avatarReference | Optional opaque content-provider reference, 1–256 UTF-8 bytes; validated app/subject ownership and ready/safe state; no URL or embedded image |
| displayLocale | Optional validated supported language tag, at most 64 ASCII characters; unsupported values return a safe field error |
| displayTimezone | Optional supported IANA timezone identifier, at most 128 ASCII characters; does not change product time semantics |
| Profile Reference / operation IDs | Server-generated opaque UUID references; never caller-selected or reused |
| IAM App Subject / registration IDs | Treat as opaque IAM-contract strings, case-sensitive; initial maximum 256 UTF-8 bytes, subject to explicit IAM contract reconciliation before implementation |
| expectedVersion | Nonnegative integer within signed 64-bit range; exposed as decimal string to avoid JavaScript precision loss; compare exactly |
| Timestamp | UTC timestamp string with explicit zone; never a client-supplied authoritative deadline |
| Profile patch | Only four named editable fields; absent means unchanged, explicit null clears an optional field; empty patch rejected |
| Mutation body | At most 16 KiB after bounded decoding; reject unknown properties, duplicate JSON keys, malformed Unicode, unexpected nesting, and unsupported content types |
| Display-read batch | 1–100 unique explicit Profile References; same-app authority for every item; no wildcard enumeration |
| Event envelope | At most 32 KiB; allowlisted metadata/payload; no caller-enriched arbitrary maps |
| Correlation ID | At most 128 ASCII characters from a conservative identifier alphabet; generate a replacement for untrusted invalid input |

- No default locale/timezone is inferred from another product, email, IP geolocation, or global identity. When absent, the product uses its presentation fallback. Persist preferences only from an authorized explicit update or an accepted app-local onboarding command.
- Maintain explicit supported locale/timezone data and record its version. Dependency updates must not silently invalidate stored choices; preserve existing data and provide a safe fallback plus a visible correction path when a value becomes unsupported.
- Treat an explicit display name separately from the generated fallback. An app policy can require a deliberately supplied name; resetting it produces honest unmet readiness rather than silently satisfying it with generated text.
- Profile policy declares supported required fields, policy revision, and allowed field operations; it cannot declare arbitrary schema/regex execution or claim IAM eligibility authority.
- API version, schemaVersion, profileRevision, bindingRevision, policyRevision, and IAM source checkpoints are distinct. Source revisions use lossless decimal strings in JSON. Clients must not compare timestamps to infer version order.
- Keep initial throttles configurable and bounded: 60 self reads/minute per app-subject, 10 profile updates/minute, 5 privacy commands/hour, and one export generation per app-subject at a time. Workload batch/repair quotas are separately registered and bounded; these are initial safety defaults to calibrate with load tests, not throughput commitments.

### Operation permission matrix

| Actor | Allowed initially | Explicitly excluded |
| --- | --- | --- |
| Direct human with UMS-targeted app-bound authority | Own profile/status read and permitted field edits | Other subjects/apps, configuration, arbitrary provisioning, lifecycle recovery bypass |
| Human/delegating workload with recent Strong authority | Own export, app-only deletion, eligible cancellation | Stored user tokens in jobs, generic act-as, weakened assurance |
| Registered product projection workload | Same-app purpose-bound display reads, scoped snapshots and acknowledgements | Global directory, editable human fields unless separately registered, deletion/cancellation as a user |
| Registered IAM event producer | Its app-scoped source facts under published schemas | Display field mutation or product-domain authorization |
| UMS relay/maintenance worker | Retry committed work, retention/finalization, publish immutable outbox | Invent user intent, extend deadlines, revive erased data |
| Configuration reconciler | Reviewed app adoption/policy/caller revisions | Profile browsing, user impersonation, changing IAM registry |
| Narrow operator | Safe status and controlled repair/retry with audit and case reference | Unrestricted data search, direct database editing, identity rebind, erasure reversal |
| Unregistered caller/anonymous caller | None of the private profile operations | Any profile read, directory, provisioning, or privacy action |

- Every row still requires exact resource/app/target binding and current lifecycle checks. Broad scope text is not proof of row-level permission. Browser BFF calls retain CSRF/session controls; native calls retain their registered sender constraint.
- Privacy jobs execute an already accepted immutable operation under UMS workload authority and lifecycle fencing; they do not need a copied expiring human token. Acceptance records include safe actor/assurance evidence, not the token itself.
- Display batch responses return permitted fields only. Hidden/missing/retired references have the same safe unavailable result; per-item errors cannot reveal another app's existence. Detailed repair state is restricted to separately authorized owner workloads.

### Event stream clarification

- Resolve the representation left open in the integration decision: publish one canonical `ums.profile.state-changed.v1` fact per changed binding revision, containing the post-transition minimized lifecycle/readiness state and a bounded change-reason class.
- The per-purpose names listed in the preceding integration ticket describe semantic change classes, not additional independent ordered streams. Do not publish both the canonical state event and competing same-revision ordered events. Record this clarification when generating contracts.
- Request/notice/export/content-cleanup delivery facts are separate operation streams with their own aggregate IDs/revisions. They cannot advance or overwrite profile binding checkpoints.
- Consumers apply only newer binding revisions; a gap triggers current-state snapshot repair. Snapshot/current-state replication may collapse intermediate display changes, but cannot replace mandatory privacy operation/audit/notice processing or acknowledge an operation that never completed.
- Schema contains explicit optional generation/reference rules for erased bindings and closed profiles. No personal display values, content references, raw identifiers, or arbitrary extension fields enter state-change facts.

### Provider reconciliation gates

| Dependency | Required agreement/evidence | Capability withheld until satisfied |
| --- | --- | --- |
| IAM app/resource binding | UMS audience preserves initiating app's pairwise subject; reviewed scope/client/delegation configuration; wrong-app/type/issuer negative fixtures | Real human/delegated UMS integration |
| IAM lifecycle/snapshot | Published app-scoped onboarding, departure, global deletion/cancellation versions and snapshot checkpoints; explicit shared-UMS reconciliation of historical Dutch-owned wording | Automatic live onboarding and IAM-driven lifecycle |
| Dutch Member integration | Dutch-owned mapping/claims seam, profile-readiness versus Ledger-authority tests, references to preserved consumer expectation IDs | Claim that Dutch's existing required outcomes are honored |
| Avatar content | App/subject-bound reference, safe-state/revocation contract, reference-aware cleanup and race tests | Avatar attachment/upload; absent-avatar core remains usable |
| Retention notices | Named product/notice owner accepts 14/7-day intents, disclosure/status/reactivation path, delivery/failure acknowledgements | Automated closed-profile expiry for that integration |
| Independent lifecycle recovery | Proven durable acceptance/replay protocol for post-backup privacy evidence, erasure-preserving restore drill, cost/ownership | Privacy-capable deployment release |

- No owner, provider acceptance, service level, paid provider, migration completion, or runtime validation is invented. A local deterministic test adapter can support development but never satisfies a production provider gate.
- Central contract records remain authoritative for cross-product coordination. Product repositories hold technical designs and fixtures, not competing copies of consumer expectations/commitments. Existing IAM/Dutch decisions are not silently edited here.

### Contract bundle and compatibility

- The first implementation foundation must publish UMS OpenAPI, strict JSON Schemas for DTOs/policy/event envelope/state/operation facts, the actor matrix, and positive/negative consumer fixtures in a UMS-owned versioned contract directory. External IAM/content schemas are pinned dependencies, not manually forked files.
- This Wayfinder supplies implementation-ready semantic rules, field bounds, and acceptance cases; generation/validation of executable schemas and adapters is the first separately scoped build deliverable. Handoff must explicitly name that gap; no endpoint or schema is represented as shipped.
- Required positive fixtures: default/strict policy provisioning, complete/incomplete profile, explicit fallback versus name, safe display response, closed/pending/erased states, independent deletion causes, lossless versions, and scoped snapshots.
- Required negative fixtures: global identity or personal fields in events, unknown mutation fields, duplicate JSON keys, invalid Unicode/control characters, oversized input, cross-app targets, wrong token subject kind/audience, unregistered delegation, version precision truncation, arbitrary avatar URL, expired download, stale snapshot, and restored erased state.
- Contract CI must test producer/consumer compatibility, schema allowlists, precise enum/null/version meaning, authorization matrices, and replay fixtures. Generated Kotlin classes are replaceable artifacts, not the external source of truth.
- Incompatible field/lifecycle/subject/security/event changes require a new contract version and documented migration. Optional additive response fields are allowed only where the schema policy permits; strict commands reject unknown fields. Keep consumers tolerant only where explicitly specified, not permissive for security-sensitive enums or unknown event types.

The ticket resolves what the contract and provider gates must contain. Providers remain unaccepted where noted, and actual machine-readable contract publication, runtime implementation, and evidence remain handoff work.
