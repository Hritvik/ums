Type: grilling
Status: resolved
Assignee: Codex
Parent: [Specify the shared User Management Service](../map.md)
Blocked by: 01

# Define profile identity, schema, readiness, and authority

## Question

What app-scoped profile identifier/mapping, typed fields, supported customization, mutation authority, and readiness contract satisfy the evidenced consumers while keeping IAM identity and product-domain ownership intact? Define the integration seam for Dutch-owned Member and claim behavior without importing its domain into shared UMS.

## Working design constraints

- One profile per issuer/environment, App Registration, and App Subject; no globally shared profile identity or cross-app search. UMS may issue a profile reference scoped to that app, without changing IAM subject semantics.
- Product-owned Member IDs and claims reference the shared profile through a Dutch-owned mapping; UMS does not become the Member canonicalization authority.
- Profile writes require authority for the specific application and target; knowing a profile ID does not grant access. Self-service and authorized product workloads use explicit field permissions, expected versions, and idempotent commands. No arbitrary overwrite or generic impersonation.
- Initial supported profile fields are display name, avatar reference, locale, and timezone. No raw verified contact identifiers, credentials, health/finance fields, or arbitrary product JSON. Exact validation and defaults follow resolved onboarding policy.
- Avatar bytes belong to a content service; references must not make private images publicly accessible.
- Display profile changes are distinct from lifecycle/readiness facts. PII-free events can invalidate a projection; authorized scoped reads retrieve display fields. Do not place display names or avatar URLs into events claimed to be PII-free.
- Profile readiness cannot establish IAM activation or product authorization. Product-specific requirements must be explicit and versioned; business onboarding remains product-owned.

## Answer

The user accepted automatic provisioning on first eligible entry into a UMS-enabled app, and optional completion by default with explicit product-required fields. This does not provision profiles for unrelated apps or make UMS mandatory for all IAM consumers.

### Identity and supported fields

- Enforce exactly one profile per issuer/environment, App Registration, and App Subject under concurrent/repeated provisioning. An app-scoped opaque Profile Reference is stable and never reused; it does not replace IAM subject or Dutch Member identity.
- The initial schema supports display name, avatar reference, display locale, and display timezone. Each field may be absent under the default policy. No arbitrary extension bag is supported initially; new fields need explicit semantics, authority, privacy classification, and a consumer use case.
- Display name is non-unique presentation text, never identity proof or a routing handle. Missing names use a neutral app-local generated fallback without revealing email, phone, global IDs, or another product's values. The fallback does not satisfy a product requirement for deliberate name entry.
- Validate names as bounded Unicode text with control-character restrictions; render as text, never trusted HTML. Exact wire-level limits belong to API/schema design.
- Locale uses supported language-tag semantics and timezone uses supported IANA identifiers. These are display preferences. Midas accounting timezone, Jarvis travel/scheduling policy, and household day boundaries retain product authority.
- Avatar Reference names a separately authorized content asset, not an unrestricted fetch URL. UMS stores no image bytes. Upload, scanning, visibility, replacement, and erasure must compose with the content owner; no private URL or personal field enters a supposedly PII-free event.

### Provisioning and readiness

- IAM emits an app-scoped onboarding-ready fact after declared entry prerequisites permit entry. Only apps explicitly configured for UMS trigger provisioning. UMS records its inbox effect, unique profile, and own lifecycle outbox atomically.
- Distinguish PROFILE_REQUIRED (absent or declared required fields unmet), PROFILE_PROVISIONING (accepted creation in progress), and PROFILE_READY (durably provisioned and required fields satisfied). Responses distinguish a missing profile from an existing incomplete profile and include safe next actions. Temporary failure is a retry/error condition, not a fabricated lifecycle transition.
- Completion is evaluated against a reviewed, versioned App Profile Policy containing only supported profile fields and validation. No terms, subscriptions, eligibility, financial setup, invitations, or roles are evaluated by UMS. IAM/product authorities own those decisions.
- A newly provisioned profile is ready immediately under the default policy. Under a stricter policy, creation is still automatic but the product presents completion before its gated action. Never discard a valid IAM session to force completion.
- Adding required fields is an explicit policy revision. Existing data is preserved; clients can discover the new requirement and complete it. Policy rollout needs compatibility handling, rather than silently relabeling already issued events.
- Activation, profile readiness, profile lifecycle, and product permissions are separate facts. Neither readiness nor profile creation grants product access or reactivates a departed profile. Closure/deletion behavior is deferred to the lifecycle ticket.
- Duplicate/delayed/reordered facts cannot create additional profiles or regress newer lifecycle state. A replay is not a new first-entry command. Exact ordering, tombstone, reconciliation, and activation-version contracts are the integration decision.

### Read and update authority

- A user may read/update their own profile only within the app and allowed fields. Product workloads may read needed display data or perform explicitly authorized field operations within that app; workload authentication alone does not authorize arbitrary human targets.
- Resource authority must bind initiating application and target App Subject/Profile Reference. UMS does not accept caller-supplied app IDs as proof or discover cross-app links. Use IAM-registered audiences/workload or approved delegation contracts; never forward a token for another audience.
- Mutations use idempotency for command retries and expected versions for existing records. Concurrent edits fail safely rather than silently overwriting each other. Ordinary profile edits cannot alter subject binding, activation, or lifecycle authority.
- Internal profile data is private by default. A product authorizes display to its members through its own API/projection. UMS exposes no public people directory, cross-product profile listing, or broad support impersonation.
- UMS is profile authority; products may hold purpose-bound display projections with version/freshness and repair contracts. PII-free change facts carry scoped references and versions, with protected reads retrieving fields. Historical product evidence is not automatically rewritten when a profile changes.

### Dutch integration seam

Dutch owns Member IDs, placeholders, claim offers, alias/canonicalization decisions, group membership, and financial authorization. Its integration maps the Dutch App Subject and UMS Profile Reference to Dutch Member identity, projects authorized display values, and gates its own actions. Shared UMS does not emit a generic claim/canonicalization event or turn profile readiness into Ledger authority. The existing member-mapping promises require Dutch-owner reconciliation before replacement.

### Acceptance scenarios for the later implementation

- Concurrent first entry and duplicate onboarding delivery create one profile; unrelated apps get none.
- Default-policy provisioning becomes ready without a form; stricter policy reports unmet fields and preserves the IAM session.
- Updating Dutch's name/avatar leaves Midas unchanged; a wrong-app token or target is denied.
- A generated fallback cannot satisfy an explicit-name requirement; no email/phone leaks into fallback, events, or logs.
- Racing updates cannot silently overwrite one another; retrying a committed command returns its prior safe outcome.
- An avatar reference cannot expose another user's private asset or cause arbitrary server fetching.
- Locale/timezone edits do not alter Midas accounting periods or Jarvis scheduling authority.
- UMS outage produces honest pending/retry behavior; stale events cannot resurrect closed/deleted state.

These are specification assertions, not executed test evidence. Lifecycle and exact integration contracts remain open.
