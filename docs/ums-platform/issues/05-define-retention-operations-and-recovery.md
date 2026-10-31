Type: grilling
Status: resolved
Assignee: Codex
Parent: [Specify the shared User Management Service](../map.md)
Blocked by: 03, 04

# Define retention, operations, and recovery guarantees

## Question

What explicit retention periods, deletion processing objectives, export expiry, audit protections, backup/restore erasure controls, lifecycle-reconciliation monitoring, operational ownership, and cloud-independent recovery requirements make UMS's scoped lifecycle promises observable and supportable? Distinguish UMS completion from content/product-owner acknowledgements and avoid approving unspecified or indefinite personal-data retention.

## Answer

The user accepted 90-day retention for closed profiles with advance notice before erasure, and a cost-conscious best-effort initial deployment with encrypted backups, tested restore, monitored event delivery, and documented recovery limits. These are design requirements; no uptime, recovery, provider cost, or notification delivery has been measured or promised.

### Lifecycle deadlines

- CLOSED profile retention expires 90 elapsed days after authoritative closure. Publish the deadline at closure and schedule app-scoped advance-notice intents 14 and 7 days before expiry. If closure is observed late, preserve the authoritative deadline and send only still-relevant notices; never invent a fresh 90-day period.
- At expiry, ordinary use remains blocked and a durable worker erases UMS-owned personal fields. This is expiry of an already disclosed retention policy, not a new deletion request with another 30-day grace period.
- Deliberate eligible reactivation before erasure cancels this closure-expiry schedule. Login, duplicate events, and notice acknowledgements cannot reset it. Independent app-only/global deletion causes still apply, with the earliest irreversible deadline winning.
- App-only deletion retains its settled 30-day cancellation window. IAM final deletion triggers cleanup without an additional UMS window. In all cases, deadline passage immediately prevents further cancellation/use even if the erasure worker is delayed.
- Design objective while service dependencies are healthy: erase live UMS personal fields within 24 hours after the relevant deadline or receipt of authoritative final deletion. Alert on delayed work; distinguish actual completion from a missed objective. Outages do not extend the cancellation deadline.

### Notice delivery boundary

- UMS does not acquire raw email/phone to deliver notices. Publish minimal app-routed notice intents with request/reference, notice class, deadline, and deduplication identity to a product-owned or approved notification adapter. That owner chooses an authorized channel, wording, and delivery policy.
- Track queued, handed-off, acknowledged, and failed notice states separately. Retry with stable intent IDs and observe missed notice windows. Delivery failure does not silently extend personal-data retention or imply successful notice.
- The product must disclose the retention policy at closure and provide a status/reactivation path. Do not enable closure-expiry erasure in a consumer integration until its notice responsibility, accessible user experience, and failure behavior are accepted and tested. No notification provider has yet accepted this dependency.

### Retention schedule

These initial operating defaults are explicit and versioned. Any change needs a recorded privacy/operational impact assessment; no unspecified indefinite retention is approved.

| Record class | Initial retention / disposal rule |
| --- | --- |
| Active profile fields | While the profile remains active; lifecycle requests and authoritative deletion override |
| Closed profile fields | 90 days from closure, shortened by an earlier deletion deadline |
| App-only deletion-pending fields | Until the 30-day deadline, shortened by an earlier authoritative erasure obligation |
| Export artifact | At most 24 hours from generation; deletion or loss of authorized availability invalidates access earlier |
| Download capability | At most 5 minutes, scoped and single-use; checked against live operation/lifecycle state |
| Command-idempotency record | 7 days for safe command identity/fingerprint/outcome reference, without personal field snapshots |
| Published outbox / processed inbox payload | 7 days after successful local processing/publication; unresolved records are quarantined with an owner and reviewed, not silently discarded |
| Diagnostic logs/traces | 14 days, minimized/allowlisted; no request bodies, personal profile values, or bearer material |
| Minimal security/lifecycle audit | 365 days, access-controlled and integrity-protected; no historic personal-field values |
| Encrypted backups | Rolling 7 days, including lifecycle metadata; access isolated and disposal verified |
| Erasure/replay fence | While the associated IAM app-subject binding can still receive eligible traffic, retain only scoped reference/generation/revision and irreversible state; no display data |

- Erasure fences are safety metadata, not operational inbox history. Their final retirement requires authoritative IAM subject retirement, expiry of all declared event/replay/backup windows, and a retained stream-generation/checkpoint fence that rejects historical traffic. Do not delete a fence merely because a table TTL expires.
- Review replay-fence necessity at least quarterly. If a finite safe retirement proof cannot be established, keep the minimal fence and record the reason/review date; do not retain personal fields with it. The persistence/contract design must encode and test this rule.
- Quarantines contain only schema-allowed minimized events. Any prohibited personal payload is isolated under an incident process with prompt redaction/removal, rather than retained indefinitely as a debugging convenience.
- Failed exports and partial artifacts are cleaned within 24 hours; erasure takes priority. Retry/audit stores must not become recoverable copies of erased profile fields.

### Initial operating tier and recovery

- Initial deployment is best effort: no published availability percentage, multi-zone promise, guaranteed RPO/RTO, paid managed-service selection, or mandatory always-on replica count. Provider choice and cost remain cloud-design inputs.
- A single application deployment plus UMS-owned durable database and relay/maintenance worker modes is acceptable if it preserves ownership, transaction, security, and recovery invariants. No self-managed orchestration or broker is introduced without demonstrated need.
- Back up at least daily to separately protected encrypted storage and retain 7 rolling days. This schedule is not proof of achieved RPO: without additional durability, acknowledged changes since the last successful backup can be lost. Surface backup age/failure and record actual restore results.
- Target an exercised, operator-driven restore within one business day initially; it is an internal planning objective, not an uptime or response-time SLA. An unavailable sole operator remains an explicit limitation.
- Before serving restored data, reconcile IAM authority, UMS erasure/closure/deletion evidence, and independently recoverable lifecycle fences. Missing evidence means affected reads/provisioning stay restricted. A pre-deletion backup must never become proof that deletion was cancelled.
- Deployment acceptance must demonstrate how post-backup privacy transitions survive or are authoritatively recovered. Backups alone cannot guarantee that. If the chosen infrastructure cannot recover local app-only deletion evidence, introduce an independently durable minimized lifecycle journal or block that release; do not weaken the no-resurrection invariant to meet a zero-cost goal.
- Run an isolated restore drill before launch, then quarterly and before destructive schema/key retirement. Record backup age, data loss, restored fences, migrations, permissions, event checkpoint convergence, actual recovery time, and unresolved gaps.
- IAM, UMS, and products retain separate databases/credentials and restore boundaries. Shared physical infrastructure needs an explicit blast-radius assessment; no shared schema, transaction, or cross-service database access.

### Monitoring, security, and ownership

- Monitor oldest outbox/inbox gap, quarantine age, relay retries, provisioning age/failure, erasure deadlines/completion, notice intent delivery/failure, export cleanup, snapshot reconciliation, backup age, restore evidence, database saturation, authorization denials, and cost/storage growth.
- Initial alert thresholds: unprocessed erasure work older than 1 hour past its deadline, any erasure objective exceeding 24 hours, no successful daily backup for 26 hours, poison event, repeated authorization/configuration failure, and failed privacy/export cleanup. Outbox/reconciliation delay over 5 minutes triggers investigation rather than a claim that every event must arrive within 5 minutes.
- Notifications and alerts contain safe app/operation/reference and failure class, never name, email, phone, avatar URL, tokens, download links, or product content. Do not use user references as unbounded metric labels.
- Protect database, backups, exports, audit, and keys with separate least-privilege purpose/environment policies. Encryption key custody/rotation must be compatible with restoring the retained backup window. Only public profile API entry points are internet-routable; internal repair/configuration and storage remain private/authenticated.
- Retain bounded per-app/actor/subject rate limits and concurrency even when cache/rate-limit infrastructure is degraded. Backpressure protects privacy finalization and lifecycle ingestion from bulk display reads.
- Required runbooks: database/backup loss, profile data exposure, compromised workload, event-gap repair, poison event, stuck provisioning, overdue erasure, failed notice, content-reference revocation, and safe restore/forward repair.
- UMS operator, consumer integration owner, IAM owner, content owner, and notice owner are responsibility gaps until explicitly assigned. One developer may hold several hats, but the plan cannot pretend there is a staffed 24/7 rotation or independent approver.
- Immutable artifacts, external secrets, configuration review, dependency/secret scanning, and staged migration/rollback evidence are prerequisites to operation. A rollback cannot restore erased fields or roll back security/lifecycle versions.

### Acceptance and upgrade triggers

- Prove closed-profile expiry, 14/7-day notice scheduling, reactivation/deletion races, missed notice handling, retention sweeps, export invalidation, and replay-fence retirement without shortening required evidence.
- Demonstrate an erased profile remains erased after restoring a backup predating its deletion, including app-only deletion while IAM activation remains active.
- Publish the actual cost, backup/durability capability, achieved restore result, monitoring coverage, and operator limitations before calling the deployment ready. Existing IAM production SLOs do not automatically apply to this best-effort UMS tier.
- Revisit the tier when usage, consumer outage tolerance, observed recovery loss/time, storage/cost, privacy processing backlog, or incidents exceed the accepted envelope. Paid redundancy and managed durability require a new cost/reliability decision, not silent scope expansion.

No operational mechanism has been implemented or tested in this Wayfinder. Persistence/technology selection, provider reconciliation, machine-readable contract completion, and verification/handoff remain open.
