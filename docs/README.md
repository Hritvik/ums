# UMS documentation

UMS is a standalone service for app-isolated profile management. IAM owns
identity/authentication; products retain their business data and permissions.

- [Requirements and service-design map](ums-platform/map.md): canonical decision index.
- [Remaining work items](development/work-items.md): the D–G build breakdown and its scope boundary.
- [Domain glossary](ums-platform/GLOSSARY.md): profile terminology.
- [Implementation handoff](ums-platform/issues/08-define-verification-and-implementation-handoff.md): build slices and release gates.
- [Executable API/event contracts](../contracts/README.md): schemas and fixture validation.
- [Foundation setup](development/foundation.md): local verification and runtime safety boundary.
- [Internal provisioning](development/provisioning.md): Slice A behavior, evidence, and remaining integration boundaries.
- [Due-deletion batches](development/deletion-batch.md): Slice D3 bounded processing, continuation, and isolated failures.
- [Local erasure finalization](development/profile-erasure.md): Slice D2 deadline processing, replay fences, and local-completion limits.
- [App-only deletion](development/profile-deletion.md): Slice D1 requests, cancellation, restricted status, and remaining privacy gates.
- [Closure and retention](development/profile-closure.md): Slice D4–D5 app departure, the published 90-day deadline, and deliberate reactivation.
- [Proposed contracts](contracts/proposed/association-report-v1.md): owner-acceptance proposals for the association report and the content attachment.
- [Upgrade and rollback](operations/upgrade-and-rollback.md): the forward path, interrupted migrations, and rollback limits.
- [Runbooks](operations/runbooks.md): alert thresholds and the operator procedures.
- [Operating tier](operations/operating-tier.md): what runs on its own and what is not promised.
- [Restore recovery](development/recovery.md): Slice F3 erasure journal, reconciliation, and backup signals.
- [Restore drill](development/restore-drill.md): recorded drill and the operator runbook.
- [Retention sweeps](development/retention.md): Slice F2 declared lifetimes, bounded batches, and fence preservation.
- [Outbox relay](development/outbox-relay.md): Slice F1 at-least-once delivery, ordering, retry, quarantine, and repair.
- [Scoped exports](development/exports.md): Slice E2 artifact lifetime, single-use capability, and revocation.
- [Profile HTTP vertical](development/profile-http.md): Slice C3 first shipped status route, caller interceptor, and mapping.
- [IAM ingestion](development/iam-ingestion.md): Slice C2 strict event intake, producer/app binding, and quarantine.
- [Caller authority](development/caller-authority.md): Slice C1 provider token validation, tuple rules, and ingress limits.
- [IAM-global deletion](development/global-deletion.md): Slice D7 independent global causes, stale rejection, and immediate final erasure.
- [Retention notices](development/notices.md): Slice E1 notice intents, windows, delivery states, and suppression.
- [Local Dutch consumer journey](development/dutch-consumer-journey.md): simulated consumer evidence and the real integration handoff.
- [IAM consumer preparation](development/iam-integration.md): Slice C provider evidence and remaining integration gates.
- [Profile edits and display](development/profile-editing.md): Slice B field validation, readiness, retries, and protected reads.

The specification was promoted from `.scratch/ums-platform` into
`docs/ums-platform` without replacing its decision history. Cross-product
expectations and provider acceptance remain in the central portfolio capability
home under the stable contract ID `app-isolated-profile-management`; this repo
holds the UMS technical design and implementation evidence.

The design map is complete; implementation and provider/deployment acceptance
are separate stages. No live availability or privacy-recovery guarantee follows
from a passing local test suite.
