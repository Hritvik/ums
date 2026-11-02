package com.vik.ums.outbox

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Drains the outbox at least once and in binding-revision order. A row is marked published only
 * after the transport accepts it, so a crash in between replays the fact as a harmless duplicate
 * instead of losing it. A repeatedly failing row is quarantined and holds only its own stream;
 * other bindings keep flowing.
 */
class OutboxRelay(
    private val jdbc: JdbcTemplate,
    transactions: TransactionTemplate,
    private val transport: OutboxTransport,
    private val clock: Clock,
    private val batchSize: Int = 20,
    private val maxAttempts: Int = 8,
    private val backoffBase: Duration = Duration.ofSeconds(30),
    private val backoffMax: Duration = Duration.ofHours(1),
    private val lease: Duration = Duration.ofMinutes(2),
) {
    init {
        require(batchSize > 0) { "Batch size must be positive" }
        require(maxAttempts > 0) { "Max attempts must be positive" }
        require(!backoffBase.isNegative && !backoffBase.isZero) { "Backoff base must be positive" }
        require(!backoffMax.isNegative && !backoffMax.isZero) { "Backoff maximum must be positive" }
        require(!lease.isNegative && !lease.isZero) { "Lease must be positive" }
    }

    /**
     * Claiming and failure bookkeeping always run in their own transaction, so no caller can
     * accidentally hold row locks across the transport call.
     */
    private val ownTransactions: TransactionTemplate =
        TransactionTemplate(transactions.transactionManager ?: error("Transaction manager is required"))
            .apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    fun relayOnce(): RelayPass {
        val claimed = ownTransactions.execute { claim() }!!
        var published = 0
        var failed = 0
        var quarantined = 0
        for (event in claimed) {
            try {
                transport.publish(event.event)
            } catch (refused: OutboxTransportException) {
                when (recordFailure(event, refused.failureClass)) {
                    FailureOutcome.QUARANTINED -> quarantined++
                    FailureOutcome.REQUEUED -> failed++
                    FailureOutcome.CLAIM_LOST -> Unit
                }
                continue
            } catch (unexpected: Exception) {
                // Unknown transport failures are retried, and only a safe class or the exception
                // type name ever leaves this method: never the payload or a message.
                log.warn("Outbox event {} failed with {}", event.event.eventId, unexpected.javaClass.simpleName)
                when (recordFailure(event, OutboxFailureClass.TRANSPORT_UNAVAILABLE)) {
                    FailureOutcome.QUARANTINED -> quarantined++
                    FailureOutcome.REQUEUED -> failed++
                    FailureOutcome.CLAIM_LOST -> Unit
                }
                continue
            }
            // A local write failure here is not a transport failure: it must not consume the
            // retry budget or quarantine a fact the consumer already accepted.
            if (markPublished(event)) published++
            else log.warn("Outbox event {} lost its claim before it could be marked", event.event.eventId)
        }
        return RelayPass(published, failed, quarantined)
    }

    /** Only the head of each stream is claimed, so a stream can never be delivered out of order. */
    private fun claim(): List<ClaimedEvent> {
        val now = clock.instant()
        val rows = jdbc.query("""select o.event_id, o.application_id, o.stream_id, o.stream_revision,
                o.event_type, o.payload::text, o.claims
            from ums_event_outbox o
            where o.published_at is null and o.quarantined_at is null and o.next_attempt_at <= ?
              and not exists (select 1 from ums_event_outbox earlier
                  where earlier.application_id = o.application_id
                    and earlier.stream_id = o.stream_id
                    and earlier.published_at is null
                    and earlier.stream_revision < o.stream_revision)
            order by o.occurred_at, o.event_id
            limit ?
            for update of o skip locked""",
            { rs: ResultSet, _: Int -> event(rs) },
            Timestamp.from(now), batchSize)
        val leaseUntil = Timestamp.from(now.plus(lease))
        val claimed = mutableListOf<ClaimedEvent>()
        rows.forEach { candidate ->
            if (candidate.claims >= maxAttempts) {
                // The row keeps coming back without ever recording a failure: a relay instance is
                // dying mid-pass. Bound that loop instead of retrying it forever.
                jdbc.update("""update ums_event_outbox set quarantined_at = ?, failure_class = ?,
                    claim_token = null where event_id = ?""",
                    Timestamp.from(now), OutboxFailureClass.RELAY_INTERRUPTED.name, candidate.event.eventId)
                return@forEach
            }
            val token = UUID.randomUUID()
            jdbc.update("""update ums_event_outbox set claim_token = ?, next_attempt_at = ?,
                claims = claims + 1 where event_id = ?""", token, leaseUntil, candidate.event.eventId)
            candidate.token = token
            claimed += candidate
        }
        return claimed
    }

    /** True only when this claim still owns the row; a stale claim cannot mark it published. */
    private fun markPublished(claimed: ClaimedEvent): Boolean =
        jdbc.update("""update ums_event_outbox set published_at = ?, failure_class = null, claim_token = null
            where event_id = ? and published_at is null and claim_token = ?""",
            Timestamp.from(clock.instant()), claimed.event.eventId, claimed.token) == 1

    private fun recordFailure(claimed: ClaimedEvent, failureClass: OutboxFailureClass): FailureOutcome =
        ownTransactions.execute {
            val attempts = jdbc.query("""select attempts from ums_event_outbox
                where event_id = ? and published_at is null and claim_token = ? for update""",
                { rs: ResultSet, _: Int -> rs.getInt(1) }, claimed.event.eventId, claimed.token)
                .singleOrNull() ?: return@execute FailureOutcome.CLAIM_LOST
            val now = clock.instant()
            val nextAttempt = attempts + 1
            if (nextAttempt >= maxAttempts) {
                jdbc.update("""update ums_event_outbox set failure_class = ?, quarantined_at = ?,
                    attempts = ?, next_attempt_at = ?, claim_token = null where event_id = ?""",
                    failureClass.name, Timestamp.from(now), nextAttempt, Timestamp.from(now), claimed.event.eventId)
                FailureOutcome.QUARANTINED
            } else {
                jdbc.update("""update ums_event_outbox set failure_class = ?, attempts = ?,
                    next_attempt_at = ?, claim_token = null where event_id = ?""",
                    failureClass.name, nextAttempt, Timestamp.from(now.plus(backoff(nextAttempt))), claimed.event.eventId)
                FailureOutcome.REQUEUED
            }
        }!!

    /** Bounded exponential backoff; the attempt count is never used as a metric label. */
    private fun backoff(attempts: Int): Duration {
        val exponent = minOf(maxOf(attempts - 1, 0), 20)
        val base = backoffBase.multipliedBy(1L shl exponent)
        return if (base > backoffMax) backoffMax else base
    }

    fun status(): OutboxStatus {
        val now = clock.instant()
        val counts = jdbc.queryForObject("""select
                count(*) filter (where published_at is null and quarantined_at is null),
                min(occurred_at) filter (where published_at is null and quarantined_at is null),
                count(*) filter (where published_at is null and quarantined_at is null
                    and (attempts > 0 or claims > 1)),
                count(*) filter (where quarantined_at is not null),
                min(quarantined_at) filter (where quarantined_at is not null)
            from ums_event_outbox""",
            { rs: ResultSet, _: Int -> StatusRow(
                rs.getLong(1),
                rs.getTimestamp(2)?.toInstant(),
                rs.getLong(3),
                rs.getLong(4),
                rs.getTimestamp(5)?.toInstant(),
            ) })!!
        val blocked = jdbc.queryForObject("""select count(distinct (o.application_id, o.stream_id))
            from ums_event_outbox o
            where o.quarantined_at is not null
              and not exists (select 1 from ums_event_outbox earlier
                  where earlier.application_id = o.application_id
                    and earlier.stream_id = o.stream_id
                    and earlier.published_at is null
                    and earlier.stream_revision < o.stream_revision)""",
            Long::class.java) ?: 0L
        return OutboxStatus(
            unpublishedCount = counts.unpublished,
            oldestUnpublished = counts.oldest?.let { age(it, now) },
            retryingCount = counts.retrying,
            quarantinedCount = counts.quarantined,
            oldestQuarantine = counts.oldestQuarantine?.let { age(it, now) },
            blockedStreamCount = blocked,
        )
    }

    /** The database clock can be slightly ahead of the application clock, so ages never go negative. */
    private fun age(since: Instant, now: Instant): Duration =
        Duration.between(since, now).let { if (it.isNegative) Duration.ZERO else it }

    /**
     * Re-drives a quarantined fact once its cause is repaired. The identity and payload are
     * untouched, so the consumer still sees one event id.
     */
    fun requeue(eventId: UUID): Boolean =
        jdbc.update("""update ums_event_outbox set quarantined_at = null, failure_class = null,
            attempts = 0, claims = 0, claim_token = null, next_attempt_at = ?
            where event_id = ? and quarantined_at is not null""",
            Timestamp.from(clock.instant()), eventId) == 1

    private fun event(rs: ResultSet): ClaimedEvent = ClaimedEvent(
        claims = rs.getInt(7),
        event = OutboxEvent(
            eventId = rs.getObject(1, UUID::class.java),
            applicationId = rs.getString(2),
            streamId = rs.getString(3),
            streamRevision = rs.getLong(4),
            eventType = rs.getString(5),
            payload = rs.getString(6),
        ),
    )

    private data class StatusRow(
        val unpublished: Long,
        val oldest: Instant?,
        val retrying: Long,
        val quarantined: Long,
        val oldestQuarantine: Instant?,
    )

    private class ClaimedEvent(val event: OutboxEvent, val claims: Int) { lateinit var token: UUID }

    private enum class FailureOutcome { REQUEUED, QUARANTINED, CLAIM_LOST }

    private companion object {
        val log = LoggerFactory.getLogger(OutboxRelay::class.java)
    }
}

/** A classified transport refusal; the message is a fixed class name, never a payload value. */
class OutboxTransportException(
    val failureClass: OutboxFailureClass,
    cause: Throwable? = null,
) : RuntimeException(failureClass.name, cause)
