package com.vik.ums.iam

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FileSnapshotCredentialsTest {
    @TempDir lateinit var directory: Path
    private val now = Instant.parse("2026-11-08T07:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun file(content: String) = directory.resolve("credential.json").also { it.toFile().writeText(content) }

    private fun valid() = """{"token":"token-value","audience":"${WorkloadTokenProfile.SNAPSHOT_AUDIENCE}",
        "scopes":["${WorkloadTokenProfile.SNAPSHOT_SCOPE}"],"expiresAt":"${now.plusSeconds(300)}"}"""

    @Test fun `a mounted credential is parsed`() {
        val credential = FileSnapshotCredentials(file(valid()), clock).credential()!!
        assertThat(credential.token).isEqualTo("token-value")
        assertThat(credential.audience).isEqualTo(WorkloadTokenProfile.SNAPSHOT_AUDIENCE)
        assertThat(credential.scopes).containsExactly(WorkloadTokenProfile.SNAPSHOT_SCOPE)
        assertThat(credential.expiresAt).isEqualTo(now.plusSeconds(300))
    }

    @Test fun `missing, malformed, expired and unknown-field files fail closed`() {
        assertThat(FileSnapshotCredentials(directory.resolve("absent.json"), clock).credential()).isNull()
        assertThat(FileSnapshotCredentials(file("not json"), clock).credential()).isNull()
        assertThat(FileSnapshotCredentials(file("""{"token":"t"}"""), clock).credential()).isNull()
        assertThat(FileSnapshotCredentials(file(valid().replace(now.plusSeconds(300).toString(), now.toString())), clock)
            .credential()).isNull()
        assertThat(FileSnapshotCredentials(file(valid().replace("\"token\"", "\"extra\":1,\"token\"")), clock)
            .credential()).isNull()
        assertThat(FileSnapshotCredentials(file(valid().replace("\"token-value\"", "\"\"")), clock).credential())
            .isNull()
    }
}
