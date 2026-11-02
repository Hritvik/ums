package com.vik.ums.privacy

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.Lifecycle
import com.vik.ums.profiles.ProfileField
import com.vik.ums.profiles.ProfileFieldsPersistence
import com.vik.ums.profiles.ProfilePersistence
import com.vik.ums.profiles.ProfileScope
import com.vik.ums.profiles.Readiness
import com.vik.ums.profiles.lockedPolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Scoped export of UMS-owned data with a 24-hour artifact and a single-use five-minute
 * download capability. The artifact is a second copy of personal data, so it is invalidated
 * on erasure and its payload is dropped as soon as it expires.
 */
class ProfileExportService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    private val bindings = ProfilePersistence(jdbc)
    private val fields = ProfileFieldsPersistence(jdbc)
    private val random = SecureRandom()

    fun request(authority: TrustedExportAuthority, idempotencyKey: UUID): ExportStatus = transactions.execute {
        authorize(authority)
        val scope = authority.scope
        val fingerprint = sha256("export")
        jdbc.query("""select export_reference from ums_profile_export_receipts
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and actor_reference = ? and idempotency_key = ?""",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            authority.actorReference, idempotencyKey).singleOrNull()?.let { existing ->
            // A replay reports what the original export is now, not what it was when accepted.
            return@execute ExportStatus(existing, "1", load(scope, existing).state.openStatus())
        }
        // The registered policy row is the shared application lock: an export cannot be built
        // from a binding that a concurrent deletion, closure, or expiry is about to replace.
        val policy = lockedPolicy(jdbc, mapper, AppScope(scope.issuerEnvironmentRef, scope.appReference))
            ?: throw IllegalStateException("Profile capability is unavailable")
        val state = bindings.binding(scope)
        check(state?.lifecycle == Lifecycle.ACTIVE) { "Profile is unavailable" }
        val present = fields.fields(scope)
        val readiness = if (policy.requiredFields.all { it in present.keys }) Readiness.PROFILE_READY
        else Readiness.PROFILE_REQUIRED
        val now = clock.instant()
        val artifact = ExportArtifact(
            profileRevision = state.profileRevision!!,
            readiness = readiness.name,
            lifecycle = state.lifecycle.name,
            // An allowlist, not "whatever the profile happens to hold": a new profile field
            // must never join an export copy implicitly.
            fields = present.filterKeys { it in EXPORTED_FIELDS }.mapKeys { it.key.wireName },
            generatedAt = now.toString(),
        )
        val reference = UUID.randomUUID()
        jdbc.update("""insert into ums_profile_exports
            (export_reference, issuer_environment_ref, app_reference, subject_reference, state,
             requested_at, expires_at, artifact)
            values (?, ?, ?, ?, 'READY', ?, ?, ?::jsonb)""",
            reference, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            Timestamp.from(now), Timestamp.from(now.plus(ARTIFACT_LIFETIME)), mapper.writeValueAsString(artifact))
        jdbc.update("""insert into ums_profile_export_receipts
            (issuer_environment_ref, app_reference, subject_reference, actor_reference, idempotency_key,
             input_fingerprint, export_reference)
            values (?, ?, ?, ?, ?, ?, ?)""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            authority.actorReference, idempotencyKey, fingerprint, reference)
        ExportStatus(reference, "1", "ACCEPTED")
    }!!

    fun status(authority: TrustedExportAuthority, reference: UUID): ExportStatus = transactions.execute {
        authorize(authority)
        ExportStatus(reference, "1", stateOf(authority.scope, reference).openStatus())
    }!!

    /**
     * At most one live capability exists per export. A second capability would make "single
     * use" true only per token, and it would accumulate redeemable copies of the artifact.
     */
    fun capability(authority: TrustedExportAuthority, reference: UUID): ExportCapability = transactions.execute {
        authorize(authority)
        val now = clock.instant()
        val export = load(authority.scope, reference)
        check(export.state == ExportState.READY && now.isBefore(export.expiresAt)) { "Export is unavailable" }
        val outstanding = jdbc.queryForObject("""select count(*) from ums_profile_export_capabilities
            where export_reference = ? and consumed_at is null and expires_at > ?""",
            Int::class.java, reference, Timestamp.from(now)) ?: 0
        check(outstanding == 0) { "A download capability is already outstanding" }
        val token = ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expiresAt = now.plus(CAPABILITY_LIFETIME).truncatedTo(ChronoUnit.MICROS)
        jdbc.update("""insert into ums_profile_export_capabilities
            (capability_reference, export_reference, token_hash, expires_at, requested_at)
            values (?, ?, ?, ?, ?)""",
            UUID.randomUUID(), reference, sha256(token), Timestamp.from(expiresAt), Timestamp.from(now))
        ExportCapability(reference, token, expiresAt.toString())
    }!!

    /**
     * The capability authorizes the read; the caller's scope must still match the export, so a
     * token stolen by another app subject cannot be redeemed.
     */
    fun download(callerScope: ProfileScope, token: String): ExportArtifact = transactions.execute {
        val now = clock.instant()
        val row = jdbc.query("""select c.capability_reference, e.artifact::text
            from ums_profile_export_capabilities c join ums_profile_exports e using (export_reference)
            where c.token_hash = ? and c.consumed_at is null and c.expires_at > ?
            and e.state = 'READY' and e.expires_at > ?
            and e.issuer_environment_ref = ? and e.app_reference = ? and e.subject_reference = ?""",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) },
            sha256(token), Timestamp.from(now), Timestamp.from(now),
            callerScope.issuerEnvironmentRef, callerScope.appReference, callerScope.subjectReference)
            .singleOrNull() ?: throw IllegalStateException("Export download is unavailable")
        val consumed = jdbc.update("""update ums_profile_export_capabilities set consumed_at = ?
            where capability_reference = ? and consumed_at is null""",
            Timestamp.from(now), row.first)
        check(consumed == 1) { "Export download is unavailable" }
        mapper.readValue(row.second, ExportArtifact::class.java)
    }!!

    /**
     * Drops the payload and marks expired artifacts, then forgets expired capabilities. Safe to
     * run repeatedly; the scheduler calls it so an artifact dies even when nobody polls it.
     */
    fun sweep(): ExportSweep = transactions.execute {
        val now = Timestamp.from(clock.instant())
        val expired = jdbc.update("""update ums_profile_exports set state = 'EXPIRED', artifact = null
            where state = 'READY' and expires_at <= ?""", now)
        val capabilities = jdbc.update(
            "delete from ums_profile_export_capabilities where expires_at <= ?", now)
        ExportSweep(expired, capabilities)
    }!!

    private fun authorize(authority: TrustedExportAuthority) {
        val now = clock.instant()
        require(authority.strong && !authority.authenticatedAt.isAfter(now) && now.isBefore(authority.expiresAt)) {
            "Recent strong authentication is required"
        }
    }

    private fun load(scope: ProfileScope, reference: UUID): StoredExport =
        jdbc.query("""select state, expires_at from ums_profile_exports
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and export_reference = ?""",
            { rs, _ -> StoredExport(ExportState.valueOf(rs.getString(1)), rs.getTimestamp(2).toInstant()) },
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, reference)
            .singleOrNull() ?: throw IllegalStateException("Export is unavailable")

    private fun stateOf(scope: ProfileScope, reference: UUID): ExportState {
        if (!load(scope, reference).expiresAt.isAfter(clock.instant())) sweep()
        return load(scope, reference).state
    }

    private fun ExportState.openStatus(): String = when (this) {
        ExportState.READY -> "LOCAL_COMPLETED"
        ExportState.EXPIRED -> "EXPIRED"
        ExportState.FAILED, ExportState.INVALIDATED -> "FAILED"
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class StoredExport(val state: ExportState, val expiresAt: Instant)

    private companion object {
        val ARTIFACT_LIFETIME: Duration = Duration.ofHours(24)
        val CAPABILITY_LIFETIME: Duration = Duration.ofMinutes(5)
        val EXPORTED_FIELDS: Set<ProfileField> = setOf(
            ProfileField.DISPLAY_NAME,
            ProfileField.AVATAR_REFERENCE,
            ProfileField.DISPLAY_LOCALE,
            ProfileField.DISPLAY_TIMEZONE,
        )
    }
}

/**
 * Erasure and restriction must invalidate any exported copy of the same scope in the same
 * transaction, so no READY artifact can outlive the profile state it describes.
 */
internal fun invalidateExportsFor(jdbc: JdbcTemplate, scope: ProfileScope, now: Instant) {
    jdbc.update("""update ums_profile_exports set state = 'INVALIDATED', invalidated_at = ?, artifact = null
        where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and state = 'READY'""",
        Timestamp.from(now), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
}
