package com.vik.ums.profiles

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ProfileInputValidationTest {
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
    private val policy = AppProfilePolicy(app, true, 1, emptySet(), emptySet(), "fixture", "fixture")
    private val fact = TrustedOnboardingFact(scope, "iam", UUID.randomUUID(), "stream", 1, "correlation")

    @Test
    fun `policy revisions and dataset references are bounded`() {
        listOf<() -> AppProfilePolicy>(
            { policy.copy(policyRevision = 0) },
            { policy.copy(supportedLocaleDataVersion = "") },
            { policy.copy(supportedLocaleDataVersion = "x".repeat(65)) },
            { policy.copy(supportedTimezoneDataVersion = " ") },
            { policy.copy(supportedTimezoneDataVersion = "x".repeat(65)) },
        ).forEach { invalid -> assertThatThrownBy { invalid() }.isInstanceOf(IllegalArgumentException::class.java) }
    }

    @Test
    fun `internal onboarding rejects unbounded or unsafe metadata and nonpositive source revisions`() {
        listOf<() -> TrustedOnboardingFact>(
            { fact.copy(producer = "") }, { fact.copy(producer = "x".repeat(129)) },
            { fact.copy(streamId = "bad stream") }, { fact.copy(streamId = "x".repeat(257)) },
            { fact.copy(sourceRevision = 0) },
            { fact.copy(correlationId = "email@example.com") },
            { fact.copy(correlationId = "x".repeat(129)) },
        ).forEach { invalid -> assertThatThrownBy { invalid() }.isInstanceOf(IllegalArgumentException::class.java) }
    }
}
