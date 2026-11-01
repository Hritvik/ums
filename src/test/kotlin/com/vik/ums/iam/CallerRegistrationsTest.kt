package com.vik.ums.iam

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CallerRegistrationsTest {
    private val valid = """[{"appId":"dutch-development","clientId":"dutch-mobile",
        "audience":"https://ums.example.test","allowedScopes":["profile:read"],"requiresDpop":true}]"""

    @Test fun `a configured registration is found only for its exact pair`() {
        val registrations = CallerRegistrations.parse(valid)

        assertThat(registrations.find("dutch-development", "dutch-mobile")).isNotNull
        assertThat(registrations.find("dutch-development", "other-client")).isNull()
        assertThat(registrations.find("other-app", "dutch-mobile")).isNull()
    }

    @Test fun `missing malformed or open-ended configuration fails closed`() {
        assertThatThrownBy { CallerRegistrations.parse(null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CallerRegistrations.parse(" ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CallerRegistrations.parse("[]") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CallerRegistrations.parse("not json") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CallerRegistrations.parse(valid.replace("\"requiresDpop\":true", "\"requiresDpop\":true,\"wildcard\":\"*\""))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `duplicate or multiply-registered callers are refused`() {
        assertThatThrownBy {
            CallerRegistrations.parse("""[{"appId":"dutch-development","clientId":"dutch-mobile","audience":"https://ums.example.test","allowedScopes":["profile:read"],"requiresDpop":true},{"appId":"dutch-development","clientId":"dutch-mobile","audience":"https://ums.example.test","allowedScopes":["profile:read"],"requiresDpop":true}]""")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CallerRegistrations.parse("""[{"appId":"dutch-development","clientId":"dutch-mobile","audience":"https://ums.example.test","allowedScopes":["profile:read"],"requiresDpop":true},{"appId":"dutch-development","clientId":"other-client","audience":"https://ums.example.test","allowedScopes":["profile:read"],"requiresDpop":true}]""")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
