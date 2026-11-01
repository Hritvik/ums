package com.vik.ums.profiles

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/** Explicit synchronous transactions; no reactive or coroutine propagation is introduced. */
@Configuration(proxyBeanMethods = false)
class ProfileServiceConfiguration {
    @Bean
    fun transactionTemplate(dataSource: DataSource): TransactionTemplate =
        TransactionTemplate(DataSourceTransactionManager(dataSource))

    @Bean
    fun profileEditingService(jdbc: JdbcTemplate, transactions: TransactionTemplate,
                              mapper: ObjectMapper, environment: Environment): ProfileEditingService =
        ProfileEditingService(
            jdbc, transactions, mapper, environment.getRequiredProperty("ums.environment"),
            ProfileCatalogs(
                environment.getRequiredProperty("ums.profile.locale-version"),
                split(environment.getRequiredProperty("ums.profile.locales")),
                environment.getRequiredProperty("ums.profile.timezone-version"),
                split(environment.getRequiredProperty("ums.profile.timezones")),
            ),
            HmacPatchFingerprint(java.util.Base64.getDecoder()
                .decode(environment.getRequiredProperty("ums.profile.receipt-key"))),
        )

    private fun split(value: String): Set<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    @Bean
    fun profileProvisioningService(jdbc: JdbcTemplate, transactions: TransactionTemplate,
                                   mapper: ObjectMapper, environment: Environment): ProfileProvisioningService =
        ProfileProvisioningService(jdbc, transactions, mapper,
            environment.getRequiredProperty("ums.environment"))
}
