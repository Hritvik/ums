package com.vik.ums.outbox

import org.springframework.jdbc.core.JdbcTemplate
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
    private val transactions: TransactionTemplate,
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

    fun relayOnce(): RelayPass {
        val claimed = transactions.execute { claim() }!!
        var published = 0
        var failed = 0
        var quarantined = 0
        for (event in claimed) {
            try {
                transport.publish(event)
                markPublished(event.eventId)
                published++
            } catch (refused: OutboxTransportException) {
                if (recordFailure(event.eventId, refused.failureClass)) quarantined++ else failed++
            } catch (unexpected: Exception) {
                // Unknown transport failures still hold the lease, so the fact is retried rather
                // than dropped, and only a safe class reaches the row.
                if (recordFailure(event.eventId, OutboxFailureClass.TRANSPORT_UNAVAILABLE)) quarantined++ else failed++
            }
        }
        return RelayPass(published, failed, quarantined)
    }

    /** Only the head of each stream is claimed, so a stream can never be delivered out of order. */
    private fun claim(): List<OutboxEvent> {
        val now = clock.instant()
        val rows = jdbc.query("""select o.event_id, o.application_id, o.stream_id, o.stream_revision,
                o.event_type, o.payload::text
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
        rows.forEach {
            jdbc.update("""update ums_event_outbox set attempts = attempts + 1, next_attempt_at = ?
                where event_id = ?""", leaseUntil, it.eventId)
        }
        return rows
    }

    private fun markPublished(eventId: UUID) {
        jdbc.update("""update ums_event_outbox set published_at = ?, failure_class = null
            where event_id = ? and published_at is null""", Timestamp.from(clock.instant()), eventId)
    }

    /** Returns true when the row was quarantined by this failure. */
    private fun recordFailure(eventId: UUID, failureClass: OutboxFailureClass): Boolean = transactions.execute {
        val attempts = jdbc.query("""select attempts from ums_event_outbox
            where event_id = ? and published_at is null for update""",
            { rs: ResultSet, _: Int -> rs.getInt(1) }, eventId).singleOrNull()
            ?: return@execute false
        val now = clock.instant()
        if (attempts >= maxAttempts) {
            jdbc.update("""update ums_event_outbox set failure_class = ?, quarantined_at = ?,
                next_attempt_at = ? where event_id = ?""",
                failureClass.name, Timestamp.from(now), Timestamp.from(now), eventId)
            true
        } else {
            jdbc.update("""update ums_event_outbox set failure_class = ?, next_attempt_at = ?
                where event_id = ?""", failureClass.name, Timestamp.from(now.plus(backoff(attempts))), eventId)
            false
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
                count(*) filter (where published_at is null and quarantined_at is null and attempts > 0),
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
        val blocked = jdbc.queryForObject("""select count(distinct o.stream_id) from ums_event_outbox o
            where o.quarantined_at is not null
              and not exists (select 1 from ums_event_outbox earlier
                  where earlier.application_id = o.application_id
                    and earlier.stream_id = o.stream_id
                    and earlier.published_at is null
                    and earlier.stream_revision < o.stream_revision)""",
            Long::class.java) ?: 0L
        return OutboxStatus(
            unpublishedCount = counts.unpublished,
            oldestUnpublished = counts.oldest?.let { Duration.between(it, now) },
            retryingCount = counts.retrying,
            quarantinedCount = counts.quarantined,
            oldestQuarantine = counts.oldestQuarantine?.let { Duration.between(it, now) },
            blockedStreamCount = blocked,
        )
    }

    /**
     * Re-drives a quarantined fact once its cause is repaired. The identity and payload are
     * untouched, so the consumer still sees one event id.
     */
    fun requeue(eventId: UUID): Boolean =
        jdbc.update("""update ums_event_outbox set quarantined_at = null, failure_class = null,
            attempts = 0, next_attempt_at = ? where event_id = ? and quarantined_at is not null""",
            Timestamp.from(clock.instant()), eventId) == 1

    private fun event(rs: ResultSet): OutboxEvent = OutboxEvent(
        eventId = rs.getObject(1, UUID::class.java),
        applicationId = rs.getString(2),
        streamId = rs.getString(3),
        streamRevision = rs.getLong(4),
        eventType = rs.getString(5),
        payload = rs.getString(6),
    )

    private data class StatusRow(
        val unpublished: Long,
        val oldest: Instant?,
        val retrying: Long,
        val quarantined: Long,
        val oldestQuarantine: Instant?,
    )
}

/** A classified transport refusal; the message is a fixed class name, never a payload value. */
class OutboxTransportException(
    val failureClass: OutboxFailureClass,
    cause: Throwable? = null,
) : RuntimeException(failureClass.name, cause)
