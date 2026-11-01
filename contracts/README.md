# UMS contract foundation

This is an executable **design contract**, not a running API or implemented authorization/lifecycle engine. `v1/openapi.json` is the baseline task-specific HTTP surface; `v1/schemas` owns strict JSON Schema 2020-12 DTOs and events. No server URL or live provider integration is declared. `$id` URLs are identifiers, not network dependencies; the test registry resolves all relative schema references locally.

Run with Python 3.11+ in a virtual environment:

```sh
python3 -m venv .venv
.venv/bin/pip install -r contracts/requirements-test.txt
.venv/bin/python -m unittest discover -s contracts/tests -v
```

The fixture manifest labels schema-positive and schema-negative examples. It proves wire structure and primitive constraints, not issuer/audience validation, ownership, readiness evaluation, concurrency, deadlines, supported locale/timezone catalogs, or replay safety. Those require subsequent implementation tests. The four profile fields, explicit null clearing, independent deletion causes and decimal signed-64-bit revisions are represented. Decimal versions must be compared losslessly; timestamps never order revisions. Bindings expose opaque UMS scope references, never the global IAM identity or raw contact details. These scope references require a later accepted IAM mapping contract; they do not invent IAM token claims.

## Validation beyond JSON Schema

Before schema validation: enforce 16 KiB decoded mutation / 32 KiB event bounds, bounded decoding, duplicate-key rejection, strict Unicode, supported media type and no unexpected nesting. Normalize display names with Unicode whitespace trimming and NFC before applying 1–80-code-point limits; reject controls, bidi override/isolate characters and line separators. The avatar wire alphabet is deliberately ASCII opaque references (1–256 bytes) with no URL semantics; final content-provider reference compatibility is a gated dependency. Validate locale/timezone against explicitly versioned supported catalogs (syntax alone is insufficient). Normalization must never make an empty name valid.

After schema validation: validate app/actor/target/issuer/environment/audience and token kind, registered delegation and field permissions, current authority and lifecycle, expected version and idempotency fingerprint. Compute readiness from explicit fields and current policy; generated names never satisfy explicit name requirements. Events carry no display fields, asset references, secrets, arbitrary metadata or raw IAM identifiers. Strict schemas do not prove semantic non-disclosure inside allowed string values; trusted producers and log controls remain necessary.

`binding-state` represents a retained CLOSED generation, pending deletion with one or both independent causes, or an ERASED tombstone without an active generation/reference. These are current-state representations; restrictive authority and source checkpoint reconciliation remain independent. New generation creation requires fresh accepted intent and never reuses the retired reference. Snapshot acceptance must compare persisted binding/source checkpoints and erasure fences; a structurally valid snapshot can still be stale and forbidden.

Only `ums.profile.state-changed.v1` advances a binding checkpoint. Operation request/notice/export/content-cleanup facts have their own operation IDs/revisions and cannot update that checkpoint. No duplicate per-purpose profile streams are published. Status values distinguish accepted, local completion, delivery acknowledgement and failure; no acknowledgement proves downstream product erasure. The baseline omits export download capabilities entirely so no URL or bearer secret can enter events.

`notice-intent.json` publishes one minimized intent per retention-notice window (`CLOSURE_EXPIRY_14_DAY`, `CLOSURE_EXPIRY_7_DAY`) with the profile scope, the published retention deadline, a deterministic `dedupeIdentity` bound to the closure instance, an incrementing `noticeRevision`, and a delivery `state`. One fact is published per state change, so the owner learns that an intent was handed off, acknowledged, failed, or withdrawn and can stop a queued delivery. It carries no contact detail, channel, wording or message body: UMS owns no contact data and cannot claim a notice was delivered. A late-observed closure records `WINDOW_PASSED` for a window whose start has passed rather than shifting it, and a profile closed again after reactivation produces fresh intents because the identity includes the new closure instance, not only the deadline.

## Actor contract

| Actor | Allowed purpose | Excluded |
| --- | --- | --- |
| App-bound direct human | Own status/display and allowed field edits | Another subject/app, configuration, arbitrary provisioning |
| Recent-Strong human or registered delegation | Own deletion, eligible cancellation, export | Generic act-as, replayed assurance, stored user tokens |
| Registered product projection workload | Explicit same-app display targets, scoped snapshot | Human privacy commands, global directory |
| Registered IAM producer | Published scoped source facts | Display mutations, domain permission grants |
| UMS maintenance worker | Execute/retry committed finalization, retention, relay | Invent intent, extend deadline, revive erased data |
| Configuration reconciler | Reviewed adoption/policy revisions | User browsing, impersonation, IAM registry mutation |
| Narrow operator | Safe status, audited case-bound repair | Direct data edits, identity rebind, erasure reversal |
| Anonymous/unregistered | None | All private operations |

## Remaining gates

External IAM/content schemas are not forked or fabricated here. IAM 1.0.0 is now pinned as the read-only provider archive described below. Real production integration still requires owner acceptance and any outstanding lifecycle/security contracts; content remains unpinned. UMS safe errors intentionally omit optional details; they do not fork or claim compatibility with an unpublished IAM error schema. Acknowledgement delivery contracts still need task-specific schemas before their runtime slice. Runtime tests must cover cross-app/token/delegation attacks, expired exports, stale snapshots, restored erased data, cancellation/finalization races, replay and privacy recovery. A local fixture is not provider acceptance. Breaking lifecycle/security/subject meanings require a new contract version and explicit migration.

TDD evidence: the initial test failed with missing `fixtures/manifest.json` before contracts existed (RED); the same suite passes against the schema/fixture bundle (GREEN). Those fixture checks alone prove no runtime behavior. The subsequent `producerContractTest` Gradle task additionally validates an actual event stored by the internal provisioning integration test; see [Slice A](../docs/development/provisioning.md) for its supported behavior and remaining provider gates.


## Pinned IAM dependency

`dependencies/iam/` contains the provider-generated `iam-contracts-1.0.0.zip`, its original SHA-256 sidecar, and `lock.json`. The lock records source path, contract version, archive digest, and every archive member digest. All 80 non-directory entries were compared byte-for-byte with the current IAM `contracts/` source before pinning; no mismatch was found. The source folder has no Git metadata, so no commit, release provenance, or remote identity is invented. This local pin is not a signed release or owner acceptance.

Read schemas and fixtures directly from the immutable archive; do not extract an editable copy or update schemas independently. A provider update requires regenerating/verifying the provider artifact and deliberately updating the whole pin and tests. Consumers never fetch the schema `$id` URLs. The dependency test uses an offline registry of all 12 archived JSON Schemas and checks 10 valid and 53 invalid provider fixtures. Of the invalid fixtures, 39 fail schema/format checks; 14 require cross-field/registration/security semantics and are explicitly listed in the lock rather than misrepresented as schema rejection. Those semantics remain provider/runtime conformance duties.

`rfc3339-validator` is pinned because plain `jsonschema` silently omits date-time validation without its optional format dependency. The tests check provider date-time rejection through the invalid fixture inventory.

`producer-tests/test_iam_adapter_input.py` separately validates the raw `build/generated-contract-fixtures/iam-onboarding-input.json` produced by JVM PostgreSQL adapter tests against the pinned onboarding event, including app/aggregate cross-field equalities. This producer check intentionally fails until the actual JVM fixture exists; it never manufactures output or requires a live IAM deployment. A schema-valid development adapter input proves wire compatibility only, not live transport, IAM trust, Dutch membership integration, or production acceptance.

## App-only deletion status (Slice D1)

`deletion-request-status.json` is a restricted operation view, separate from the acceptance acknowledgement. `PENDING` may remain visible after the deadline with `cancellationEligible: false`; it never claims erasure happened. `CANCELLED`, `LOCAL_COMPLETED` and `SUPERSEDED` always have false cancellation eligibility. `SUPERSEDED` means another obligation erased the profile first, so the request can never complete; it is a terminal answer, not a completion or cancellation claim. `LOCAL_COMPLETED` means local UMS-owned field/derived-data erasure committed; it never promises downstream acknowledgement, content-provider cleanup, backup removal, or whole-product erasure. The original accepted time and deadline remain visible after cancellation; revisions stay lossless decimal strings. The required current `bindingRevision` is the safe concurrency token supplied as cancellation `expectedVersion`; it is distinct from `operationRevision`, so restricted users can cancel without reading normal profile state. Runtime authority/clock checks determine eligibility, and the boolean never grants permission or extends a deadline. The schema cannot validate clock-relative eligibility, exact 30-day arithmetic, independent IAM restrictions, or finalization races. The existing design-only deletion status response references this schema; no HTTP route is declared implemented by this contract change.

## Explicit maintenance batch result (Slice D3)

`deletion-batch-result.json` describes an internal, explicitly invoked maintenance result, without adding a route or scheduler. The command bounds `limit` to 1–100; results contain 0–100 request references and allowlisted outcomes only: `LOCAL_COMPLETED`, `BLOCKED`, or `RETRYABLE`. The latter represents recognized transient database unavailability; unknown/integrity failures propagate and must not become fabricated success or retry reports. No personal fields, SQL, raw exceptions, arbitrary error maps or bearer capabilities are allowed.

The nullable `nextCursor` contains the same app and fixed cutoff with an `(afterDeadline, afterRequestReference)` keyset. Runtime must verify app/cutoff equality, cutoff eligibility, and progress after all attempted rows; it is neither a public bearer capability nor a lease or completion acknowledgement. Schema checks bound and allowlist the structure; PostgreSQL tests establish selection, failure-isolation, races and pagination behavior. The actual JVM producer fixture is `deletion-batch-result.json`.
