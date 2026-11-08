package com.vik.ums.iam

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.net.URI
import java.nio.file.Path
import java.time.Duration

/**
 * The snapshot repair client is off unless a deployment supplies its endpoint, app registration and
 * mounted credential file. The credential is minted by IAM for a registered workload; UMS never
 * asks for a human token. See [IamSnapshotClient] for the fetch/retry rules.
 *
 * Deployment inputs: `ums.iam.snapshot.enabled`, `ums.iam.snapshot.base-uri`,
 * `ums.iam.snapshot.application-id`, `ums.iam.snapshot.credential-file`,
 * `ums.iam.snapshot.timeout-seconds` (default 10).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["ums.iam.snapshot.enabled"], havingValue = "true")
class IamSnapshotConfiguration {
    @Bean
    fun snapshotCredentialSupplier(environment: Environment, clock: java.time.Clock): SnapshotCredentialSupplier =
        FileSnapshotCredentials(
            Path.of(environment.getRequiredProperty("ums.iam.snapshot.credential-file")), clock)

    @Bean
    fun iamSnapshotClient(environment: Environment, credentials: SnapshotCredentialSupplier,
                          clock: java.time.Clock): IamSnapshotClient = IamSnapshotClient(
        baseUri = URI.create(environment.getRequiredProperty("ums.iam.snapshot.base-uri")),
        applicationId = environment.getRequiredProperty("ums.iam.snapshot.application-id"),
        credentials = credentials,
        clock = clock,
        timeout = Duration.ofSeconds(environment.getProperty("ums.iam.snapshot.timeout-seconds", "10").toLong()),
    )
}
