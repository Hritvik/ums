Label: wayfinder:map

# Specify the shared User Management Service

## Destination

A complete requirements and service-design specification for standalone UMS serving the product corpus, with consumer-traced requirements, ownership boundaries, profile contracts, lifecycle/privacy behavior, integration, operations, verification, and an implementation handoff.

## Notes

- Planning only; implementation and deployment require a separate effort.
- User-confirmed direction: one independent shared service, app-isolated profiles, optional adoption. IAM authentication alone does not require a UMS profile.
- Purpose: reusable product profile onboarding, management, readiness, and lifecycle. UMS does not own product business data or domain authorization.
- User accepted UMS ownership of display names, avatar references, locale/timezone, and profile readiness, with explicit consumer-driven schemas. Products retain domain settings and memberships.
- Dutch placeholders, claim offers, and member canonicalization remain Dutch-owned initially. Do not move those rules into shared UMS without proven reusable demand.
- IAM owns Human Identities, app-scoped subjects/activations, verified identifiers, credentials/factors, authentication/recovery, sessions/tokens, and workload/delegation authority. Active App Activation and ready profile are separate states.
- Preserve IAM's app-subject privacy boundary. Do not expose a global Human Identity ID, correlate profiles across products, forward wrong-audience tokens, or introduce automatic profile synchronization.
- Ask the human only when viable alternatives have meaningful product, privacy, cost, or operating consequences. Resolve routine consequences of settled requirements autonomously and record rationale.
- Use Wayfinder, Grilling, and Domain Modeling. Charting records the frontier; later sessions resolve one non-research ticket at a time.
- Local Markdown tracker: this map and its child issue files are canonical for this effort.
- Current standalone CRUD implementation is evidence, not the target specification. Existing Dutch UMS and portfolio exclusions are historical inputs requiring explicit reconciliation.
- Kotlin learning is an explicit user goal. Prefer idiomatic, understandable Kotlin and incremental explanations; avoid unnecessary reactive/concurrency complexity.

## Decisions so far

<!-- Resolved ticket links go here. User-confirmed starting constraints are in Notes. -->

- [Establish consumer requirements and adoption boundaries](issues/01-establish-consumer-requirements.md) — Dutch provides concrete requirements; other products remain optional candidates, with domain ownership and IAM/Dutch reconciliation explicit in the central capability contract.
- [Define profile identity, schema, readiness, and authority](issues/02-define-profile-contract-and-authority.md) — App-isolated typed profiles provision automatically on eligible entry; completion is optional by default, required fields are product-declared, and readiness grants no authentication or domain authority.
- [Define profile closure, reactivation, and privacy boundaries](issues/03-define-profile-lifecycle-boundaries.md) — App-only deletion blocks use immediately with a 30-day cancellation period; IAM global deletion remains authoritative, independent restrictions compose, and erasure never promises deletion of product-owned evidence.
- [Design app-scoped integration and recovery contracts](issues/04-design-app-scoped-integration.md) — UMS-scoped HTTP authority and minimized app-routed events use independent source checkpoints, durable inbox/outbox, tombstones, and authenticated repair; IAM, Dutch, and content-provider reconciliation remain explicit.
- [Define retention, operations, and recovery guarantees](issues/05-define-retention-operations-and-recovery.md) — Closed profiles expire after 90 days with advance notices; initial uptime is best effort, with bounded retention, encrypted backups, restore checks, and independently recoverable deletion evidence required before release.
- [Select persistence and implementation technology](issues/06-select-persistence-and-technology.md) — Kotlin/JVM, Spring Boot MVC, PostgreSQL, explicit JDBC, Flyway, and local transactional inbox/outbox form one modular service; independent privacy recovery remains a deployment prerequisite.
- [Complete contract boundaries and provider reconciliation](issues/07-complete-contract-and-provider-reconciliation.md) — Field limits and actor permissions are fixed; one canonical binding-state event replaces competing change streams, and each provider dependency has an explicit capability gate.
- [Define verification and implementation handoff](issues/08-define-verification-and-implementation-handoff.md) — Contract-first Kotlin vertical slices, behavioral/security/recovery evidence, prototype retirement, and per-capability release gates define the build handoff without claiming implementation or provider readiness.

## Not yet specified

None for the provider-neutral service specification. Concrete provider acceptance and deployment evidence are tracked as handoff/release gates, not represented as completed work.

## Out of scope

- Implementing, deploying, or migrating services during this Wayfinder.
- Authentication, identifier verification, credentials, sessions, token issuance, and IAM activation authority.
- Product memberships, roles, balances, business settings, and domain authorization.
- Dutch-specific placeholder/claim/canonicalization rules.
- Automatic cross-product profile synchronization or a shared global profile.
- Selecting/provisioning concrete cloud topology and proving lifecycle-journal durability, actual operating cost, and achieved recovery. These belong to the deployment effort under the operational acceptance constraints and gates defined here.

## Evidence pointers

- [IAM boundary and vocabulary](../../../iam/docs/identity-platform/IAM-CONTEXT.md) — use the dedicated IAM repository; links to sibling repositories must be verified before handoff.
- IAM canonical decisions: `~/repositories/iam/docs/identity-platform/wayfinder/issues/01-define-identity-trust-and-privacy-boundaries.md`, `10-design-lifecycle-privacy-and-administration.md`, `12-define-apis-events-and-app-integration.md`, and `15-define-implementation-handoff.md` in that directory.
- Portfolio expectation source: `~/Documents/ChatGPT/Distracted Dev Micro Service Portfolio/capabilities/efforts/dutch/slice-1-identity-member-onboarding.yaml`.
- Dutch integration source: `~/repositories/dutch/docs/architecture/identity-membership-integration.md`.
- Midas/Jarvis boundary decisions in the portfolio capability home currently exclude Dutch-specific UMS. Treat these as historical boundaries, not accepted shared-UMS requirements.
- No consolidated shared-UMS consumer requirements list was found during initial corpus inspection. Do not relabel IAM consumer requirements as UMS commitments.
