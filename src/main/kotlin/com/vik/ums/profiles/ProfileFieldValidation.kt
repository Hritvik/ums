package com.vik.ums.profiles

import com.vik.ums.content.ContentReference
import com.vik.ums.content.TrustedContentAttachment
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Key must come from a separately managed secret. No production key or default is provided. */
class HmacPatchFingerprint(key: ByteArray) {
    private val secret: SecretKeySpec
    init {
        require(key.size >= 32) { "Patch fingerprint key must contain at least 256 bits" }
        secret = SecretKeySpec(key.copyOf(), "HmacSHA256")
    }
    fun digest(content: ByteArray): String = Mac.getInstance("HmacSHA256").run {
        init(secret)
        doFinal(content).joinToString("") { "%02x".format(it) }
    }
}

internal class ProfileFieldValidation(private val catalogs: ProfileCatalogs) {
    fun normalize(changes: Map<ProfileField, String?>,
                  attachment: TrustedContentAttachment? = null): Map<ProfileField, String?> = changes.mapValues { (field, value) ->
        when {
            value == null -> null
            field == ProfileField.DISPLAY_NAME -> name(value)
            field == ProfileField.AVATAR_REFERENCE -> {
                require(ContentReference.PATTERN.matches(value)) { "Avatar reference is malformed" }
                require(attachment?.reference == value) {
                    "Avatar attachment requires accepted content authority"
                }
                value
            }
            field == ProfileField.DISPLAY_LOCALE -> {
                require(value.length <= 64 && value.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")) && value in catalogs.locales)
                value
            }
            else -> {
                require(value.length <= 128 && value.matches(Regex("[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+)*")) && value in catalogs.timezones)
                value
            }
        }
    }

    private fun name(value: String): String {
        require(wellFormed(value)) { "Display name must contain well-formed Unicode" }
        require(value.codePoints().allMatch { cp ->
            cp !in 0..31 && cp !in 127..159 && cp !in 0x2028..0x202e && cp !in 0x2066..0x2069
        }) { "Display name contains forbidden characters" }
        val normalized = Normalizer.normalize(value.trim { it.isWhitespace() || Character.isSpaceChar(it) }, Normalizer.Form.NFC)
        require(normalized.isNotEmpty() && normalized.codePointCount(0, normalized.length) <= 80)
        return normalized
    }

    private fun wellFormed(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                index += 2
            } else {
                if (Character.isLowSurrogate(char)) return false
                index++
            }
        }
        return true
    }
}
