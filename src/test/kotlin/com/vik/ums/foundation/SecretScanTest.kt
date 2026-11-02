package com.vik.ums.foundation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * A build-time secret check over the shipped sources and contracts. External scanners run in the
 * release gate; this catches the common leak classes before a commit.
 */
class SecretScanTest {
    private val patterns = listOf(
        Regex("-----BEGIN (RSA|EC|OPENSSH|DSA) PRIVATE KEY-----"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("ghp_[A-Za-z0-9]{36}"),
        Regex("xox[baprs]-[A-Za-z0-9-]{10,}"),
        Regex("sk-[A-Za-z0-9]{32,}"),
        Regex("eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\."),
    )

    @Test fun `shipped sources and contracts carry no secret material`() {
        val roots = listOf(Path.of("src", "main"), Path.of("contracts"))
        val findings = mutableListOf<String>()
        for (root in roots) {
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && !it.toString().contains("__pycache__") }
                        .forEach { file ->
                    if (file.toString().contains("${Path.of("contracts", "v1", "fixtures")}")) return@forEach
                    val text = runCatching { Files.readString(file) }.getOrNull() ?: return@forEach
                    patterns.forEach { pattern ->
                        if (pattern.containsMatchIn(text)) findings += "${file}: ${pattern.pattern}"
                    }
                }
            }
        }
        assertThat(findings).isEmpty()
    }
}
