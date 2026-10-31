Type: grilling
Status: resolved
Assignee: Codex
Parent: [Specify the shared User Management Service](../map.md)
Blocked by: 01, 02

# Define profile closure, reactivation, and privacy boundaries

## Question

What profile-specific closure, reactivation, export, deletion/anonymization, retention, and administrative authority are needed, and how do these compose with IAM app departure, global deletion/cancellation, and product-owned historical evidence without resurrecting access or deleting another product's data?

## Working constraints

- IAM retains app departure/reactivation, global lock, deletion-pending/cancellation/final authority. UMS handles only app-profile data and cannot revoke or restore IAM authority.
- App departure closes ordinary profile use; it is not silently interpreted as an erasure request. Reactivation is deliberate and must recheck IAM activation, current profile policy, and pending lifecycle operations.
- Global deletion follows IAM's settled 30-day recovery window. UMS restricts use on app-scoped deletion-pending, handles cancellation by authoritative version, and irreversibly erases/anonymizes on finalization. Delayed/stale events cannot revive erased data or access.
- Application-specific deletion affects only that app's profile. Product business data, historical evidence, memberships, health/finance records, and other app profiles remain outside UMS mutation authority.
- Export contains only the requesting app's UMS-owned data and releasable lifecycle metadata; recent strong authentication, scoped authorization, protected short-lived download, and audit are required. IAM and products supply their own exports.
- Profile edits, retry, and replay cannot cancel deletion or reactivate a closed profile. Lifecycle transitions use explicit authority, reason classes, versions, and durable processing.
- Erasure removes display values, avatar associations, and derived copies under UMS control. A minimal non-reusable tombstone prevents stale replay; it is not a retained display profile. Asset deletion and product-cache acknowledgements need separate owner contracts.
- Operational status distinguishes request acceptance, local completion, and downstream acknowledgement. UMS must not claim product-wide erasure when it has erased only its own data.

## Answer

The user accepted a clearly disclosed 30-day cancellation window for app-only profile deletion. Ordinary profile use stops immediately; irreversible erasure follows the deadline. This is a profile-only operation, not an implicit IAM app-departure command or a promise to erase the product's business data.

### Separate lifecycle dimensions

- UMS tracks ACTIVE, CLOSED, DELETION_PENDING, and ERASED profile lifecycle. Readiness remains a separate completion fact; external activation/security restrictions independently determine whether a caller can use an otherwise ready profile.
- An app-scoped IAM departure closes normal profile use without scheduling erasure. Profile data remains subject to a declared retention policy, not an invented indefinite-retention guarantee. A later deliberate IAM reactivation may reopen a retained profile after current policy and deletion checks.
- IAM suspension or security lock restricts use without automatically deleting profile data. Removing the restriction does not cancel another closure or deletion cause.
- Profile existence, returning through SSO, editing, or replaying an onboarding fact never reopens CLOSED/DELETION_PENDING/ERASED state. First-entry auto-provisioning applies only to an eligible initial entry, not to tombstone resurrection.

### App-only deletion and cancellation

- Require current, purpose-bound app authority and recent Strong authentication. The user sees the exact app, affected UMS data, deadline, cancellation path, and boundary with product-data deletion before confirmation.
- Persist request ID, authoritative accepted time, deadline, reason class, lifecycle version, and PII-free outbox fact atomically. Exact retries preserve the original deadline rather than extending it.
- At acceptance, stop ordinary reads/updates and revoke or invalidate UMS-controlled profile views/downloads. Restricted status, cancellation, and privacy operations remain available through appropriately scoped authority; profile-required gating must not make cancellation inaccessible.
- Cancellation before the deadline requires an explicit authenticated command with expected state/version. Repeated login or a normal edit is not cancellation. Cancellation removes only this request's restriction; it does not reactivate IAM, restore product permissions, or cancel global deletion.
- Finalization and cancellation race through one authoritative transaction/clock. Once the deadline passes, cancellation fails even if a worker has not run yet. Once erasure commits, it is irreversible.
- A durable finalizer erases personal fields and UMS-owned derived data, disconnects avatar associations, and emits a versioned erasure fact. Worker retries are idempotent. Local failure remains visible and retried; it is never reported as completed.

### Interaction with IAM global deletion

- Global deletion-pending/cancellation/final facts are authenticated, app-scoped IAM authority. UMS never needs the global Human Identity ID or a list of the person's other apps.
- Track app-only deletion and IAM-global deletion as independent causes with their own versions/deadlines. Removing one cause cannot clear another. Existing requests do not restart or extend each other's deadlines.
- An earlier app-only erasure deadline remains effective even if a later global deletion is cancelled. Cancelling app-only deletion does not bypass IAM's global restriction or final deletion.
- Global pending restricts use immediately on receipt; authoritative global finalization triggers irreversible UMS erasure without starting another 30-day period. A late pending/cancellation event cannot reverse a newer finalization. Gaps are repaired through IAM's scoped snapshot contract.
- UMS does not invent IAM authority when events are delayed. Its local status distinguishes observed authority, freshness, and processing state. Resource/security projection rules and IAM token expiry still govern access while reconciliation catches up.
- Global cancellation alone does not reopen a profile closed by app departure or recreate an erased profile. Re-entry requires fresh eligible authority and deliberate product onboarding. Any future replacement profile uses a fresh Profile Reference/generation and cannot silently relink retained Dutch evidence or old claims; exact tombstone/uniqueness mechanics belong to integration/persistence design.

### Erasure, assets, projections, and backups

- ERASED is a minimal purpose-limited tombstone, with scoped reference/version and necessary replay-suppression evidence, not a recoverable personal profile. Do not retain display fields or full historic event payloads for convenience.
- Remove or de-identify UMS-owned personal fields, caches, search/read models, stored exports, and avatar associations. Do not delete a shared content blob without verifying ownership/reference rules; request content-owner cleanup and track its result separately.
- Products receive minimal app-scoped lifecycle facts and own cleanup of their caches/projections and retained business evidence. Record delivery/acknowledgement without treating acknowledgement as proof that every product database was erased.
- Operational retention must name periods and purposes for CLOSED records, tombstones, audit, event/inbox/idempotency records, exports, and backups. Those values remain an explicit operations decision; no indefinite retention is approved here.
- Backups are access-restricted and expire under the documented retention policy. Restores must reapply erasure/tombstone evidence before serving traffic so restored data cannot reappear. Do not claim immediate deletion from every backup when only live-state erasure is complete.

### Export and administration

- Export only UMS-owned fields and releasable lifecycle information for the authorized app/subject. No cross-app list, raw IAM identifier, credential, internal support notes, or product business records.
- Generate exports with scoped strong authority; protect artifacts at rest, use bounded expiry/download authorization, and audit generation/download. Deletion invalidates outstanding exports; races must not publish a new artifact after erasure. Exact expiry/retention limits belong to operations and API contracts.
- Administrative actions are narrow and reason-coded: inspect safe processing status, retry failed delivery/finalization, and apply separately authorized profile restrictions. No general impersonation, identity rebinding, direct database editing, or restoration of erased fields.
- Audit captures actor, scope, action, version, time, correlation, and safe reason classes without copying erased field values or bearer secrets. Audits are separately protected and retained under an explicit policy.

### Acceptance scenarios for implementation

- Deleting a Dutch profile immediately blocks ordinary use but leaves Midas and IAM identity intact.
- Exact delete retries preserve one deadline; normal login and edits cannot cancel it.
- Strongly authenticated cancellation succeeds only before the deadline and cannot bypass app departure or global deletion.
- App-only and global requests/cancellations in either order preserve independent restrictions and never extend an earlier erasure deadline.
- Finalization is retry-safe; stale onboarding, cancellation, and snapshot events cannot resurrect a tombstone.
- An offline consumer later converges to erased state; status distinguishes local erasure from downstream completion.
- Active export/download and avatar-reference races cannot expose erased data or remove another owner's content.
- A backup restore replays erasure evidence before serving profile reads.
- Dutch historical Member references remain product-owned and cannot be silently reassigned during fresh onboarding.

These are specification acceptance scenarios, not executed evidence. Concrete retention periods and integration contracts remain open.
