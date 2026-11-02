package com.vik.ums.privacy

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The 24-hour artifact promise is only true if something revokes it without being asked, so the
 * sweep runs on its own schedule instead of waiting for the next status read.
 */
@Component
@ConditionalOnProperty(name = ["ums.privacy.export-sweep.enabled"], havingValue = "true", matchIfMissing = true)
class ProfileExportSweepScheduler(
    private val exports: ProfileExportService,
    private val signals: ExportSweepSignals = ExportSweepSignals(),
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) {
    @Scheduled(
        fixedDelayString = "\${ums.privacy.export-sweep.interval:PT15M}",
        initialDelayString = "\${ums.privacy.export-sweep.initial-delay:PT1M}",
    )
    fun sweep() {
        try {
            val result = exports.sweep()
            signals.failed = false
            signals.lastCompletedAt = clock.instant()
            if (result.expiredExports > 0 || result.purgedCapabilities > 0) {
                log.info("Export sweep expired {} artifacts and purged {} capabilities",
                    result.expiredExports, result.purgedCapabilities)
            }
        } catch (unavailable: Exception) {
            signals.failed = true
            log.error("Export cleanup failed ({})", unavailable.javaClass.simpleName)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ProfileExportSweepScheduler::class.java)
    }
}
