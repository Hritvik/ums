package com.vik.ums.iam

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * One reviewed workload binding for the IAM `workload_access` v1 profile. A token is accepted only
 * when its `sub`, `app_id`, `client_id`, audience and scopes all match one registered binding.
 */
data class WorkloadRegistration(
    val workloadId: String,
    val appRegistrationId: String,
    val clientId: String,
    val audiences: Set<String>,
    val scopes: Set<String>,
) {
    init {
        require(workloadId.matches(WorkloadTokenProfile.WORKLOAD_ID)) { "Workload id is malformed" }
        require(appRegistrationId.matches(WorkloadTokenProfile.APP_ID)) { "App registration is malformed" }
        require(clientId.matches(WorkloadTokenProfile.CLIENT_ID)) { "Client id is malformed" }
        require(audiences.isNotEmpty() && audiences.all { it.matches(WorkloadTokenProfile.AUDIENCE) }) {
            "Workload audiences must be a non-empty set of HTTPS URIs"
        }
        require(scopes.isNotEmpty() && scopes.all { it.matches(WorkloadTokenProfile.SCOPE) }) {
            "Workload scopes must be a non-empty bounded set"
        }
    }
}

/** Reviewed registrations loaded from deployment configuration; there is no default or wildcard. */
class WorkloadRegistrations(registrations: List<WorkloadRegistration>) {
    private val byBinding = registrations.associateBy {
        Triple(it.appRegistrationId, it.clientId, it.workloadId)
    }

    init {
        require(registrations.isNotEmpty()) { "At least one workload registration is required" }
        require(byBinding.size == registrations.size) { "Workload registrations must be unique" }
    }

    fun find(appRegistrationId: String, clientId: String, workloadId: String): WorkloadRegistration? =
        byBinding[Triple(appRegistrationId, clientId, workloadId)]

    companion object {
        private val mapper = ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .registerModule(KotlinModule.Builder().build())

        /** Parses the configured JSON array. Invalid configuration fails startup, never falls back. */
        fun parse(configured: String?): WorkloadRegistrations {
            require(!configured.isNullOrBlank()) { "Workload registrations must be configured" }
            val registrations = runCatching { mapper.readValue<List<WorkloadRegistration>>(configured) }
                .getOrElse { throw IllegalArgumentException("Workload registrations are malformed") }
            return WorkloadRegistrations(registrations)
        }
    }
}
