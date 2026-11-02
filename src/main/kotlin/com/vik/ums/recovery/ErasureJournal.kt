package com.vik.ums.recovery

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.profiles.ProfileScope
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * One minimized erasure fact: scope references and retired generation only. It never carries a
 * display value, so the journal itself is not a copy of the data it protects.
 */
data class ErasureEntry(
    val feedReference: UUID,
    val scope: ProfileScope,
    val retiredProfileReference: UUID,
    val retiredGeneration: Long,
    val erasedBindingRevision: Long,
    val erasedAt: Instant,
    val obligationKind: String = "APP_DELETION",
    val causeReference: UUID? = null,
    /** Monotonic feed order; the reconciliation cursor never depends on clocks. */
    val sequence: Long = 0,
)

/** Thrown when the evidence exists but cannot be trusted; the caller must fail closed. */
class ErasureJournalException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private const val HEADER = """{"version":1,"journal":"ums-erasure"}"""

/**
 * Append-only JSON-lines journal on a path the operator keeps independent of the database
 * backup. Each append is forced to disk before the feed row is marked appended.
 */
class ErasureJournal(val path: Path) {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    /**
     * Appends one fact. A torn trailing write from an earlier crash is truncated first, and the
     * new file's directory entry is forced, so a crash cannot leave a feed row marked appended
     * without a journal line.
     */
    fun append(entry: ErasureEntry) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val line = mapper.writeValueAsBytes(mapOf(
            "version" to 1,
            "feedReference" to entry.feedReference.toString(),
            "issuerEnvironmentRef" to entry.scope.issuerEnvironmentRef.toString(),
            "appReference" to entry.scope.appReference.toString(),
            "subjectReference" to entry.scope.subjectReference.toString(),
            "retiredProfileReference" to entry.retiredProfileReference.toString(),
            "retiredGeneration" to entry.retiredGeneration,
            "erasedBindingRevision" to entry.erasedBindingRevision,
            "erasedAt" to entry.erasedAt.toString(),
            "obligationKind" to entry.obligationKind,
            "causeReference" to entry.causeReference?.toString(),
            "sequence" to entry.sequence,
        ))
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            .use { channel ->
                requireHeader(channel)
                repairTornTail(channel)
                if (channel.size() == 0L) {
                    writeFully(channel, (HEADER + "\n").toByteArray())
                }
                channel.position(channel.size())
                writeFully(channel, line + "\n".toByteArray())
                channel.force(true)
            }
        syncDirectory()
    }

    fun entries(): List<ErasureEntry> {
        if (!Files.exists(path)) throw ErasureJournalException("Erasure journal is missing: $path")
        val content = try {
            Files.readString(path)
        } catch (unreadable: Exception) {
            throw ErasureJournalException("Erasure journal is unreadable: $path", unreadable)
        }
        if (content.isEmpty()) throw ErasureJournalException("Erasure journal is empty: $path")
        val lines = content.split('\n').dropLast(1)
        val header = lines.firstOrNull { it.isNotBlank() }
            ?: throw ErasureJournalException("Erasure journal has no header: $path")
        if (header != HEADER) throw ErasureJournalException("Erasure journal header is not recognised: $path")
        return lines.dropWhile { it.isBlank() || it == HEADER }.mapIndexedNotNull { index, line ->
            if (line.isBlank()) null else parse(line, index + 1)
        }
    }

    /** Appending to a file that is not this journal would silently lose the evidence. */
    private fun requireHeader(channel: FileChannel) {
        if (channel.size() == 0L) return
        val size = minOf(channel.size(), 128L).toInt()
        val buffer = ByteBuffer.allocate(size)
        var read = 0
        while (read < size) {
            val count = channel.read(buffer, read.toLong())
            if (count <= 0) break
            read += count
        }
        val firstLine = String(buffer.array(), 0, read).substringBefore('\n')
        if (firstLine != HEADER) {
            throw ErasureJournalException("Refusing to append to a file that is not the erasure journal: $path")
        }
    }

    private fun writeFully(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    /** Creates the header for a genuinely new deployment. */
    fun initialise() {
        Files.createDirectories(path.toAbsolutePath().parent)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            .use { channel ->
                if (channel.size() == 0L) writeFully(channel, (HEADER + "\n").toByteArray())
                channel.force(true)
            }
        syncDirectory()
    }

    /** A crash can leave a partial final line; it is dropped before the next append. */
    private fun repairTornTail(channel: FileChannel) {
        val size = channel.size()
        if (size == 0L) return
        val last = ByteBuffer.allocate(1)
        channel.read(last, size - 1)
        if (last.get(0) == '\n'.code.toByte()) return
        var position = size - 1
        while (position >= 0) {
            val start = maxOf(0L, position - 4095)
            val length = (position - start + 1).toInt()
            val window = ByteBuffer.allocate(length)
            var read = 0
            while (read < length) {
                val count = channel.read(window, start + read)
                if (count <= 0) break
                read += count
            }
            for (index in read - 1 downTo 0) {
                if (window.get(index) == '\n'.code.toByte()) {
                    channel.truncate(start + index + 1L)
                    return
                }
            }
            position = start - 1
        }
        channel.truncate(0)
    }

    private fun syncDirectory() {
        runCatching {
            FileChannel.open(path.toAbsolutePath().parent, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun parse(line: String, number: Int): ErasureEntry = try {
        val node = mapper.readTree(line)
        require(node["version"].asInt() == 1)
        ErasureEntry(
            feedReference = UUID.fromString(node["feedReference"].asText()),
            scope = ProfileScope(
                UUID.fromString(node["issuerEnvironmentRef"].asText()),
                UUID.fromString(node["appReference"].asText()),
                UUID.fromString(node["subjectReference"].asText()),
            ),
            retiredProfileReference = UUID.fromString(node["retiredProfileReference"].asText()),
            retiredGeneration = node["retiredGeneration"].asLong(),
            erasedBindingRevision = node["erasedBindingRevision"].asLong(),
            erasedAt = Instant.parse(node["erasedAt"].asText()),
            obligationKind = node["obligationKind"]?.asText() ?: "APP_DELETION",
            causeReference = node["causeReference"]?.takeUnless { it.isNull }?.asText()?.let(UUID::fromString),
            sequence = node["sequence"]?.asLong() ?: 0,
        )
    } catch (invalid: Exception) {
        throw ErasureJournalException("Erasure journal line $number is invalid", invalid)
    }
}

/** The transactional retry queue for journal appends. */
class ErasureFeed(private val jdbc: JdbcTemplate) {
    fun record(scope: ProfileScope, retiredProfileReference: UUID, retiredGeneration: Long,
               erasedBindingRevision: Long, erasedAt: Instant, obligationKind: String,
               causeReference: UUID?) {
        jdbc.update("""insert into ums_erasure_journal_feed
            (feed_reference, issuer_environment_ref, app_reference, subject_reference,
             retired_profile_reference, retired_generation, erased_binding_revision, erased_at,
             obligation_kind, cause_reference)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            UUID.randomUUID(), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            retiredProfileReference, retiredGeneration, erasedBindingRevision, Timestamp.from(erasedAt),
            obligationKind, causeReference)
    }

    fun pending(limit: Int): List<ErasureEntry> = jdbc.query("""select feed_reference, feed_sequence,
            issuer_environment_ref, app_reference, subject_reference, retired_profile_reference,
            retired_generation, erased_binding_revision, erased_at, obligation_kind, cause_reference
        from ums_erasure_journal_feed where appended_at is null
        order by feed_sequence limit ?""",
        { rs, _ -> ErasureEntry(
            rs.getObject(1, UUID::class.java),
            ProfileScope(rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java),
                rs.getObject(5, UUID::class.java)),
            rs.getObject(6, UUID::class.java),
            rs.getLong(7),
            rs.getLong(8),
            rs.getTimestamp(9).toInstant(),
            rs.getString(10),
            rs.getObject(11, UUID::class.java),
            rs.getLong(2),
        ) }, limit)

    fun appended(feedReference: UUID) {
        jdbc.update("update ums_erasure_journal_feed set appended_at = current_timestamp, failure_class = null where feed_reference = ?",
            feedReference)
    }

    fun failed(feedReference: UUID) {
        jdbc.update("""update ums_erasure_journal_feed set attempts = attempts + 1,
            failure_class = 'JOURNAL_UNAVAILABLE' where feed_reference = ?""", feedReference)
    }
}
