package com.vik.ums.recovery

/**
 * Appends committed erasures to the journal and marks the feed row done. A failure leaves the
 * row pending and retried, so an acknowledged erasure is never silently missing from the
 * evidence; the journal append is forced to disk before the row is marked.
 */
class ErasureJournalFlusher(
    private val feed: ErasureFeed,
    private val journal: ErasureJournal,
    private val batchSize: Int = 100,
) {
    init { require(batchSize > 0) { "Batch size must be positive" } }

    /** Drains every pending append, not just one batch, before reconciliation runs. */
    fun flushAll(maxPasses: Int = 1000): Int {
        var total = 0
        repeat(maxPasses) {
            val appended = flush()
            total += appended
            if (appended == 0) return total
        }
        return total
    }

    fun hasPending(): Boolean = feed.pending(1).isNotEmpty()

    fun flush(): Int {
        var appended = 0
        for (entry in feed.pending(batchSize)) {
            try {
                journal.append(entry)
                feed.appended(entry.feedReference)
                appended++
            } catch (unavailable: Exception) {
                feed.failed(entry.feedReference)
            }
        }
        return appended
    }
}
