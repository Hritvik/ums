package com.vik.ums.operations

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.function.Supplier

class OperationsConfigurationTest {
    private val dataSource = DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/ums", "ums", "ums")
    private val jdbc = JdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val mapper = jacksonObjectMapper()

    private fun context(properties: Map<String, Any>): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(MapPropertySource("test", properties))
        context.registerBean("jdbc", JdbcTemplate::class.java, Supplier { jdbc })
        context.registerBean("transactions", TransactionTemplate::class.java, Supplier { transactions })
        context.registerBean("mapper", com.fasterxml.jackson.databind.ObjectMapper::class.java, Supplier { mapper })
        context.registerBean("clock", Clock::class.java, Supplier { Clock.systemUTC() })
        context.register(OperationsConfiguration::class.java)
        context.registerBean("retentionSignals", com.vik.ums.retention.RetentionSignals::class.java,
            Supplier { com.vik.ums.retention.RetentionSignals() })
        context.registerBean("backupSignals", com.vik.ums.recovery.BackupSignals::class.java,
            Supplier { com.vik.ums.recovery.BackupSignals() })
        return context
    }

    @Test fun `operations beans build and the worker is enabled by default`() {
        val context = context(mapOf("ums.environment" to "test"))
        context.refresh()

        assertThat(context.getBean(MaintenanceWorker::class.java)).isNotNull()
        assertThat(context.getBean(WorkerLease::class.java)).isNotNull()
        assertThat(context.getBean(OperationalAlerts::class.java)).isNotNull()
        assertThat(context.getBean(MaintenanceScheduler::class.java)).isNotNull()
        assertThat(context.getBean(AlertScheduler::class.java)).isNotNull()
        assertThat(context.getBean(AuthorizationFailures::class.java)).isNotNull()
        assertThat(context.getBean(com.vik.ums.privacy.DueDeletionBatchService::class.java)).isNotNull()
        assertThat(context.getBean(com.vik.ums.notices.ProfileNoticeService::class.java)).isNotNull()

        val registry = SimpleMeterRegistry()
        context.getBean(io.micrometer.core.instrument.binder.MeterBinder::class.java).bindTo(registry)
        assertThat(registry.meters.map { it.id.name })
            .contains("ums.alert.overdue.erasures", "ums.alert.poisoned.outbox", "ums.alert.backup.failed")
        context.close()
    }

    @Test fun `the maintenance worker can be disabled`() {
        val context = context(mapOf("ums.environment" to "test", "ums.maintenance.enabled" to "false"))
        context.refresh()

        assertThat(context.getBeanNamesForType(MaintenanceWorker::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(MaintenanceScheduler::class.java)).isEmpty()
        context.close()
    }
}
