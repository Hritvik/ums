package com.vik.ums.profiles

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.Collections
import java.util.UUID

enum class HumanAction { READ, EDIT }

/** Already resolved local authority; not a token, IAM claim schema, or production ingress. */
class TrustedHumanAuthority(val scope: ProfileScope, val actorReference: UUID, actions: Set<HumanAction>) {
    val actions: Set<HumanAction> = Collections.unmodifiableSet(actions.toSet())
    fun copy(scope: ProfileScope = this.scope, actorReference: UUID = this.actorReference,
             actions: Set<HumanAction> = this.actions): TrustedHumanAuthority = TrustedHumanAuthority(scope, actorReference, actions)
}

class TrustedProjectionAuthority(
    val app: AppScope, val purpose: String, permittedTargets: Set<UUID>, allowedFields: Set<ProfileField>,
) {
    val permittedTargets: Set<UUID> = Collections.unmodifiableSet(permittedTargets.toSet())
    val allowedFields: Set<ProfileField> = Collections.unmodifiableSet(allowedFields.toSet())
    fun copy(app: AppScope = this.app, purpose: String = this.purpose,
             permittedTargets: Set<UUID> = this.permittedTargets, allowedFields: Set<ProfileField> = this.allowedFields) =
        TrustedProjectionAuthority(app, purpose, permittedTargets, allowedFields)
}

/** Missing key = unchanged; null = explicit clear; string = explicit set. */
class ProfilePatchCommand(
    val idempotencyKey: UUID, val expectedVersion: Long, changes: Map<ProfileField, String?>, val correlationId: String,
) {
    val changes: Map<ProfileField, String?> = Collections.unmodifiableMap(changes.toMap())
    init {
        require(expectedVersion >= 0)
        require(changes.isNotEmpty())
        require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
    }
}

class ProfileCatalogs(
    val localeVersion: String, locales: Set<String>, val timezoneVersion: String, timezones: Set<String>,
) {
    val locales: Set<String> = Collections.unmodifiableSet(locales.toSet())
    val timezones: Set<String> = Collections.unmodifiableSet(timezones.toSet())
    init {
        require(localeVersion.isNotBlank() && localeVersion.length <= 64)
        require(timezoneVersion.isNotBlank() && timezoneVersion.length <= 64)
        require(locales.isNotEmpty() && timezones.isNotEmpty())
    }
    fun requirePolicy(policy: AppProfilePolicy) {
        require(policy.supportedLocaleDataVersion == localeVersion && policy.supportedTimezoneDataVersion == timezoneVersion) {
            "Profile policy requires different validation catalogs"
        }
    }
}

data class PatchOutcome(val profileReference: UUID, val appliedRevision: String)
data class ProfileDisplay(val profileReference: UUID, val profileRevision: String,
                          val fields: Map<String, String>, val displayNameSource: String)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DisplayReadResult(val profileReference: UUID, val status: String, val display: ProfileDisplay? = null)
data class DisplayReadResponse(val results: List<DisplayReadResult>)
