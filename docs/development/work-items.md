# Work Items — UMS remaining implementation (Slices D–G)

> **Coordination status (2026-11-01).** Status lines below mirror the central registry; the registry remains authoritative for execution state and leases. W-1–W-4 and W-10 are integrated, W-5 is implemented and awaiting review, and the remaining items are planned.

These work items continue the build sequence in the [implementation handoff](../ums-platform/issues/08-define-verification-and-implementation-handoff.md) after the already-verified Foundation and Slices A, B, and D1–D3. The scope boundary is recorded in [artifacts/scope-boundary.md](artifacts/scope-boundary.md). Work items are numbered `W-N` for cross-reference only. `Depends on` lines refer to other work items in this file.

Five provider inputs do not exist yet and are carried as named gaps rather than invented: an IAM app-departure/global-deletion event contract (inherited by W-4 and W-6), a content-provider reference/cleanup contract (W-11), a notice-owner delivery contract (W-10 and W-15), a Dutch-owned Member-association contract (W-8), and named operators/consumer owners (W-14, W-15, W-18). Until an owner accepts one, the affected work item may build and verify the local behavior but cannot claim the integration.

## Shared reference artifacts

- [Executable v1 contracts](../../contracts/README.md) — the authoritative wire, event, actor, and validation rules; every item that produces or consumes a payload must honour it rather than restating it.
  - [OpenAPI surface](../../contracts/v1/openapi.json) — the eight declared business and internal operations; `#/paths` for the operation, `#/components` for shared parameters.
  - [Binding state schema](../../contracts/v1/schemas/binding-state.json) — lifecycle, readiness, generation, revisions, and the two independent deletion causes.
  - [Profile state event schema](../../contracts/v1/schemas/profile-state-event.json) — the single canonical `ums.profile.state-changed.v1` stream and its reason values.
  - [Common definitions](../../contracts/v1/schemas/common.json) — scope, lifecycle, readiness, version, and timestamp primitives.
- [Lifecycle boundaries decision](../ums-platform/issues/03-define-profile-lifecycle-boundaries.md) — closure versus deletion, reactivation rechecks, independent causes, and erasure limits.
- [Integration and recovery contracts](../ums-platform/issues/04-design-app-scoped-integration.md) — authority binding, HTTP semantics, event families, commit ordering, and repair.
- [Retention and recovery decision](../ums-platform/issues/05-retention-operations-and-recovery.md) — the 90-day closure period, notice windows, record retention table, alerts, and restore rules.
- [Implementation handoff](../ums-platform/issues/08-define-verification-and-implementation-handoff.md) — slice definitions, verification matrix, and release gates.
- [Verification harness](foundation.md) — the exact JDK, Docker, Python, and Gradle commands every item is verified with.

## W-1 — Close a profile on app departure and publish its retention deadline

**Summary.** When a person leaves an app, their profile for that app must stop being usable, but it must not be silently erased. Today the code can represent a closed profile but nothing ever closes one. This item adds that transition and records the date by which the retained data will be erased. It also makes the closure visible to the app as a normal lifecycle fact.

**Status.** Complete — recorded as done by the UMS owner and mirrored in the central registry (`ums/W-1`, manual).

**Work to be done.**
- Add a trusted application seam that closes the current profile for one app and subject, following the same constructor-bound authority style as the existing onboarding and privacy seams.
  - Place it with the other profile application services and reuse the existing policy-row lock so closure serializes with edits, deletion, and finalization.
- Record closure as a durable fact: the lifecycle becomes closed, the binding revision advances once, and the retention deadline is stored as an absolute instant derived from the authoritative closure time.
  - Keep the readiness value restricted rather than rewriting stored fields, and keep any existing independent deletion causes untouched.
- Publish one canonical state event with the closure reason on the existing binding stream, in the same transaction as the state change.
- Make ordinary reads, edits, and display projections refuse a closed profile exactly as they already refuse a restricted one, without adding a new error shape.
- Expose the closure deadline and the remaining lifecycle state through the restricted status read so a person can be told when their data expires.

**Justification.** The handoff requires the lifecycle slice to implement "closure/reactivation" with "90-day closure retention", and the lifecycle decision states that app departure closes normal profile use without scheduling erasure.

**References.**
- **Plan decisions** — [Slice D of the handoff](../ums-platform/issues/08-define-verification-and-implementation-handoff.md#slice-d--lifecycle-and-privacy-correctness) defines lifecycle and privacy correctness as the slice this item continues. [Lifecycle dimensions](../ums-platform/issues/03-define-profile-lifecycle-boundaries.md#separate-lifecycle-dimensions) separates closure from deletion and forbids reopening closed state through login, edits, or replay.
- **Retention** — [Lifecycle deadlines](../ums-platform/issues/05-retention-operations-and-recovery.md#lifecycle-deadlines) sets closed-profile retention at 90 elapsed days with the deadline published at closure.
- **Event contract** — [profile-state-event.json#/properties/reason](../../contracts/v1/schemas/profile-state-event.json) already carries the `CLOSED` reason and the closed binding state; this item emits it, it does not extend the schema.
- **Repo doc** — [provisioning.md](provisioning.md) for the existing lock order and transactional outbox pattern this item must reuse.

**Acceptance criteria.**
- [ ] Closing a profile commits the lifecycle change, exactly one binding revision increment, the absolute retention deadline, one closure event, and a minimized audit record together, or commits nothing.
- [ ] A closed profile refuses ordinary reads, edits, and projection reads, while the restricted status read still returns lifecycle, readiness, and the retention deadline.
- [ ] Repeating the closure for the same authoritative closure time is harmless and does not move the deadline or emit a second event.
- [ ] A different closure time for an already closed profile is rejected rather than silently shortening or extending retention.
- [ ] Automated tests cover the transition, the repeat, the conflict, and the refused-read paths against disposable PostgreSQL.

**Depends on.** `None.`

## W-2 — Reopen a retained profile through deliberate reactivation

**Summary.** A person who returns to an app after leaving should get their retained profile back, but only through a deliberate action and only while it still exists. Login, a retried event, or a normal edit must never do it by accident. This item adds that reopen path and its failure rules.

**Status.** Complete — recorded as done by the UMS owner and mirrored in the central registry (`ums/W-2`, manual).

**Work to be done.**
- Add a trusted reactivation seam for one app and subject that succeeds only from a closed profile whose retention deadline has not passed.
  - Reuse the closure item's lock and deadline fields so reactivation and closure expiry race through one transaction.
- Refuse reactivation while any deletion cause is present, while adoption is disabled, or after erasure, and make all three failures indistinguishable to an ordinary caller.
- Advance the binding revision exactly once, clear the retention deadline, emit the reactivation reason on the canonical stream, and return the profile to the active lifecycle without changing stored display values.
- Ensure a reactivated profile is re-evaluated against the current policy revision so readiness reflects today's required fields rather than the fields required at closure.
- Keep the retired generation and its references intact so prior edit and privacy receipts cannot be replayed against the new active state.

**Justification.** The handoff requires closure and reactivation together in the lifecycle slice, and the lifecycle decision states that reactivation must recheck IAM activation, current policy, and pending lifecycle operations.

**References.**
- **Plan decisions** — [Lifecycle dimensions](../ums-platform/issues/03-define-profile-lifecycle-boundaries.md#separate-lifecycle-dimensions) requires deliberate reactivation with current-policy and deletion checks. [Lifecycle deadlines](../ums-platform/issues/05-retention-operations-and-recovery.md#lifecycle-deadlines) requires that eligible reactivation cancels the closure-expiry schedule.
- **Event contract** — [profile-state-event.json#/properties/reason](../../contracts/v1/schemas/profile-state-event.json) already carries the `REACTIVATED` reason.
- **Repo doc** — [profile-deletion.md](profile-deletion.md) for the rule that retries and edits cannot cancel or reopen anything.

**Acceptance criteria.**
- [ ] Reactivation succeeds only before the retention deadline and produces exactly one revision increment, one event, and a readiness value computed from the current policy.
- [ ] Reactivation is refused for an erased profile, for a profile with an active app-only or IAM-global deletion cause, and for a disabled app.
- [ ] Stored display fields survive reactivation unchanged, and a profile that no longer satisfies the current policy reports incomplete readiness rather than claiming readiness.
- [ ] Prior edit and privacy command receipts cannot be replayed to change the reactivated profile.
- [ ] Automated tests cover each refusal, the deadline boundary, and concurrent reactivation against closure expiry.

**Depends on.** `W-1.`

## W-3 — Erase retained profiles when the closure deadline passes

**Summary.** A closed profile is kept for a fixed period so the person can come back. After that period it must be erased even if nobody is watching. This item adds that expiry, using the erasure path that already exists for deletion requests.

**Status.** Integrated — see [closure and retention](profile-closure.md) and the central evidence file.

**Work to be done.**
- Add a trusted maintenance seam that erases a closed profile whose retention deadline has passed.
- Reuse the existing erasure field-clearing and canonical erased event rather than writing a second erasure path, and record the obligation on the shared erasure fence.
  - Every obligation writes one fence row with the obligation kind, optional cause reference, retired generation, obligation time, erased revision, and the causes it superseded, so recovery reconciles one surface.
- Resolve competing obligations by earliest irreversible deadline: an earlier pending deletion wins, a later one cannot extend retention, and closure expiry never starts a fresh grace period.
- Refuse erasure while an avatar association exists, matching the deletion finalizer instead of dropping the only pointer to a provider asset.
- Mark a superseded app-only request terminal so it is never selected for processing again and never claims completion or cancellation.
- Treat a profile reactivated between selection and execution as not due, and report that as a routine skip rather than a failure.
- Report per-item outcomes with the same blocked, retryable, and corruption distinction already used by due-deletion batches.

**Justification.** The retention decision requires that a durable worker erase UMS-owned personal fields at closure expiry, and forbids treating expiry as a new deletion request with another grace period.

**References.**
- **Plan decisions** — [Lifecycle deadlines](../ums-platform/issues/05-retention-operations-and-recovery.md#lifecycle-deadlines) defines expiry behavior and the earliest-deadline rule. [Interaction with IAM global deletion](../ums-platform/issues/03-define-profile-lifecycle-boundaries.md#interaction-with-iam-global-deletion) requires independent causes to compose without extending each other.
- **Event contract** — [profile-state-event.json](../../contracts/v1/schemas/profile-state-event.json) for the erased state and event the existing finalizer already emits.
- **Repo doc** — [profile-erasure.md](profile-erasure.md) for the fence, generation retirement, and rollback guarantees this item must not fork.

**Acceptance criteria.**
- [ ] A profile past its retention deadline is erased with the same tombstone shape and canonical erased event as an app-only deletion, plus one erasure fence row carrying the obligation kind, retired generation, obligation time, erased revision, and superseded causes, all in one transaction.
- [ ] A profile before the deadline, or one reactivated after selection, is left untouched and reported as skipped.
- [ ] When several obligations exist, the earliest irrevocable deadline governs, no later cause extends it, and a superseded request reads as terminal `SUPERSEDED` with cancellation ineligible.
- [ ] An avatar association blocks expiry without mutating the profile, and a reactivation racing the deadline cannot both reopen and erase.
- [ ] Restoring a closed profile's fields is impossible afterwards: old edit receipts, onboarding retries, and status reads cannot surface the erased values.
- [ ] Automated tests cover due, not-due, reactivated-after-selection, competing-deadline, and replay paths.

**Depends on.** `W-1, W-2.`

## W-4 — Apply IAM-global deletion causes independently of app-only ones

**Summary.** IAM can delete a person's global identity, which must erase their UMS profiles. That obligation is separate from a person deleting their profile in one app, and neither may cancel or extend the other. Today the code can represent a global cause but nothing applies one.

**Status.** Integrated — see [IAM-global deletion](global-deletion.md) and the central evidence file.

**Work to be done.**
- Add trusted seams that record a global deletion as pending, cancel it, or finalize it for one app and subject, storing it as its own cause with its own source version and deadline.
- Enforce independence: removing the app-only cause leaves the global one, removing the global one leaves the app-only one, and an earlier app-only deadline survives a later global cancellation.
- Restrict ordinary use immediately when a global pending fact arrives, without starting a new 30-day window.
- Finalize immediately on an authoritative global final fact rather than waiting for a deadline, reusing the shared erasure transaction.
- Reject a late pending or cancellation that arrives after a newer global finalization, and record it as stale rather than as a new transition.
- Keep the adapter boundary explicit: the application seams accept already-trusted facts, and the authenticated provider transport is a separate item.

**Justification.** The handoff's lifecycle slice requires "IAM global deletion" with independent causes, and the lifecycle decision states that UMS never needs the global identity and that removing one cause cannot clear another.

**References.**
- **Plan decisions** — [Interaction with IAM global deletion](../ums-platform/issues/03-define-profile-lifecycle-boundaries.md#interaction-with-iam-global-deletion) is the governing rule for this item. [Retention table](../ums-platform/issues/05-retention-operations-and-recovery.md#retention-schedule) fixes the "authoritative final deletion triggers cleanup without an additional window" behavior.
- **Contract** — [binding-state.json#/properties/deletionCauses](../../contracts/v1/schemas/binding-state.json) already defines the `iamGlobal` cause shape; this item populates it.
- **Repo doc** — [profile-deletion.md](profile-deletion.md) for the existing cause and deadline invariants.

**Acceptance criteria.**
- [ ] A global pending fact restricts ordinary use immediately and records the global cause with its own source version, keeping any app-only cause intact.
- [ ] Cancelling either cause leaves the other in force, and the reported lifecycle stays restricted while any cause remains.
- [ ] An authoritative global final fact erases the profile in one transaction with the shared tombstone and fence, without adding a 30-day window.
- [ ] A global pending or cancellation that arrives after a newer finalization is rejected as stale and changes no state.
- [ ] Automated tests cover both cause orders, cancellation of each cause alone, finalization without a window, and the stale-arrival path.

**Depends on.** `W-1, W-3.`

## W-5 — Validate real IAM caller authority at the resource server

**Summary.** Every capability so far trusts a caller object that a test constructs. Real callers will present tokens, and those tokens must be checked for the right issuer, audience, app, subject, assurance, and delegation before anything is allowed. This item replaces the trust seam with real validation and defines the caller context every other surface will use.

**Status.** Implemented and unit-verified against the pinned provider fixture; see [caller authority](caller-authority.md).

**Work to be done.**
- Replace the test-only decoder path with production token validation that checks issuer, environment, audience, token kind, app binding, subject kind, assurance, security versions, and registered delegation.
  - Keep the test decoder out of the main artifact and require explicit deployment configuration; startup must still fail closed when any of it is missing.
- Define one caller context type that every handler receives, carrying app, actor, subject, purpose, assurance, and the evidence the decision was based on, so no handler reads identity out of a request body.
- Reject wrong issuer, environment, audience, type, app, subject, and stale security versions with the failure shapes the contract bundle already defines, without revealing whether a target exists.
- Add conformance tests that drive the real decoder with accepted and rejected provider fixtures rather than hand-built tokens.
- Document the exact claims the service requires so the provider side can be registered against it.

**Justification.** The handoff's verification matrix requires wrong issuer, environment, audience, type, app, actor, and subject to be denied, and the integration decision requires a UMS-audience token that preserves the originating application and subject.

**References.**
- **Plan decisions** — [Application and actor binding](../ums-platform/issues/04-design-app-scoped-integration.md#application-and-actor-binding) defines the required claim and delegation rules. [Verification matrix](../ums-platform/issues/08-define-verification-and-implementation-handoff.md#verification-matrix) defines the denial cases.
- **Contract** — [safe-error.json](../../contracts/v1/schemas/safe-error.json) for the non-enumerating failure shape, and the [actor contract](../../contracts/README.md#actor-contract) for who may call what.
- **Repo doc** — [foundation.md](foundation.md#startup-boundary) for the fail-closed configuration rule this item extends.

**Acceptance criteria.**
- [ ] A token with a wrong issuer, audience, JOSE header, profile, version, app, client, subject kind, scope, confirmation key, or timestamp ordering is denied by the resolver with a stable reason code.
- [ ] A permitted token yields one app-scoped caller context, and no handler needs to read identity out of a request body. Applying the resolver before handlers is the HTTP-surface item.
- [ ] Production startup fails when the issuer, key set, or audience is absent, and the test decoder cannot be loaded from the production artifact.
- [ ] Denials never echo a claim value or token content, so the surface can map them to a non-enumerating failure.
- [ ] Automated conformance tests load the pinned provider fixtures, including its rejected token fixtures, rather than hand-built claims only.

**Depends on.** `None.`

## W-6 — Ingest IAM lifecycle facts over an authenticated transport

**Summary.** Profiles must be created, closed, and deleted from IAM facts that actually arrive over the network, and duplicates, gaps, and out-of-order delivery must not corrupt state. Today only a local adapter call exists. This item adds the authenticated entry point and the repair behavior around it.

**Status.** Green — ingestion and snapshot repair implemented; the authenticated transport is a named gate, not built.

**Work to be done.**
- Add a private, authenticated inbound endpoint that accepts only the registered IAM event types for the registered app, and rejects unknown fields, duplicate keys, oversized bodies, and trailing content.
- Route each accepted fact into the existing inbox and adapter path so receipts, checkpoints, mappings, state, and outbox continue to commit together.
- Handle gaps and duplicates explicitly: duplicates are harmless, a gap requests reconciliation instead of provisioning, and a poisoned payload is quarantined with a safe reason rather than retried forever.
- Add authenticated snapshot repair that reconciles one app-scoped binding against authoritative provider state, and refuses to bypass erasure fences or lifecycle restrictions.
- Record delivery and processing state so operators can see oldest unprocessed input, quarantine age, and repeated failures.

**Justification.** The handoff requires a "running IAM producer and authenticated transport, bounded retry, delivery and gap-repair behavior", and the integration decision requires durable inbox processing with authenticated snapshot repair.

**References.**
- **Plan decisions** — [Commit, ordering, and repair](../ums-platform/issues/04-design-app-scoped-integration.md) governs inbox/outbox, ordering, gaps, and repair. [Slice C](../ums-platform/issues/08-define-verification-and-implementation-handoff.md#slice-c--actual-iam-and-dutch-integration) defines the exit criteria.
- **Contract** — the [internal snapshot operation](../../contracts/v1/openapi.json) for the repair surface, and the pinned IAM onboarding event schema recorded in [iam-integration.md](iam-integration.md#pinned-provider-contract).
- **Repo doc** — [iam-integration.md](iam-integration.md) for the mapping, ownership, and gating rules already implemented locally.

**Acceptance criteria.**
- [ ] A valid provider fact delivered through the trusted producer seam creates exactly one profile and one outbox event for the scoped app and subject. The authenticated transport that produces that seam is a separate gate, because IAM has published no consumer-ingestion contract.
- [ ] Duplicate delivery changes nothing, a version gap returns reconciliation instead of provisioning, and a poisoned payload is quarantined without blocking later valid facts.
- [ ] Requests with a foreign app, source, environment, schema version, or aggregate coordinate are rejected before any durable write.
- [ ] Snapshot repair advances a lagging ordering checkpoint under the application lock, records reconciled evidence, never creates or restores a profile, never clears a cause, and reports an omitted subject on a truncated page as indeterminate.
- [ ] Automated tests cover duplicate, gap, poison, wrong-app, and repair-convergence paths against disposable PostgreSQL.

**Depends on.** `W-4, W-5.`

## W-7 — Serve the app-scoped profile HTTP surface

**Summary.** The service currently exposes no business routes, so no product can use it. This item turns the already-tested internal services into the eight operations the published contract describes, with honest status codes and bounded bodies.

**Status.** Green for the user-facing surface. Internal projection/configuration routes and export remain gated, not built.

**Work to be done.**
- Implement the declared profile read and update operations, the status read, the deletion request and cancellation commands, and the restricted status read against the existing application services.
  - The internal display-read batch, policy reconciliation, and snapshot routes stay gated: they require a registered workload token profile that IAM has not published.
  - Map each operation to the caller context from the authority item, including field allowlists and purpose.
- Enforce request discipline at the edge: decoded body limits, duplicate-key rejection, unknown fields, expected version, idempotency key, and safe non-enumerating failures.
- Return `200` with a profile-required status rather than an error when a profile simply is not ready, and use the contract's accepted or conflict shapes for mutations.
- Keep restricted journeys reachable: a person with a pending deletion must still be able to read status and cancel, without reopening ordinary reads.
- Keep responses private and non-cacheable, and keep personal values out of logs and correlation metadata.

**Justification.** The handoff's integration slice requires production caller validation plus "scoped status/display routes", and the integration decision defines this exact HTTP surface with its authority and status-code rules.

**References.**
- **Plan decisions** — [HTTP surface and command semantics](../ums-platform/issues/04-design-app-scoped-integration.md#http-surface-and-command-semantics) is the governing table for these eight operations.
- **API contract** — [openapi.json#/paths](../../contracts/v1/openapi.json) for every request and response shape; [operation-accepted.json](../../contracts/v1/schemas/operation-accepted.json), [profile-status.json](../../contracts/v1/schemas/profile-status.json), and [safe-error.json](../../contracts/v1/schemas/safe-error.json) for the envelopes.
- **Repo doc** — [profile-editing.md](profile-editing.md) and [profile-deletion.md](profile-deletion.md) for the service behaviors being exposed.

**Acceptance criteria.**
- [ ] Every user-facing declared operation exists and validates its request against the published schemas, rejecting unknown fields, duplicate keys, and oversized bodies. The internal workload routes are a separate gate because no workload token profile is published.
- [ ] Recent-Strong privacy authority is derived from `acr` and `auth_time`, never accepted from the caller, and weaker evidence is denied.
- [ ] A foreign-app, wrong-subject, or wrong-purpose call is denied with the contract's non-enumerating failure, and a denied call changes no state.
- [ ] A pending-deletion caller can read restricted status and cancel before the deadline while ordinary profile reads, edits, and display reads stay blocked.
- [ ] Status returns `200` with a profile-required outcome for an incomplete profile, and mutation conflicts return the documented conflict code.
- [ ] Responses and logs contain no personal values, tokens, or cross-app references in the covered paths.

**Depends on.** `W-5`.

**Remaining gates.** Internal display-read/policy/snapshot routes need an accepted workload token profile and registration; export needs its own artifact contract and storage, tracked as W-9.

## W-8 — Publish and consume the Dutch Member-association contract

**Summary.** Dutch keeps its own member records and needs to know which UMS profile belongs to which member. No such contract exists, so the integration cannot be built. This item establishes it with Dutch as the owning party and connects the two sides without moving any Dutch rules into UMS.

**Status.** Planned — not started.

**Work to be done.**
- Agree with the Dutch owner on the association record, its keys, its lifecycle, and who publishes what; write it down as a versioned contract with fixtures before writing client code.
- Implement the UMS side as a client that reports the minimal app-scoped facts Dutch needs to associate a member, and that never sends display values or global identifiers.
- Implement the Dutch side as a reference consumer in the local journey harness, keeping placeholder, claim, and Ledger rules untouched and owned by Dutch.
- Handle the association's failure modes: an unavailable Dutch service does not roll back a profile, a duplicate report is harmless, and an association for an erased profile is refused.
- Record the reconciliation path for a member that exists without a profile and a profile that exists without a member.

**Justification.** The handoff's integration slice requires integrating "the Dutch-owned Member mapping seam", and the integration decision states that Dutch owns its Member, placeholder, claim, and Ledger authority.

**References.**
- **Plan decisions** — [Slice C](../ums-platform/issues/08-define-verification-and-implementation-handoff.md#slice-c--actual-iam-and-dutch-integration) requires the real first consumer journey with named acceptance. [Event families and privacy](../ums-platform/issues/04-design-app-scoped-integration.md#event-families-and-privacy) governs what may cross the boundary.
- **Contract** — [profile-state-event.json](../../contracts/v1/schemas/profile-state-event.json) for the facts already published, and the [actor contract](../../contracts/README.md#actor-contract) for the projection workload.
- **Repo doc** — [dutch-consumer-journey.md](dutch-consumer-journey.md) for the simulated association this item replaces with a real contract.

**Acceptance criteria.**
- [ ] A versioned association contract exists with positive and negative fixtures, and both sides validate against the same fixtures.
- [ ] A profile that becomes ready produces exactly one association report carrying only app-scoped references and permitted lifecycle facts.
- [ ] An unavailable Dutch consumer leaves the profile unchanged and the report retryable; a duplicate report creates no second association.
- [ ] An erased profile cannot be associated, and a member without a profile is reported as unreconciled rather than auto-created.
- [ ] No Dutch placeholder, claim, canonicalization, or Ledger rule appears in the UMS implementation.

**Depends on.** `W-7.`

## W-9 — Export a person's profile data with protected, expiring download

**Summary.** A person is entitled to a copy of the data UMS holds about them for one app. That copy must not become a permanent second store of personal data, and it must disappear if the profile is deleted while it exists. This item adds export generation, a short-lived download, and the invalidation rules around them.

**Status.** Green — implemented and verified; awaiting review.

**Work to be done.**
- Define and publish the export contract before implementing it: the artifact states, the 24-hour artifact lifetime, and the single-use download capability of at most five minutes.
- Generate an export containing only the requester's own app-scoped UMS-owned fields and releasable lifecycle facts, produced under recent-strong authority and recorded in the audit trail.
- Protect the artifact at rest and hand out a download capability that is scoped, time-bounded, and consumed on first successful use.
- Invalidate outstanding exports when the profile is erased or its authority is withdrawn, and refuse to publish a new artifact once erasure has committed.
- Clean up expired, failed, and partial artifacts within 24 hours and report cleanup failures rather than hiding them.

**Justification.** The handoff's export slice requires "fenced export jobs/download authority, 24-hour artifact expiry", and the retention decision fixes the artifact and download lifetimes.

**References.**
- **Plan decisions** — [Slice E](../ums-platform/issues/08-define-verification-and-implementation-handoff.md#slice-e--exports-notices-and-content-integration) defines the export slice. [Export and administration](../ums-platform/issues/03-define-profile-lifecycle-boundaries.md#export-and-administration) bounds what may be exported. [Retention table](../ums-platform/issues/05-retention-operations-and-recovery.md#retention-schedule) fixes the 24-hour artifact and 5-minute download limits.
- **API contract** — the [export operation](../../contracts/v1/openapi.json) declares the surface; the contract bundle deliberately omits a download URL until this item publishes one, so this item authors the schema.
- **Repo doc** — [profile-erasure.md](profile-erasure.md) for the erasure fence an export must respect.

**Acceptance criteria.**
- [ ] The published export contract defines `exportReference`, `scope`, `requestReference`, `state` as one of `REQUESTED`, `READY`, `EXPIRED`, `INVALIDATED`, `FAILED`, `generatedAt`, `expiresAt` set to 24 hours after generation, and a `download` object carrying a capability reference and its own expiry of at most 5 minutes.
- [ ] An export contains only the requesting app's UMS-owned fields and releasable lifecycle facts, with no cross-app reference, token, or product business data.
- [ ] The download capability works once and is refused with the contract's safe failure after expiry, after use, after invalidation, or after erasure.
- [ ] Erasing a profile invalidates its outstanding exports before any new artifact can be published.
- [ ] Automated tests cover the expiry boundary, single-use consumption, invalidation by erasure, and refusal to generate after erasure.

**Depends on.** `W-5, W-7.`

## W-10 — Schedule advance notices before closure expiry

**Summary.** Erasing a retained profile without warning would be unfair, so the person must be told twice before it happens. UMS does not hold contact details, so it can only publish a minimal notice intent that the product turns into a real message. This item adds those intents and tracks what happened to them.

**Status.** Integrated — see [retention notices](notices.md) and the central evidence file.

**Work to be done.**
- Define and publish the notice-intent contract before implementing it: the notice classes, an identity bound to the closure instance, an incrementing notice revision, and the delivery states the product reports back.
- Publish one minimized fact per state change so the owner can see a withdrawal and stop a queued delivery.
- Create intents at closure, or on observing a late closure, for the 14-day and 7-day points before the retention deadline.
  - Skip a notice whose window has already passed instead of shifting the window, and never invent a fresh 90-day period.
- Track queued, handed off, acknowledged, failed, suppressed, and window-passed states separately, with retries using the same intent identity and a failure timestamp that never overwrites the handoff time.
- Record a late delivery after suppression instead of turning it into a permanent adapter error.
- Suppress remaining intents when the retention obligation is cancelled by reactivation, superseded by an earlier deletion obligation, or met by erasure.
- Expose missed notice windows to operators as a visible failure rather than treating silence as success.

**Justification.** The retention decision requires notice intents at 14 and 7 days before expiry, with tracked delivery states and an explicit rule that late closure never invents a fresh period.

**References.**
- **Plan decisions** — [Notice delivery boundary](../ums-platform/issues/05-retention-operations-and-recovery.md#notice-delivery-boundary) forbids UMS from acquiring contact details and defines the intent handoff. [Lifecycle deadlines](../ums-platform/issues/05-retention-operations-and-recovery.md#lifecycle-deadlines) fixes the 14 and 7 day points.
- **Contract** — this item authors the notice-intent schema; no notice contract exists in the bundle today.
- **Repo doc** — [work-items.md](work-items.md) W-1 for the closure deadline these notices read.

**Acceptance criteria.**
- [ ] The published notice contract defines `noticeReference`, `scope`, `noticeClass` as one of `CLOSURE_EXPIRY_14_DAY`, `CLOSURE_EXPIRY_7_DAY`, `deadline`, a `dedupeIdentity` bound to the closure instance, an incrementing `noticeRevision`, and `state` as one of `QUEUED`, `HANDED_OFF`, `ACKNOWLEDGED`, `FAILED`, `SUPPRESSED`, `WINDOW_PASSED`.
- [ ] The same closure never produces a duplicate intent for the same notice class, including after process restart or repeated reconciliation.
- [ ] A closure observed after a notice window has started records a terminal window-passed intent and publishes it, without moving the retention deadline.
- [ ] Reactivation, an earlier deletion obligation, and erasure each suppress outstanding intents with a published withdrawal fact, and a failed handoff is reported as failed rather than acknowledged.
- [ ] Cancelling a deletion that still leaves a retained profile re-arms the notices whose windows have not passed, so erasure never happens after a withdrawal with no replacement notice.
- [ ] Automated tests cover both windows, late closure, duplicate reconciliation, a re-closure at the same authoritative time, suppression by each cause, late acknowledgement, the window-start boundary, and failure reporting.

**Depends on.** `W-1.`

## W-11 — Manage avatar references through an accepted content contract

**Summary.** An avatar is currently just a stored reference, because no content provider has agreed how attachments are created, revoked, or cleaned up. Until that exists, a person cannot attach one and erasure cannot honestly claim the image is gone. This item closes that gap with the content owner.

**Status.** Planned — not started.

**Work to be done.**
- Agree with the content owner on reference format, attach and revoke semantics, ownership verification, cleanup confirmation, and what UMS may claim.
- Replace the current unconditional rejection with validated attachment under that contract, keeping explicit clearing supported.
- Revoke the association when a field is cleared, a profile is erased, or a reference becomes invalid, and track the provider's cleanup result separately from local erasure.
- Keep erasure honest: an erasure with an outstanding provider obligation reports the obligation rather than claiming the image was deleted.
- Add the revocation repair path for a reference that existed before the contract or was left behind by a failed cleanup.
- Provide the completion path for a blocked authoritative final: a global finalization that arrives while an association exists records blocked evidence and must be finishable once cleanup is confirmed.

**Justification.** The handoff gates avatar enablement on an accepted content authority and cleanup contract, and the lifecycle decision forbids deleting a shared content blob without verified ownership.

**References.**
- **Plan decisions** — [Avatar enablement gate](../ums-platform/issues/08-define-verification-and-implementation-handoff.md#release-gates) conditions the capability on an accepted provider contract. [Erasure, assets, projections, and backups](../ums-platform/issues/03-define-profile-lifecycle-boundaries.md#erasure-assets-projections-and-backups) governs ownership and cleanup claims.
- **Contract** — [profile-patch.json](../../contracts/v1/schemas/profile-patch.json) fixes the wire form of an avatar reference; the provider contract is authored here.
- **Repo doc** — [profile-editing.md](profile-editing.md) for the current rejection behavior being replaced.

**Acceptance criteria.**
- [ ] A versioned content contract exists covering reference format, attach, revoke, ownership check, and cleanup confirmation, with fixtures both sides validate.
- [ ] A permitted attachment is stored only after the reference passes the contract's validation, and an explicit clear removes the association.
- [ ] Erasure with an outstanding provider obligation reports the obligation instead of claiming the content was deleted, while local fields are still erased.
- [ ] Cleanup failures are retryable and visible, and a reference owned by another scope is never revoked.
- [ ] Automated tests cover attachment validation, clearing, erasure with and without an outstanding obligation, and the retry path.

**Depends on.** `W-3, W-7.`

## W-12 — Publish outbox facts through a retrying relay

**Summary.** The service records what happened in an outbox, but nothing ever sends it, so no consumer has ever received a real event. This item adds the sender, including what happens when a consumer is down for a long time. It must deliver at least once without inventing exactly-once claims.

**Status.** Green — implemented and verified; awaiting review.

**Work to be done.**
- Add a relay that claims unpublished outbox rows, publishes them in binding-revision order per binding, and marks them published only after the transport accepts them.
- Retry with bounded backoff and a quarantine state for repeatedly failing or malformed rows, with a safe failure class rather than an exception message.
- Keep delivery at least once: a crash after publish and before marking must produce a harmless duplicate rather than a lost fact.
- Expose oldest unpublished age, retry counts, and quarantine age as operational signals.
- Provide a repair path that re-drives quarantined rows once the cause is fixed, without changing their identity.

**Justification.** The handoff's recovery slice requires a "monitored relay", and the integration decision requires broker-independent at-least-once delivery with durable outbox state and visible quarantine.

**References.**
- **Plan decisions** — [Commit, ordering, and repair](../ums-platform/issues/04-design-app-scoped-integration.md) defines outbox ordering, duplicates, and quarantine. [Monitoring and ownership](../ums-platform/issues/05-retention-operations-and-recovery.md#monitoring-security-and-ownership) defines the signals. [Retention table](../ums-platform/issues/05-retention-operations-and-recovery.md#retention-schedule) fixes 7-day published-payload retention.
- **Event contract** — [profile-state-event.json](../../contracts/v1/schemas/profile-state-event.json) is the payload the relay carries.
- **Repo doc** — [foundation.md](foundation.md#database-harness) for the outbox tables the relay must drain.

**Acceptance criteria.**
- [ ] Every unpublished outbox fact is eventually published exactly once in effect, and a simulated crash after publish produces a duplicate the consumer can discard rather than a missing fact.
- [ ] Facts for one binding are published in binding-revision order, and a stuck binding does not block other bindings.
- [ ] A row failing repeatedly moves to quarantine with a safe failure class and stops consuming retry capacity; re-driving it after repair succeeds without changing its identity.
- [ ] Oldest-unpublished age, retry count, and quarantine age are readable by operators.
- [ ] Automated tests cover retry, backoff, crash-after-publish, quarantine, and repair paths.

**Depends on.** `None.`

## W-13 — Enforce the declared retention schedule

**Summary.** Every record the service keeps has a stated lifetime, but nothing currently deletes anything on time. That turns a disclosed retention promise into indefinite storage. This item adds the sweeps and the safeguards that keep erasure evidence while removing everything else.

**Status.** Green — implemented and verified; awaiting review.

**Work to be done.**
- Implement sweeps for the records with a declared lifetime: command receipts, published outbox and processed inbox payloads, export artifacts, and failed or partial exports.
- Leave erasure fences and minimal lifecycle audit in place, and refuse to sweep a record that is still needed to reject historical traffic.
- Delete in bounded batches with observable counts, and never let one failing record block the rest of a sweep.
- Report swept, retained, and failing counts separately so an operator can tell the difference between "nothing to do" and "the sweep is broken".
- Document each sweep against the retention table so a change to a period has one place to change.

**Justification.** The retention decision publishes an explicit schedule for each record class and requires retention sweeps to be proven, including that a fence is not deleted merely because a table lifetime elapsed.

**References.**
- **Plan decisions** — [Retention schedule](../ums-platform/issues/05-retention-operations-and-recovery.md#retention-schedule) is the governing table. [Acceptance and upgrade triggers](../ums-platform/issues/05-retention-operations-and-recovery.md#acceptance-and-upgrade-triggers) requires sweeps and fence retirement to be proven.
- **Repo doc** — [profile-erasure.md](profile-erasure.md) for the fence rows a sweep must preserve.

**Acceptance criteria.**
- [ ] Receipts older than 7 days, published outbox and processed inbox payloads older than 7 days, and exports or partial artifacts older than their declared lifetime are removed, while newer records are untouched.
- [ ] Erasure fences and minimal lifecycle audit rows survive any sweep, including when their associated table has no other rows.
- [ ] A record still needed to reject historical onboarding or replay traffic is retained and reported as retained rather than deleted.
- [ ] A failing record does not prevent the sweep from processing the rest, and swept, retained, and failed counts are reported separately.
- [ ] Automated tests cover each period boundary, fence preservation, and the partial-failure path.

**Depends on.** `W-9, W-12.`

## W-14 — Prove erasure survives restoring a backup

**Summary.** If a backup taken before a deletion is restored, the deleted data must not come back. That is the hardest promise in the design and it cannot be met by the database alone. This item establishes independently durable deletion evidence and proves it with an actual restore.

**Status.** Planned — not started.

**Work to be done.**
- Decide and implement how acknowledged lifecycle transitions survive a restore, through an independently durable minimized journal or an equivalent mechanism the chosen infrastructure can actually provide.
- Reconcile restored state against that evidence before any read or provisioning is served, and keep affected scopes restricted when the evidence is missing.
- Encrypt backups, restrict access, retain seven rolling days, and surface backup age and failure.
- Run an isolated restore drill and record backup age, data loss, restored fences, migration result, checkpoint convergence, and actual recovery time.
- Write the restore runbook so an operator can repeat the drill without reading code.

**Justification.** The handoff's privacy-capable deployment gate requires "independent recovery of acknowledged local privacy transitions plus erased-data-preserving restore evidence", and the retention decision requires a demonstrated restore that keeps an erased profile erased.

**References.**
- **Plan decisions** — [Initial operating tier and recovery](../ums-platform/issues/05-retention-operations-and-recovery.md#initial-operating-tier-and-recovery) governs recovery evidence and forbids weakening the no-resurrection invariant. [Privacy-capable deployment gate](../ums-platform/issues/08-define-verification-and-implementation-handoff.md#release-gates) is the blocking gate.
- **Contract** — [binding-state.json](../../contracts/v1/schemas/binding-state.json) for the tombstone shape a restore must reproduce.
- **Repo doc** — [profile-erasure.md](profile-erasure.md) for the fence that makes replay refusal durable.

**Acceptance criteria.**
- [ ] A backup taken before an app-only deletion is restored, and after reconciliation the affected profile serves no display fields and refuses reads and edits.
- [ ] Restoring without available deletion evidence leaves the affected scopes restricted instead of serving pre-deletion data, and this is reported rather than silently degraded.
- [ ] Backup age, failure, and restore results are recorded, and a backup older than 26 hours raises an alert.
- [ ] The recorded drill names backup age, lost changes, restored fences, migration result, checkpoint convergence, and elapsed recovery time.
- [ ] The restore runbook is sufficient for a second person to repeat the drill.

**Depends on.** `W-3, W-4, W-12.`

## W-15 — Run maintenance work on a schedule with alerts and runbooks

**Summary.** Erasure, notices, retries, and sweeps all exist as code that somebody must call by hand. In a real deployment they need to run on their own and shout when they fall behind. This item adds the scheduling, the alerts, and the procedures an operator follows.

**Status.** Planned — not started.

**Work to be done.**
- Add worker modes that run the finalizers, notice scheduling, relay, sweeps, and backup checks on a defined cadence, with single-flight behavior so two workers do not duplicate work.
- Emit the alerts the design requires: erasure work past its deadline, work exceeding the 24-hour objective, a missed daily backup, a poisoned event, repeated authorization or configuration failure, and failed privacy or export cleanup.
- Keep alert content safe: app and reference identifiers and failure classes only, never personal values, tokens, or download links.
- Write the runbooks named by the design for the failure scenarios operators will actually face.
- Document the deployment's limited operating tier, including that a single operator is an accepted limitation.

**Justification.** The retention decision requires monitored erasure deadlines, notice delivery, backup age, and a named set of runbooks before the service can be operated.

**References.**
- **Plan decisions** — [Monitoring and ownership](../ums-platform/issues/05-retention-operations-and-recovery.md#monitoring-security-and-ownership) names the signals, thresholds, and runbooks. [Initial operating tier and recovery](../ums-platform/issues/05-retention-operations-and-recovery.md#initial-operating-tier-and-recovery) defines the accepted best-effort tier.
- **Repo doc** — [deletion-batch.md](deletion-batch.md) for the batch contract a scheduled worker drives.

**Acceptance criteria.**
- [ ] Finalization, notice scheduling, relay, and retention sweeps run on a schedule without manual invocation, and two concurrent workers do not produce duplicate transitions or events.
- [ ] Each required alert fires at its stated threshold, and alert content contains no personal values, tokens, or download links.
- [ ] Runbooks exist for database or backup loss, profile data exposure, compromised workload, event-gap repair, poison event, stuck provisioning, overdue erasure, failed notice, content revocation, and safe restore.
- [ ] A deliberately delayed worker produces an alert within the stated window rather than silently missing its objective.
- [ ] Automated tests cover single-flight behavior and each alert threshold.

**Depends on.** `W-3, W-10, W-12, W-13.`

## W-16 — Prove the security and privacy boundaries under attack

**Summary.** The design forbids cross-app access and personal data in the wrong places, but nobody has tried to break it. This item attacks the service on purpose and records what held and what did not. It also runs the dependency and secret checks a release needs.

**Status.** Planned — not started.

**Work to be done.**
- Attempt the documented attacks: foreign app, wrong subject, replayed token, stale security version, forged delegation, cross-app listing, hidden-target enumeration, and access after erasure.
- Scan stored events, logs, receipts, audit rows, and error responses for personal values, tokens, and cross-app references, and fix what is found.
- Run dependency and secret scanning, record findings, and resolve critical and high results.
- Verify rate limits and concurrency limits protect privacy operations from bulk reads, including when the limits themselves are degraded.
- Record what was attempted, what held, and what changed, so the release gate can cite evidence instead of intent.

**Justification.** The handoff's release gate requires "authorization/adversarial privacy tests", "dependency/secret review", and no unresolved critical or high findings.

**References.**
- **Plan decisions** — [Verification matrix](../ums-platform/issues/08-define-verification-and-implementation-handoff.md#verification-matrix) lists the required denial and privacy evidence. [Monitoring and ownership](../ums-platform/issues/05-retention-operations-and-recovery.md#monitoring-security-and-ownership) requires scanning and bounded limits.
- **Contract** — [Actor contract](../../contracts/README.md#actor-contract) for the allowed and excluded actions being attacked.
- **Repo doc** — [work-items.md](work-items.md) W-5 and W-7 for the authority and surface under test.

**Acceptance criteria.**
- [ ] Each documented attack is attempted as an automated test and denied, with no state change on the denied path.
- [ ] A scan of events, logs, receipts, audit rows, and error responses finds no personal values, tokens, or cross-app references in the covered paths.
- [ ] Dependency and secret scanning runs in the build and reports no unresolved critical or high finding.
- [ ] Bulk display reads cannot starve privacy operations under the stated limits, and degraded limit infrastructure still enforces a bounded rate.
- [ ] A written evidence record names each attempt, the result, and any fix applied.

**Depends on.** `W-7, W-9, W-11.`

## W-17 — Rehearse the schema upgrade and rollback boundaries

**Summary.** The service will be upgraded while it holds real data, and a bad upgrade must not lose or resurrect anything. This item proves the migrations work forward from every earlier state and records what a rollback cannot do.

**Status.** Planned — not started.

**Work to be done.**
- Test a forward migration from each previously shipped schema state to the current one, including from a database that carries erased profiles and open deletion requests.
- Prove an interrupted migration leaves the database in a defined state and can be resumed rather than silently half-applied.
- Record that rolling back the application cannot restore erased fields, cancel an erasure, or move a security or lifecycle version backwards.
- Rehearse the upgrade against a restored backup as part of the same drill.
- Document the upgrade and rollback procedure for an operator.

**Justification.** The handoff's local-build gate requires migrations and "migration rehearsal", and the retention decision states that a rollback cannot restore erased fields or roll back security and lifecycle versions.

**References.**
- **Plan decisions** — [Release gates](../ums-platform/issues/08-define-verification-and-implementation-handoff.md#release-gates) and [Monitoring and ownership](../ums-platform/issues/05-retention-operations-and-recovery.md#monitoring-security-and-ownership) require staged migration evidence.
- **Repo doc** — [foundation.md](foundation.md#database-harness) for the migration and integration-test harness this item extends.

**Acceptance criteria.**
- [ ] Every previously shipped schema state upgrades to the current schema, and the upgraded database passes the full integration suite.
- [ ] An upgrade over a database containing an erased profile and a pending deletion request preserves the tombstone, fence, cause, and deadline.
- [ ] An interrupted migration is detectable and resumable, and cannot leave a state that serves restricted data as ordinary data.
- [ ] The written upgrade procedure states plainly that application rollback cannot restore erased fields or reverse lifecycle versions.
- [ ] Automated tests cover the upgrade paths and the interruption case.

**Depends on.** `W-1, W-4, W-9, W-10, W-13, W-14.`

## W-18 — Prove the real Dutch consumer journey end to end

**Summary.** Everything so far has been proven locally, with a simulated product. The release claim only becomes real when a person can enter through IAM, get a profile, be associated as a Dutch member, and be correctly refused a Ledger action without the right permission. This item produces that evidence with the owning teams.

**Status.** Planned — not started.

**Work to be done.**
- Run the journey against real services: IAM entry and activation, UMS profile creation and required fields, Dutch member association, and a product shell gated on readiness.
- Exercise the failure modes the design promises to survive: delayed and unavailable providers, duplicate and reordered events, a wrong-authority caller, and a profile that becomes required mid-journey.
- Confirm that readiness alone never grants a Ledger permission, and that a denied domain action is denied by Dutch rather than by UMS.
- Collect named acceptance from the IAM and Dutch owners for the flows exercised, including any flow they decline to accept.
- Record the result as evidence with dated runs, and keep any unaccepted flow listed as an open gate rather than a completed capability.

**Justification.** The handoff's final verification row requires a "real consumer journey" with named acceptance, and its release gates require all enabled capabilities to be evidenced before launch.

**References.**
- **Plan decisions** — [Real consumer journey](../ums-platform/issues/08-define-verification-and-implementation-handoff.md#verification-matrix) defines the required evidence. [Slice C exit](../ums-platform/issues/08-define-verification-and-implementation-handoff.md#slice-c--actual-iam-and-dutch-integration) defines the delay, outage, duplicate, reorder, wrong-authority, and recovery scenarios.
- **Repo doc** — [dutch-consumer-journey.md](dutch-consumer-journey.md) for the simulated journey this item replaces with real services.
- **Contract** — [profile-status.json](../../contracts/v1/schemas/profile-status.json) and [profile-state-event.json](../../contracts/v1/schemas/profile-state-event.json) for the facts the journey observes.

**Acceptance criteria.**
- [ ] The real journey completes from IAM entry through a profile-required product state to a Dutch member association, with each step's evidence recorded and dated.
- [ ] Each listed failure mode is exercised against real services and leaves the system in the documented restricted or recoverable state.
- [ ] A ready profile without the Dutch domain permission is refused the Ledger action, and the refusal is shown to come from Dutch.
- [ ] The IAM and Dutch owners give named acceptance for the exercised flows, and any declined flow is recorded as an open gate.
- [ ] The evidence names the date, environment, and the reason any scenario could not be exercised.

**Depends on.** `W-8, W-15, W-16, W-17.`

## Cut for Scope

This is work the work items exclude, not work deferred for lack of evidence. There is no trigger that reopens an entry here; the recorded boundary already settled it.

- **Choosing and provisioning cloud topology.** The handoff keeps deployment as a separate effort and names topology and cost as its deliverables, so no work item decides a provider, region, or cost envelope; W-14 records only the durability capability the deployment must supply.
- **Moving Dutch placeholder, claim, canonicalization, or Ledger rules into UMS.** The design map states these stay Dutch-owned until proven reusable, so W-8 builds an association contract and nothing else.
- **Building product screens for the deletion, closure, or profile-required journeys.** The integration decision assigns UI hosting and product screens to the consuming application, so W-18 exercises the journeys through the real product rather than building them.
- **Migrating or erasing the retired prototype's stored data.** The handoff requires a separately reviewed ownership and identifier reconciliation before any destructive change to populated data, and no live population has been established, so no work item touches it.
- **Any availability, recovery-time, or throughput guarantee.** The accepted tier is best effort with no published availability percentage, so W-14 and W-15 record measured limits instead of promising them.
