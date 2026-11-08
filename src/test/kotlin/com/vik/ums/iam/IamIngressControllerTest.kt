package com.vik.ums.iam

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class IamIngressControllerTest {
    private val now = Instant.parse("2026-11-08T07:00:00Z")
    private val issuer = "https://iam.example.test"
    private val audience = "https://ums.example.test/internal/iam/events"
    private val workload = "wl_0123456789abcdefghijkL"
    private val registration = WorkloadRegistration(
        workloadId = workload,
        appRegistrationId = "dutch-development",
        clientId = "iam-relay",
        audiences = setOf(audience),
        scopes = setOf("ums:onboarding:write"),
    )
    private val resolver = WorkloadCallerResolver(issuer, audience, "ums:onboarding:write",
        WorkloadRegistrations(listOf(registration)), Clock.fixed(now, ZoneOffset.UTC))
    private val seen = mutableListOf<TrustedProducerRequest>()

    private fun controller(outcome: IngestionOutcome = IngestionOutcome.APPLIED,
                           maxBytes: Int = 32 * 1024) = IamIngressController(resolver, { request ->
        seen += request
        when (outcome) {
            IngestionOutcome.QUARANTINED -> IngestionResult(outcome, java.util.UUID.randomUUID())
            else -> IngestionResult(outcome)
        }
    }, WorkloadReplayCache(Clock.fixed(now, ZoneOffset.UTC)), maxBytes)

    private fun jwt(overrides: (MutableMap<String, Any>) -> Unit = {}): Jwt {
        val claims = mutableMapOf<String, Any>(
            "iss" to issuer, "sub" to workload, "aud" to audience,
            "iat" to now.epochSecond, "exp" to now.plusSeconds(300).epochSecond,
            "jti" to "0123456789abcdef", "client_id" to "iam-relay",
            "token_profile" to "workload_access", "token_version" to 1,
            "subject_kind" to "workload", "scope" to "ums:onboarding:write",
            "app_id" to "dutch-development", "security_version" to 1,
        ).also(overrides)
        return Jwt.withTokenValue("token").header("typ", "at+jwt").header("alg", "ES256").header("kid", "key-20261108")
            .claims { it.putAll(claims) }
            .issuedAt(Instant.ofEpochSecond((claims["iat"] as Number).toLong()))
            .expiresAt(Instant.ofEpochSecond((claims["exp"] as Number).toLong()))
            .build()
    }

    private fun request(body: String = """{"eventId":"evt_0123456789abcdefghij"}""",
                        correlation: String? = "cor_0123456789abcdef"): MockHttpServletRequest =
        MockHttpServletRequest("POST", "/internal/v1/iam/events").apply {
            setContent(body.toByteArray())
            correlation?.let { addHeader("X-Correlation-ID", it) }
        }

    private val idempotency = "evt_0123456789abcdefghij"

    @Test fun `a valid workload delivery is ingested with the authenticated app`() {
        val response = MockHttpServletResponse()
        val result = controller().ingest(jwt(), request(), response, idempotency)

        assertThat(result.statusCode.value()).isEqualTo(202)
        assertThat(result.body?.get("outcome")).isEqualTo("APPLIED")
        assertThat(seen.single().applicationId).isEqualTo("dutch-development")
        assertThat(seen.single().producer).isEqualTo("iam")
        assertThat(seen.single().correlationId).isEqualTo("cor_0123456789abcdef")
        assertThat(seen.single().body.decodeToString()).contains("evt_0123456789abcdefghij")
        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("cor_0123456789abcdef")
    }

    @Test fun `replay and terminal outcomes map to the relay contract`() {
        assertThat(controller(IngestionOutcome.DUPLICATE).ingest(jwt(), request(),
            MockHttpServletResponse(), idempotency).statusCode.value()).isEqualTo(202)
        assertThat(controller(IngestionOutcome.STALE).ingest(jwt(), request(),
            MockHttpServletResponse(), idempotency).statusCode.value()).isEqualTo(202)
        assertThat(controller(IngestionOutcome.QUARANTINED).ingest(jwt(), request(),
            MockHttpServletResponse(), idempotency).statusCode.value()).isEqualTo(422)
        assertThat(controller(IngestionOutcome.RECONCILIATION_REQUIRED).ingest(jwt(), request(),
            MockHttpServletResponse(), idempotency).statusCode.value()).isEqualTo(409)
        assertThat(controller(IngestionOutcome.RESTRICTED).ingest(jwt(), request(),
            MockHttpServletResponse(), idempotency).statusCode.value()).isEqualTo(409)
    }

    @Test fun `wrong authority is the same non-enumerating 404 and is never ingested`() {
        val response = MockHttpServletResponse()
        val result = controller().ingest(jwt { it["app_id"] = "other-app" }, request(), response, idempotency)

        assertThat(result.statusCode.value()).isEqualTo(404)
        assertThat(result.body?.get("error")).isEqualTo("unavailable")
        assertThat(seen).isEmpty()
        assertThat(response.getHeader("X-Correlation-ID")).startsWith("cor_")
    }

    @Test fun `an unauthenticated request and an empty body are refused`() {
        assertThat(controller().ingest(null, request(), MockHttpServletResponse(), idempotency).statusCode.value())
            .isEqualTo(404)
        assertThat(controller().ingest(jwt(), request(body = ""), MockHttpServletResponse(), idempotency)
            .statusCode.value()).isEqualTo(400)
        assertThat(seen).isEmpty()
    }

    @Test fun `a token used for a different event is a replay, a retried event is not`() {
        val controller = controller(outcome = IngestionOutcome.DUPLICATE)
        assertThat(controller.ingest(jwt(), request(), MockHttpServletResponse(), idempotency).statusCode.value())
            .isEqualTo(202)
        // The lost-response retry uses the same token and the same event id.
        assertThat(controller.ingest(jwt(), request(), MockHttpServletResponse(), idempotency).statusCode.value())
            .isEqualTo(202)
        // The same token reused for a different event is a replay.
        val other = "evt_0123456789abcdef99"
        assertThat(controller.ingest(jwt(), request(body = """{"eventId":"$other"}"""),
            MockHttpServletResponse(), other).statusCode.value()).isEqualTo(409)
        assertThat(seen).hasSize(2)
    }

    @Test fun `the idempotency key must match the body event id`() {
        val result = controller().ingest(jwt(), request(body = """{"eventId":"evt_0123456789abcdef99"}"""),
            MockHttpServletResponse(), idempotency)
        assertThat(result.statusCode.value()).isEqualTo(422)
        assertThat(result.body?.get("outcome")).isEqualTo("IDEMPOTENCY_KEY_MISMATCH")
        assertThat(seen).isEmpty()
    }

    @Test fun `a missing or malformed idempotency key is rejected`() {
        assertThat(controller().ingest(jwt(), request(), MockHttpServletResponse(), null).statusCode.value())
            .isEqualTo(422)
        assertThat(controller().ingest(jwt(), request(), MockHttpServletResponse(), "not-an-event").statusCode.value())
            .isEqualTo(422)
        assertThat(seen).isEmpty()
    }

    @Test fun `an oversized body is refused without reading it all`() {
        val body = "x".repeat(2048)
        val result = controller(maxBytes = 1024).ingest(jwt(), request(body), MockHttpServletResponse(), idempotency)
        assertThat(result.statusCode.value()).isEqualTo(413)
        assertThat(seen).isEmpty()
    }

    @Test fun `a missing or invalid correlation is replaced with a safe value`() {
        val response = MockHttpServletResponse()
        controller().ingest(jwt(), request(correlation = ".leading-dot"), response, idempotency)
        assertThat(response.getHeader("X-Correlation-ID")).matches("^cor_[0-9a-f]{8}-[0-9a-f]{4}-.*")
        assertThat(seen.single().correlationId).matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    }
}
