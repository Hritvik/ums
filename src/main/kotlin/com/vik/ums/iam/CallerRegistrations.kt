package com.vik.ums.iam

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Reviewed caller registrations, loaded from explicit deployment configuration. There is no
 * default and no wildcard: a deployment with no valid registrations cannot authenticate anyone.
 */
class CallerRegistrations(registrations: List<CallerRegistration>) : CallerRegistrationLookup {
    private val byPair = registrations.associateBy { it.appId to it.clientId }

    init {
        require(registrations.isNotEmpty()) { "At least one caller registration is required" }
        require(byPair.size == registrations.size) { "Caller registrations must be unique per app and client" }
        require(registrations.groupBy { it.appId }.values.all { group -> group.size == 1 }) {
            "An app may be called by only one registered client"
        }
    }

    override fun find(appId: String, clientId: String): CallerRegistration? = byPair[appId to clientId]

    companion object {
        private val mapper = ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .registerModule(KotlinModule.Builder().build())

        /** Parses the configured JSON array. Invalid configuration fails startup, never falls back. */
        fun parse(configured: String?): CallerRegistrations {
            require(!configured.isNullOrBlank()) { "Caller registrations must be configured" }
            val registrations = runCatching { mapper.readValue<List<CallerRegistration>>(configured) }
                .getOrElse { throw IllegalArgumentException("Caller registrations are malformed") }
            return CallerRegistrations(registrations)
        }
    }
}
