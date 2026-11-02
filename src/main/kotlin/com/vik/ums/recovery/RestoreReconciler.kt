package com.vik.ums.recovery

import com.vik.ums.privacy.invalidateExportsFor
import com.vik.ums.profiles.Lifecycle
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

data class ReconciliationResult(
    val entries: Int,
    val erased: Int,
    val alreadyErased: Int,
    val newerGeneration: Int,
    val missing: Int,
)

/**
 * Replays the independent erasure journal over whatever the database now holds. A restored
 * pre-deletion backup is brought back to the erased state; a profile that was legitimately
 * re-onboarded after the erasure (a newer generation) is left alone.
 */
class RestoreReconciler(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val journal: ErasureJournal,
    private val clock: Clock,
) {
    fun reconcile(): ReconciliationResult {
        val cursor = cursor()
        val entries = journal.entries()
            .distinctBy { it.feedReference }
            .filter { cursor == null || ORDER.compare(it, cursor) > 0 }
            .sortedWith(ORDER)
        var erased = 0
        var already = 0
        var newer = 0
        var missing = 0
        for (entry in entries) {
            when (transactions.execute { apply(entry) } ?: Outcome.MISSING) {
                Outcome.ERASED -> erased++
                Outcome.ALREADY_ERASED -> already++
                Outcome.NEWER_GENERATION -> newer++
                Outcome.MISSING -> missing++
            }
        }
        return ReconciliationResult(entries.size, erased, already, newer, missing)
    }

    private fun cursor(): ErasureEntry? = jdbc.query("""select last_erased_at, last_feed_reference
        from ums_erasure_reconciliation""", { rs: ResultSet, _: Int ->
        val at = rs.getTimestamp(1)?.toInstant() ?: return@query null
        val reference = rs.getObject(2, UUID::class.java) ?: return@query null
        ErasureEntry(reference, DUMMY_SCOPE, DUMMY, 1, 1, at)
    }, ).singleOrNull()

    private fun advance(entry: ErasureEntry) {
        jdbc.update("""insert into ums_erasure_reconciliation
            (singleton, last_erased_at, last_feed_reference, updated_at)
            values (true, ?, ?, current_timestamp)
            on conflict (singleton) do update set last_erased_at = excluded.last_erased_at,
                last_feed_reference = excluded.last_feed_reference, updated_at = excluded.updated_at""",
            Timestamp.from(entry.erasedAt), entry.feedReference)
    }

    private fun apply(entry: ErasureEntry): Outcome {
        val scope = entry.scope
        val current = jdbc.query("""select binding_revision, lifecycle, generation
            from ums_profile_bindings
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            for update""", { rs: ResultSet, _: Int ->
            val generation = rs.getLong(3).let { if (rs.wasNull()) null else it }
            Binding(rs.getLong(1), Lifecycle.valueOf(rs.getString(2)), generation)
        }, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference).singleOrNull()
            ?: run { advance(entry); return Outcome.MISSING }
        if (current.lifecycle == Lifecycle.ERASED) {
            advance(entry)
            return Outcome.ALREADY_ERASED
        }
        // The erasure retired one generation; a newer one is a deliberate re-onboarding.
        if (current.generation != entry.retiredGeneration) {
            advance(entry)
            return Outcome.NEWER_GENERATION
        }

        val revision = Math.addExact(current.bindingRevision, 1)
        jdbc.update("""update ums_profile_bindings set display_name = null, avatar_reference = null,
                display_locale = null, display_timezone = null, readiness = 'PROFILE_REQUIRED',
                lifecycle = 'ERASED', profile_reference = null, generation = null, profile_revision = null,
                closure_present = false, closure_at = null, closure_retain_until = null, closure_reference = null,
                completed_deletion_request_reference = null, binding_revision = ?
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            revision, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
        jdbc.update("""insert into ums_profile_erasure_obligations
            (fence_reference, issuer_environment_ref, app_reference, subject_reference, obligation_kind,
             cause_reference, retired_profile_reference, retired_generation, obligation_at,
             erased_binding_revision, erased_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (retired_profile_reference) do nothing""",
            UUID.randomUUID(), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            entry.obligationKind, entry.causeReference, entry.retiredProfileReference, entry.retiredGeneration,
            Timestamp.from(entry.erasedAt), revision, Timestamp.from(clock.instant()))
        // A restored pending request must not be finalizable or cancellable after the erasure.
        jdbc.update("""update ums_profile_deletion_requests set status = 'SUPERSEDED',
            operation_revision = operation_revision + 1
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and status = 'PENDING'""", scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
        jdbc.update("""delete from ums_profile_deletion_causes
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
        invalidateExportsFor(jdbc, scope, clock.instant())
        jdbc.update("""update ums_profile_notice_intents set state = 'SUPPRESSED', suppressed_at = ?
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and state in ('QUEUED', 'HANDED_OFF', 'FAILED')""",
            Timestamp.from(clock.instant()), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
        log.warn("Restore reconciliation erased a resurrected profile in app {} revision {}",
            scope.appReference, revision)
        advance(entry)
        return Outcome.ERASED
    }

    private data class Binding(val bindingRevision: Long, val lifecycle: Lifecycle, val generation: Long?)

    private enum class Outcome { ERASED, ALREADY_ERASED, NEWER_GENERATION, MISSING }

    private companion object {
        val ORDER: Comparator<ErasureEntry> =
            compareBy<ErasureEntry>({ it.erasedAt }, { it.feedReference })
        val DUMMY_SCOPE = com.vik.ums.profiles.ProfileScope(UUID(0, 0), UUID(0, 0), UUID(0, 0))
        val DUMMY: UUID = UUID(0, 0)
        val log = LoggerFactory.getLogger(RestoreReconciler::class.java)
    }
}
