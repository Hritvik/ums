package com.vik.ums.profiles

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import java.util.UUID

/** These references are app-qualified, never IAM global Human Identity identifiers. */
data class AppScope(val issuerEnvironmentRef: UUID, val appReference: UUID)
data class ProfileScope(val issuerEnvironmentRef: UUID, val appReference: UUID, val subjectReference: UUID) {
    fun application(): AppScope = AppScope(issuerEnvironmentRef, appReference)
}

enum class ProfileField(@get:JsonValue val wireName: String) {
    DISPLAY_NAME("displayName"), AVATAR_REFERENCE("avatarReference"),
    DISPLAY_LOCALE("displayLocale"), DISPLAY_TIMEZONE("displayTimezone"),
}
enum class Readiness { PROFILE_REQUIRED, PROFILE_READY }
enum class Lifecycle { ACTIVE, CLOSED, DELETION_PENDING, ERASED }

data class AppProfilePolicy(
    val app: AppScope,
    val adopted: Boolean,
    val policyRevision: Long,
    val requiredFields: Set<ProfileField>,
    val humanEditableFields: Set<ProfileField>,
    val supportedLocaleDataVersion: String,
    val supportedTimezoneDataVersion: String,
) {
    init {
        require(policyRevision > 0) { "Policy revision must be positive" }
        require(supportedLocaleDataVersion.isNotBlank() && supportedLocaleDataVersion.length <= 64)
        require(supportedTimezoneDataVersion.isNotBlank() && supportedTimezoneDataVersion.length <= 64)
    }
}

/** Internal trusted/development seam, NOT an accepted IAM wire format or authority proof. */
data class TrustedOnboardingFact(
    val scope: ProfileScope,
    val producer: String,
    val eventId: UUID,
    val streamId: String,
    val sourceRevision: Long,
    val correlationId: String,
) {
    init {
        require(producer.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(streamId.matches(Regex("[A-Za-z0-9._:-]{1,256}")))
        require(sourceRevision > 0)
        require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
    }
}

data class DeletionCause(
    val requestReference: UUID, val sourceRevision: String, val acceptedAt: String, val deadline: String,
)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DeletionCauses(val appOnly: DeletionCause? = null, val iamGlobal: DeletionCause? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BindingState(
    val scope: ProfileScope,
    val bindingRevision: String,
    val policyRevision: String,
    val lifecycle: Lifecycle,
    val readiness: Readiness,
    val profileReference: UUID?,
    val generation: String?,
    val profileRevision: String?,
    val deletionCauses: DeletionCauses = DeletionCauses(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProfileStatus(
    val state: BindingState? = null,
    val unmetFields: List<ProfileField>? = null,
    val nextActions: List<String>,
    val readiness: Readiness? = null,
)

enum class ProvisioningOutcome { PROVISIONED, DUPLICATE, STALE, NOT_ADOPTED, RECONCILIATION_REQUIRED, RESTRICTED }
data class ProvisioningResult(val outcome: ProvisioningOutcome, val state: BindingState? = null)
