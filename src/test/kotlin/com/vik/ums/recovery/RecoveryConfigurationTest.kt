package com.vik.ums.recovery

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.function.Supplier

class RecoveryConfigurationTest {
    private val dataSource = DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/ums", "ums", "ums")
    private val jdbc = JdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    @TempDir lateinit var directory: Path

    private fun context(properties: Map<String, Any>): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(MapPropertySource("test", properties))
        context.registerBean("jdbc", JdbcTemplate::class.java, Supplier { jdbc })
        context.registerBean("transactions", TransactionTemplate::class.java, Supplier { transactions })
        context.registerBean("clock", Clock::class.java, Supplier { Clock.systemUTC() })
        context.registerBean("mapper", com.fasterxml.jackson.databind.ObjectMapper::class.java, Supplier { mapper })
        context.environment.propertySources.addFirst(MapPropertySource("env", mapOf("ums.environment" to "test")))
        context.register(RecoveryConfiguration::class.java)
        return context
    }

    @Test fun `recovery beans build from configured paths`() {
        val context = context(mapOf(
            "ums.recovery.erasure-journal-path" to directory.resolve("journal.jsonl").toString(),
            "ums.recovery.backup-status-file" to directory.resolve("backup.json").toString(),
            "ums.recovery.allow-missing-evidence" to "true",
        ))
        context.refresh()

        assertThat(context.getBean(ErasureJournal::class.java).path).isEqualTo(directory.resolve("journal.jsonl"))
        assertThat(context.getBean(ErasureFeed::class.java)).isNotNull()
        assertThat(context.getBean(ErasureJournalFlusher::class.java)).isNotNull()
        assertThat(context.getBean(RestoreReconciler::class.java)).isNotNull()
        assertThat(context.getBean(BackupMonitor::class.java)).isNotNull()
        assertThat(context.getBean(RestoreReconciliationLifecycle::class.java)).isNotNull()
        context.close()
    }

    @Test fun `recovery beans are absent without evidence configuration`() {
        val context = context(emptyMap())
        context.refresh()

        assertThat(context.getBeanNamesForType(ErasureJournal::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(RestoreReconciler::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(BackupMonitor::class.java)).isEmpty()
        context.close()
    }

    @Test fun `backup gauges are unknown before the first check`() {
        val registry = SimpleMeterRegistry()
        RecoveryConfiguration().recoveryMetrics(BackupSignals()).bindTo(registry)

        assertThat(registry.get("ums.backup.age.seconds").gauge().value()).isNaN()
        assertThat(registry.get("ums.backup.failed").gauge().value()).isNaN()

        val signals = BackupSignals()
        signals.failed = true
        val failed = SimpleMeterRegistry()
        RecoveryConfiguration().recoveryMetrics(signals).bindTo(failed)
        assertThat(failed.get("ums.backup.failed").gauge().value()).isEqualTo(1.0)
    }
}
