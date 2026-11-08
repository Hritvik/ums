package com.vik.ums.iam

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/**
 * Reads the workload credential IAM minted for the snapshot from a mounted secret file. The file is
 * `{token, audience, scopes, expiresAt}`; an unknown field, a wrong type, an expired value or a
 * missing file all yield no credential, so the client fails closed before any network call.
 *
 * This is deliberately not an OAuth client: UMS never calls the human token endpoint and IAM
 * publishes no general workload token endpoint.
 */
class FileSnapshotCredentials(
    private val path: Path,
    private val clock: Clock = Clock.systemUTC(),
) : SnapshotCredentialSupplier {
    override fun credential(): WorkloadCredential? = runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) !in 1..(16 * 1024)) return null
        val node = MAPPER.readTree(path.toFile())
        if (node !is com.fasterxml.jackson.databind.node.ObjectNode || node.fieldNames().asSequence().toSet() != KEYS) {
            return null
        }
        val scopes = node["scopes"].takeIf { it.isArray }?.map { it.asText() }?.toSet()
            ?: return null
        val token = node["token"].takeIf { it.isTextual }?.textValue() ?: return null
        val audience = node["audience"].takeIf { it.isTextual }?.textValue() ?: return null
        val expiresAt = node["expiresAt"].takeIf { it.isTextual }?.textValue()
            ?.let { Instant.parse(it) } ?: return null
        WorkloadCredential(token, audience, scopes, expiresAt)
    }.getOrNull()?.takeIf { it.token.isNotBlank() && it.expiresAt.isAfter(clock.instant()) }

    private companion object {
        val MAPPER = ObjectMapper()
        val KEYS = setOf("token", "audience", "scopes", "expiresAt")
    }
}
