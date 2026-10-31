# Scope boundary — UMS remaining implementation slices

## Recorded scope

Breakdown and build of the remaining work in the UMS implementation handoff,
[Define verification and implementation handoff](../ums-platform/issues/08-define-verification-and-implementation-handoff.md),
starting from the local state on 31 October 2026: Foundation and Slices A, B,
and D1–D3 are implemented and verified; Slice C exists as local preparation
only. The work items cover what remains of Slice D (closure, reactivation,
closure expiry, IAM-global deletion causes) and all of Slices C, E, F, and G.

The user stated the target at invocation as "all remaining slices, D through G",
in the handoff's dependency order, with the first implementable item built in the
same pass.

The following are inside the boundary, because the handoff lists them as the
build sequence for these slices:

- Local lifecycle behavior for closure, reactivation, closure expiry, and
  independent IAM-global deletion causes.
- Real IAM authority validation, authenticated event ingestion, and the
  app-scoped HTTP surface.
- The Dutch Member-association seam, exports, notice intents, and avatar/content
  lifecycle.
- Outbox relay, retention enforcement, scheduled workers, restore evidence,
  adversarial security/privacy evidence, migration rehearsal, and real consumer
  journey evidence.

## Stated exclusions

- No change to the settled requirements and service design in
  `docs/ums-platform/`. Those documents are read-only input.
- No product business data, domain authorization, memberships, or Dutch
  placeholder/claim/canonicalization rules move into UMS.
- No cross-app profile listing, correlation, or global Human Identity exposure.
- No claim of provider acceptance, deployment readiness, uptime, recovery-time,
  or cost evidence. Those stay release gates per the handoff.
- Deployment topology selection and cloud provider choice are not decided here;
  the items that need them record the dependency instead of inventing a choice.

## Direction of travel

Answered at invocation: build toward the complete handoff, in dependency order,
D first. The user also chose to protect the existing work by committing it as
per-slice commits before new work begins, which was done as
`chore: replace prototype…` through `feat(privacy): bounded due-deletion
batches`.

Deprecations recorded: the retired Mongo/WebFlux prototype and its raw-identifier
CRUD/list-all routes are not compatibility obligations. The placeholder
`deletionCauses` model was already replaced by typed causes and is not restored.

## Visual Material Received

None received.

## Record provenance

Created by this run on 31 October 2026. No inherited boundary record existed at
`docs/development/artifacts/scope-boundary.md` or beside the source handoff. The
effort has no user interface of its own: UMS exposes APIs and events, and
product screens belong to the consuming applications.
