package com.vik.ums.iam

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock

class IamSnapshotConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(IamSnapshotConfiguration::class.java)
        .withBean(Clock::class.java, { Clock.systemUTC() })

    @Test fun `the client is wired only when enabled and fully configured`() {
        runner.withPropertyValues(
            "ums.iam.snapshot.enabled=true",
            "ums.iam.snapshot.base-uri=https://iam.example.test/",
            "ums.iam.snapshot.application-id=dutch-development",
            "ums.iam.snapshot.credential-file=/run/secrets/ums-snapshot.json",
        ).run { context ->
            assertThat(context).hasSingleBean(IamSnapshotClient::class.java)
            assertThat(context).hasSingleBean(SnapshotCredentialSupplier::class.java)
        }
    }

    @Test fun `the client is absent when disabled or misconfigured`() {
        runner.run { context ->
            assertThat(context).doesNotHaveBean(IamSnapshotClient::class.java)
        }
        runner.withPropertyValues(
            "ums.iam.snapshot.enabled=true",
            "ums.iam.snapshot.application-id=dutch-development",
        ).run { context ->
            assertThat(context).hasFailed()
        }
    }
}
