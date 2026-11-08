package com.vik.ums.iam

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WorkloadRegistrationTest {
    private val valid = WorkloadRegistration(
        "wl_0123456789abcdefghijkL", "dutch-development", "iam-relay",
        setOf("https://ums.example.test/internal/iam/events"), setOf("ums:onboarding:write"),
    )

    @Test fun `a reviewed binding is accepted and looked up by all three keys`() {
        val registrations = WorkloadRegistrations(listOf(valid))
        assertThat(registrations.find("dutch-development", "iam-relay", "wl_0123456789abcdefghijkL"))
            .isEqualTo(valid)
        assertThat(registrations.find("dutch-development", "other-client", "wl_0123456789abcdefghijkL"))
            .isNull()
    }

    @Test fun `malformed bindings fail closed`() {
        assertThatThrownBy { valid.copy(workloadId = "wl_short") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { valid.copy(appRegistrationId = "Upper") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { valid.copy(clientId = "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { valid.copy(audiences = emptySet()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { valid.copy(audiences = setOf("http://insecure")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { valid.copy(scopes = emptySet()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { valid.copy(scopes = setOf("Bad Scope")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WorkloadRegistrations(listOf(valid, valid)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WorkloadRegistrations.parse("""[{"unknown":true}]""") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
