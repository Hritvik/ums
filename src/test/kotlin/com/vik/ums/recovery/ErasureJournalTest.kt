package com.vik.ums.recovery

import com.vik.ums.profiles.ProfileScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class ErasureJournalTest {
    @TempDir lateinit var directory: Path

    private fun entry() = ErasureEntry(UUID.randomUUID(),
        ProfileScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
        UUID.randomUUID(), 1, 2, Instant.parse("2026-11-02T09:00:00Z"))

    @Test fun `an empty file is not evidence`() {
        val path = directory.resolve("journal.jsonl")
        Files.writeString(path, "")

        assertThatThrownBy { ErasureJournal(path).entries() }
            .isInstanceOf(ErasureJournalException::class.java)
    }

    @Test fun `a header with no entries is a valid empty journal`() {
        val path = directory.resolve("journal.jsonl")
        Files.writeString(path, "{\"version\":1,\"journal\":\"ums-erasure\"}\n")

        assertThat(ErasureJournal(path).entries()).isEmpty()
    }

    @Test fun `a foreign header is refused on read and append`() {
        val path = directory.resolve("journal.jsonl")
        Files.writeString(path, "{\"version\":1,\"journal\":\"other\"}\n")
        val journal = ErasureJournal(path)

        assertThatThrownBy { journal.entries() }.isInstanceOf(ErasureJournalException::class.java)
        assertThatThrownBy { journal.append(entry()) }.isInstanceOf(ErasureJournalException::class.java)
    }

    @Test fun `a long torn tail is truncated without losing earlier entries`() {
        val journal = ErasureJournal(directory.resolve("journal.jsonl"))
        journal.append(entry())
        Files.writeString(journal.path, "x".repeat(9000), java.nio.file.StandardOpenOption.APPEND)

        journal.append(entry())

        assertThat(journal.entries()).hasSize(2)
    }

    @Test fun `the first append creates the header`() {
        val journal = ErasureJournal(directory.resolve("journal.jsonl"))
        journal.append(entry())

        assertThat(Files.readString(journal.path).lines().first())
            .isEqualTo("{\"version\":1,\"journal\":\"ums-erasure\"}")
    }
}
