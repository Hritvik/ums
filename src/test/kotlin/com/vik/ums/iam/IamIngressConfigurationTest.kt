package com.vik.ums.iam

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class IamIngressConfigurationTest {
    private val source = TrustedIamSource(UUID.randomUUID(), "test")
    private val configuration = IamIngressConfiguration()

    @Test fun `a reviewed registration map becomes an app scope`() {
        val reference = UUID.randomUUID()
        val parsed = configuration.appRegistrations("""{"dutch-development":"$reference"}""", source)
        assertThat(parsed).containsOnlyKeys("dutch-development")
        assertThat(parsed["dutch-development"]!!.issuerEnvironmentRef).isEqualTo(source.issuerEnvironmentRef)
        assertThat(parsed["dutch-development"]!!.appReference).isEqualTo(reference)
    }

    @Test fun `malformed, empty and foreign registrations fail closed`() {
        assertThatThrownBy { configuration.appRegistrations("not json", source) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { configuration.appRegistrations("{}", source) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { configuration.appRegistrations("""{"UPPER":"${UUID.randomUUID()}"}""", source) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { configuration.appRegistrations("""{"dutch-development":"not-a-uuid"}""", source) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
