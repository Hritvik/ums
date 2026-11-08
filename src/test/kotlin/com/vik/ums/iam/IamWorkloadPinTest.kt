package com.vik.ums.iam

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** The reviewed IAM workload artifacts are pinned, and the runtime constants match them. */
class IamWorkloadPinTest {
    private val root: Path = Path.of("src/test/resources/iam/pinned")

    @Test fun `every pinned artifact still matches the manifest hash`() {
        val pin = ObjectMapper().readTree(root.resolve("PIN.json").toFile())
        assertThat(pin["sourceCommit"].asText()).matches("[0-9a-f]{7,40}")
        assertThat(pin.has("sourceState")).isFalse()
        val files = pin["files"]
        assertThat(files.size()).isGreaterThanOrEqualTo(4)
        files.forEach { entry ->
            val path = root.resolve(entry["path"].asText())
            assertThat(path).exists()
            val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it) }
            assertThat(digest).isEqualTo(entry["sha256"].asText())
            assertThat(Files.size(path)).isEqualTo(entry["bytes"].asLong())
        }
    }

    @Test fun `the runtime claim set equals the reviewed claims schema`() {
        val schema = ObjectMapper().readTree(root.resolve("workload-access-token-claims.schema.json").toFile())
        assertThat(schema["additionalProperties"].asBoolean()).isFalse()
        val required = schema["required"].map { it.asText() }.toSet()
        assertThat(WorkloadTokenProfile.CLAIMS).isEqualTo(required)
        assertThat(schema["properties"]["token_profile"]["const"].asText())
            .isEqualTo(WorkloadTokenProfile.PROFILE)
        assertThat(schema["properties"]["token_version"]["const"].asLong())
            .isEqualTo(WorkloadTokenProfile.VERSION)
        assertThat(schema["properties"]["subject_kind"]["const"].asText())
            .isEqualTo(WorkloadTokenProfile.SUBJECT_KIND)
        assertThat(schema["properties"]["sub"]["pattern"].asText())
            .isEqualTo(WorkloadTokenProfile.WORKLOAD_ID.pattern)
    }

    @Test fun `the header and snapshot pins are the accepted profile`() {
        val header = ObjectMapper().readTree(root.resolve("workload-access-token-header.schema.json").toFile())
        assertThat(header["properties"]["typ"]["const"].asText()).isEqualTo("at+jwt")
        assertThat(header["properties"]["alg"]["const"].asText()).isEqualTo("ES256")

        val snapshot = Files.readString(root.resolve("iam-onboarding-snapshot.openapi.yaml"))
        assertThat(snapshot).contains(WorkloadTokenProfile.SNAPSHOT_PROFILE)
        assertThat(snapshot).contains(WorkloadTokenProfile.SNAPSHOT_SCOPE)
        assertThat(snapshot).contains(WorkloadTokenProfile.SNAPSHOT_AUDIENCE)

        val semantic = Files.readString(root.resolve("semantic-requirements.yaml"))
        assertThat(semantic).contains("workloadAccess")
    }
}
