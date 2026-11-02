package com.vik.ums.retention

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration

/**
 * The declared retention schedule, in one place. Every sweep below names its table and period
 * here, so changing a period means changing one constant and its documentation.
 */
object RetentionSchedule {
    /** Export artifact and its status row: at most a day, unless a replay receipt needs it. */
    val EXPORT_ARTIFACT: Duration = Duration.ofHours(24)

    /** Download capabilities live five minutes; the row is cleaned an hour after it expires. */
    val EXPORT_CAPABILITY: Duration = Duration.ofHours(1)

    /** Command idempotency records: seven days of safe replay. */
    val COMMAND_RECEIPT: Duration = Duration.ofDays(7)

    /** Published outbox facts and processed inbox dedupe rows: seven days. */
    val DELIVERED_EVENT: Duration = Duration.ofDays(7)

    /** Minimal security/lifecycle audit: one year, access controlled. */
    val AUDIT: Duration = Duration.ofDays(365)
}

/** Per-class outcome: swept rows, rows kept on purpose, and rows whose deletion failed. */
data class SweepClassResult(val name: String, val swept: Int, val retained: Int, val failed: Int)

data class RetentionReport(val classes: List<SweepClassResult>) {
    val swept: Int get() = classes.sumOf { it.swept }
    val retained: Int get() = classes.sumOf { it.retained }
    val failed: Int get() = classes.sumOf { it.failed }
}

/** One declared sweep: what to select, what to keep, and the table its keys belong to. */
internal data class RetentionClass(
    val name: String,
    val retention: Duration,
    val table: String?,
    val keyColumns: List<String>,
    val candidatesSql: String,
    val retainedSql: String? = null,
    val retainedUsesCutoff: Boolean = true,
)

/**
 * Removes records whose declared lifetime has elapsed, in bounded batches, one transaction per
 * record so a single failure cannot block the rest of the sweep. Erasure fences and the minimal
 * audit are never part of a delete rule; the onboarding receipt is a replay fence and is only
 * reported as retained.
 */
class RetentionSweeper(
    private val jdbc: JdbcTemplate,
    transactions: TransactionTemplate,
    private val clock: Clock,
    private val batchSize: Int = 100,
) {
    init { require(batchSize > 0) { "Batch size must be positive" } }

    private val ownTransactions: TransactionTemplate =
        TransactionTemplate(transactions.transactionManager ?: error("Transaction manager is required"))
            .apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    private val deleter: (String, List<Any?>) -> Int = { sql, args -> jdbc.update(sql, *args.toTypedArray()) }

    fun sweepAll(): RetentionReport = RetentionReport(rules().map { sweep(it, deleter) })

    internal fun rules(): List<RetentionClass> = listOf(
            // Capabilities first: they are the only children of an export that never need a receipt.
            RetentionClass(
                name = "export_capabilities",
                retention = RetentionSchedule.EXPORT_CAPABILITY,
                table = "ums_profile_export_capabilities",
                keyColumns = listOf("capability_reference"),
                candidatesSql = """select capability_reference from ums_profile_export_capabilities
                    where expires_at < ? order by expires_at, capability_reference limit ?""",
            ),
            // An export status row lives a day, unless a command receipt still needs it for replay.
            RetentionClass(
                name = "exports",
                retention = RetentionSchedule.EXPORT_ARTIFACT,
                table = "ums_profile_exports",
                keyColumns = listOf("export_reference"),
                candidatesSql = """select e.export_reference from ums_profile_exports e
                    where e.requested_at < ? and not exists (
                        select 1 from ums_profile_export_receipts r
                        where r.export_reference = e.export_reference)
                    order by e.requested_at, e.export_reference limit ?""",
                retainedSql = """select count(*) from ums_profile_exports e
                    where e.requested_at < ? and exists (
                        select 1 from ums_profile_export_receipts r
                        where r.export_reference = e.export_reference)""",
            ),
            RetentionClass(
                name = "export_receipts",
                retention = RetentionSchedule.COMMAND_RECEIPT,
                table = "ums_profile_export_receipts",
                keyColumns = listOf("issuer_environment_ref", "app_reference", "subject_reference",
                    "actor_reference", "idempotency_key"),
                candidatesSql = """select issuer_environment_ref, app_reference, subject_reference,
                        actor_reference, idempotency_key
                    from ums_profile_export_receipts
                    where export_reference in (select export_reference from ums_profile_exports where requested_at < ?)
                    order by idempotency_key limit ?""",
            ),
            RetentionClass(
                name = "patch_receipts",
                retention = RetentionSchedule.COMMAND_RECEIPT,
                table = "ums_profile_patch_receipts",
                keyColumns = listOf("issuer_environment_ref", "app_reference", "subject_reference",
                    "actor_reference", "operation", "idempotency_key"),
                candidatesSql = """select issuer_environment_ref, app_reference, subject_reference,
                        actor_reference, operation, idempotency_key
                    from ums_profile_patch_receipts
                    where accepted_at < ? order by accepted_at, idempotency_key limit ?""",
            ),
            RetentionClass(
                name = "privacy_receipts",
                retention = RetentionSchedule.COMMAND_RECEIPT,
                table = "ums_profile_privacy_receipts",
                keyColumns = listOf("issuer_environment_ref", "app_reference", "subject_reference",
                    "actor_reference", "operation", "idempotency_key"),
                candidatesSql = """select issuer_environment_ref, app_reference, subject_reference,
                        actor_reference, operation, idempotency_key
                    from ums_profile_privacy_receipts
                    where accepted_at < ? order by accepted_at, idempotency_key limit ?""",
            ),
            RetentionClass(
                name = "published_outbox",
                retention = RetentionSchedule.DELIVERED_EVENT,
                table = "ums_event_outbox",
                keyColumns = listOf("event_id"),
                candidatesSql = """select event_id from ums_event_outbox
                    where published_at is not null and published_at < ?
                    order by published_at, event_id limit ?""",
            ),
            RetentionClass(
                name = "processed_inbox",
                retention = RetentionSchedule.DELIVERED_EVENT,
                table = "ums_event_inbox",
                keyColumns = listOf("producer", "event_id"),
                candidatesSql = """select producer, event_id from ums_event_inbox
                    where processed_at < ? order by processed_at, event_id limit ?""",
            ),
            RetentionClass(
                name = "privacy_audit",
                retention = RetentionSchedule.AUDIT,
                table = "ums_profile_privacy_audit",
                keyColumns = listOf("audit_reference"),
                candidatesSql = """select audit_reference from ums_profile_privacy_audit
                    where occurred_at < ? order by occurred_at, audit_reference limit ?""",
            ),
            RetentionClass(
                name = "lifecycle_evidence",
                retention = RetentionSchedule.AUDIT,
                table = "ums_profile_lifecycle_evidence",
                keyColumns = listOf("evidence_reference"),
                candidatesSql = """select evidence_reference from ums_profile_lifecycle_evidence
                    where occurred_at < ? order by occurred_at, evidence_reference limit ?""",
            ),
            // Onboarding receipts are the replay fence: retained while the binding can still
            // receive traffic, and reported rather than deleted.
            RetentionClass(
                name = "onboarding_receipts",
                retention = RetentionSchedule.DELIVERED_EVENT,
                table = null,
                keyColumns = emptyList(),
                candidatesSql = "",
                retainedSql = """select count(*) from ums_iam_onboarding_receipts where accepted_at < ?""",
            ),
            // Erasure fences are safety metadata, not operational history. They are reported so
            // an operator can see the retained set, and they are never deleted by a sweep.
            RetentionClass(
                name = "erasure_fences",
                retention = RetentionSchedule.AUDIT,
                table = null,
                keyColumns = emptyList(),
                candidatesSql = "",
                retainedSql = "select count(*) from ums_profile_erasure_obligations",
                retainedUsesCutoff = false,
            ),
        )

    /** Swaps the per-row delete, so a test can prove that one failure does not stop the rest. */
    internal fun sweepWith(deleter: (String, List<Any?>) -> Int): RetentionReport =
        RetentionReport(rules().map { sweep(it, deleter) })

    internal fun sweep(rule: RetentionClass, delete: (String, List<Any?>) -> Int): SweepClassResult {
        val cutoff = Timestamp.from(clock.instant().minus(rule.retention))
        val retained = rule.retainedSql?.let {
            if (rule.retainedUsesCutoff) jdbc.queryForObject(it, Long::class.java, cutoff) ?: 0L
            else jdbc.queryForObject(it, Long::class.java) ?: 0L
        }?.toInt() ?: 0
        if (rule.table == null) return SweepClassResult(rule.name, 0, retained, 0)

        val keys = jdbc.query(rule.candidatesSql, { rs, _ ->
            rule.keyColumns.map { rs.getObject(it) }
        }, cutoff, batchSize)
        var swept = 0
        var failed = 0
        for (key in keys) {
            val sql = "delete from ${rule.table} where " +
                rule.keyColumns.joinToString(" and ") { "$it = ?" }
            val removed = try {
                ownTransactions.execute { delete(sql, key) } ?: 0
            } catch (unavailable: Exception) {
                failed++
                continue
            }
            swept += removed
        }
        return SweepClassResult(rule.name, swept, retained, failed)
    }
}
