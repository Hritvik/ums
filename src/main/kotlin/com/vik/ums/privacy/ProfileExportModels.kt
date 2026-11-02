package com.vik.ums.privacy

import com.vik.ums.profiles.ProfileScope
import java.time.Instant
import java.util.UUID

/** Recent-Strong export authority; a trusted local seam, not a token format. */
data class TrustedExportAuthority(
    val scope: ProfileScope,
    val actorReference: UUID,
    val authenticatedAt: Instant,
    val strong: Boolean,
    val expiresAt: Instant,
)

enum class ExportState { READY, EXPIRED, INVALIDATED, FAILED }

data class ExportStatus(
    val operationReference: UUID,
    val operationRevision: String,
    val status: String,
)

data class ExportCapability(
    val exportReference: UUID,
    val token: String,
    val expiresAt: String,
)

/** The downloadable artifact: only UMS-owned display data and lifecycle facts. */
data class ExportArtifact(
    val profileRevision: String,
    val readiness: String,
    val lifecycle: String,
    val fields: Map<String, String>,
    val generatedAt: String,
)
