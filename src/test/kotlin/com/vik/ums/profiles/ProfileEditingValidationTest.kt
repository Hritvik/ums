package com.vik.ums.profiles

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ProfileEditingValidationTest {
    private val catalogs = ProfileCatalogs("l1", setOf("en"), "t1", setOf("UTC"))
    private val validator = ProfileFieldValidation(catalogs)

    @Test fun `Unicode limits count codepoints and reject lexical controls before trimming`() {
        val supplementary = "\uD83D\uDE00".repeat(80)
        assertThat(validator.normalize(mapOf(ProfileField.DISPLAY_NAME to supplementary))[ProfileField.DISPLAY_NAME])
            .isEqualTo(supplementary)
        listOf("\uDC00", "\uD800x", "\u0000Name", "\u0085Name", "\u2028Name", "\u2066Name", " Name\t", supplementary + "x")
            .forEach { invalid ->
                assertThatThrownBy { validator.normalize(mapOf(ProfileField.DISPLAY_NAME to invalid)) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        assertThat(validator.normalize(mapOf(ProfileField.DISPLAY_NAME to "\u00a0Name\u00a0"))[ProfileField.DISPLAY_NAME])
            .isEqualTo("Name")
    }

    @Test fun `catalog validation checks lexical bounds and pinned semantic membership`() {
        listOf("x".repeat(65), "bad tag", "zz").forEach {
            assertThatThrownBy { validator.normalize(mapOf(ProfileField.DISPLAY_LOCALE to it)) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        listOf("x".repeat(129), "bad zone", "Mars/Test").forEach {
            assertThatThrownBy { validator.normalize(mapOf(ProfileField.DISPLAY_TIMEZONE to it)) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
        val policy = AppProfilePolicy(app, true, 1, emptySet(), emptySet(), "l1", "t2")
        assertThatThrownBy { catalogs.requirePolicy(policy) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `catalog and HMAC secrets are mandatory immutable snapshots`() {
        val locales = mutableSetOf("en")
        val timezones = mutableSetOf("UTC")
        val snapshot = ProfileCatalogs("l1", locales, "t1", timezones)
        locales.clear()
        timezones.clear()
        assertThat(snapshot.locales).containsExactly("en")
        assertThat(snapshot.timezones).containsExactly("UTC")
        listOf<() -> ProfileCatalogs>(
            { ProfileCatalogs("", setOf("en"), "t1", setOf("UTC")) },
            { ProfileCatalogs("x".repeat(65), setOf("en"), "t1", setOf("UTC")) },
            { ProfileCatalogs("l1", setOf("en"), "", setOf("UTC")) },
            { ProfileCatalogs("l1", setOf("en"), "x".repeat(65), setOf("UTC")) },
            { ProfileCatalogs("l1", emptySet(), "t1", setOf("UTC")) },
            { ProfileCatalogs("l1", setOf("en"), "t1", emptySet()) },
        ).forEach { invalid -> assertThatThrownBy { invalid() }.isInstanceOf(IllegalArgumentException::class.java) }
        assertThatThrownBy { HmacPatchFingerprint(ByteArray(31)) }.isInstanceOf(IllegalArgumentException::class.java)
        val key = ByteArray(32) { 7 }
        val hasher = HmacPatchFingerprint(key)
        val before = hasher.digest("value".toByteArray())
        key.fill(0)
        assertThat(hasher.digest("value".toByteArray())).isEqualTo(before).hasSize(64)
        assertThat(HmacPatchFingerprint(key).digest("value".toByteArray())).isNotEqualTo(before)
    }

    @Test fun `patch rejects negative versions and invalid correlation`() {
        assertThatThrownBy { ProfilePatchCommand(UUID.randomUUID(), -1, mapOf(ProfileField.DISPLAY_NAME to null), "trace") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ProfilePatchCommand(UUID.randomUUID(), 1, mapOf(ProfileField.DISPLAY_NAME to null), "bad correlation") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
