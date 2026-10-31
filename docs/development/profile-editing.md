# Slice B: internal profile edits and protected display

This extends the constructor-only [provisioning service](provisioning.md).
Application HTTP routes remain denied. The trusted authority types describe
already resolved local permissions; they are not tokens or proof of IAM authority.

## Supported behavior

- A patch distinguishes absent fields, explicit null clearing, and supplied
  values. It enforces the app's human-editable field policy and expected profile
  revision before applying a new command.
- Names reject malformed Unicode, controls, line separators, and explicit bidi
  overrides/isolates; Unicode surrounding whitespace is trimmed and NFC applied.
  The maximum is 80 Unicode code points, including supplementary characters.
- Locale and display timezone must match injected, versioned supported catalogs.
  The catalogs must match the registered profile policy. These fields do not
  change product-owned financial or scheduling rules.
- Non-null avatar attachment is rejected until content authority is accepted.
  Explicit clearing is supported, subject to the same field permissions.
- Actual field changes increment profile and binding revisions once, recompute
  readiness from explicit stored values, and publish one minimized canonical
  FIELDS_CHANGED event. No-op patches record their receipt without a revision
  or event increment. Generated fallback presentation never completes a required
  explicit display name.
- The idempotency receipt is scoped to actor, app, subject, operation, and key.
  It contains a keyed fingerprint and safe outcome references/revision, not a
  personal-field or response snapshot. Sorted request fields, explicit clearing,
  and expected version define the fingerprint; correlation metadata does not.
- Current authority, field policy, catalog compatibility, and ACTIVE lifecycle
  are checked before returning a prior receipt. A replay cannot bypass a later
  restriction. The caller reads current display separately from the minimal
  acknowledgement of its original accepted command.
- Self display and bounded projection batches read under the same policy-row
  lock as edits/status, so readiness and fields form a coherent snapshot.
  Projection reads require an explicit app, purpose, permitted target set, and
  field allowlist. Missing, forbidden, foreign-app, and restricted targets all
  produce the same unavailable result.

## Implementation and verification

V3 adds typed display columns and safe patch receipts with acceptance timestamps.
All changes, receipts, and outbox records commit or roll back together. Key and
catalog snapshots are immutable; there are no production defaults for them.

On 24 October 2026, the clean `check bootJar` build passed with JDK 21 and the
local Docker context: 54 Kotlin tests, zero failures/skips, 43 schema fixtures,
and five producer checks against actual persisted events/status/display output.
Global coverage was 99.2% lines and 96.4% branches. Tests include concurrent
same-version edits, clear-versus-absent/no-op semantics, exact and changed retry
behavior, lifecycle/permission checks before replay, rollback, Unicode limits,
catalog mismatch, avatar gating, and projection non-enumeration.

The initial missing edit service produced the test-first compilation RED, and
the four new producer checks failed on missing artifacts before implementation.
Subsequent behavior and boundary tests passed against real PostgreSQL. Read-only
correctness/security review found no material finding within the supported slice.

See [foundation setup](foundation.md) for the verification command and reports.

## Remaining gates

No real IAM authority adapter, HTTP DTO parser, public endpoint, or production
service bean is enabled. Body limits/duplicate JSON keys, real authentication,
sender constraints, app-subject mapping, rate limits, and production audit remain
ingress/release requirements. The HMAC key must be externally managed and remain
stable for active receipt verification; key rotation/retirement and the agreed
seven-day receipt purge are not implemented. Catalog sourcing/version promotion,
policy reevaluation, content/notice integration, lifecycle deletion/erasure,
relay/repair adapters, privacy-preserving restore, and deployment remain gated.
