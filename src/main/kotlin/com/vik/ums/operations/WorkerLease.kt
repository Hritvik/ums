package com.vik.ums.operations

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * A database lease, so two workers running the same job do not produce duplicate transitions:
 * the loser skips the pass instead of waiting.
 */
class WorkerLease(private val jdbc: JdbcTemplate, private val clock: Clock) {
    fun tryAcquire(worker: String, ttl: Duration): UUID? {
        require(ttl > Duration.ZERO) { "Lease must be positive" }
        val holder = UUID.randomUUID()
        val now = clock.instant()
        val acquired = jdbc.update("""insert into ums_worker_lease (worker, holder, leased_until)
            values (?, ?, ?)
            on conflict (worker) do update set holder = excluded.holder,
                leased_until = excluded.leased_until
            where ums_worker_lease.leased_until <= ?""",
            worker, holder, Timestamp.from(now.plus(ttl)), Timestamp.from(now))
        return if (acquired == 1) holder else null
    }

    fun renew(worker: String, holder: UUID, ttl: Duration): Boolean =
        jdbc.update("""update ums_worker_lease set leased_until = ?
            where worker = ? and holder = ?""",
            Timestamp.from(clock.instant().plus(ttl)), worker, holder) == 1

    fun release(worker: String, holder: UUID) {
        jdbc.update("""update ums_worker_lease set leased_until = ?
            where worker = ? and holder = ?""", Timestamp.from(clock.instant()), worker, holder)
    }
}
