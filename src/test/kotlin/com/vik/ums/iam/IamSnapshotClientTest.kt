package com.vik.ums.iam

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class IamSnapshotClientTest {
    private val now = Instant.parse("2026-11-08T07:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var server: HttpServer
    private var status = 200
    private var body = """{"data":{"appRegistrationId":"dutch-development","items":[],"hasMore":false,"nextCursor":null}}"""
    private val requests = mutableListOf<HttpExchange>()
    private val headers = mutableMapOf<String, String>()

    @BeforeEach fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange
            headers["Authorization"] = exchange.requestHeaders.getFirst("Authorization")
            headers["Correlation"] = exchange.requestHeaders.getFirst("X-Correlation-ID")
            headers["Accept"] = exchange.requestHeaders.getFirst("Accept")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach fun stop() = server.stop(0)

    private fun client(credential: WorkloadCredential? = valid()) = IamSnapshotClient(
        URI.create("http://127.0.0.1:${server.address.port}/"), "dutch-development",
        { credential }, clock, Duration.ofSeconds(2),
    )

    private fun valid(expiresAt: Instant = now.plusSeconds(300),
                      audience: String = WorkloadTokenProfile.SNAPSHOT_AUDIENCE,
                      scopes: Set<String> = setOf(WorkloadTokenProfile.SNAPSHOT_SCOPE)) =
        WorkloadCredential("token-value", audience, scopes, expiresAt)

    @Test fun `a valid workload credential fetches one bounded page`() {
        val result = client().fetch()
        assertThat(result).isInstanceOf(SnapshotFetch.Page::class.java)
        val page = result as SnapshotFetch.Page
        assertThat(page.body.decodeToString()).contains("dutch-development")
        assertThat(page.correlationId).matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        assertThat(headers["Authorization"]).isEqualTo("Bearer token-value")
        assertThat(headers["Accept"]).isEqualTo("application/json")
        assertThat(headers["Correlation"]).isEqualTo(page.correlationId)
        assertThat(requests.single().requestURI.path)
            .isEqualTo("/internal/v1/apps/dutch-development/onboarding")
    }

    @Test fun `a cursor is passed as a bound query parameter`() {
        body = """{"data":{"appRegistrationId":"dutch-development","items":[],"hasMore":true,"nextCursor":"next"}}"""
        client().fetch("cursor-value_1")
        assertThat(requests.single().requestURI.query).isEqualTo("cursor=cursor-value_1")
    }

    @Test fun `missing, expired, wrong-audience and wrong-scope credentials never call the service`() {
        assertThat(client(null).fetch()).isEqualTo(SnapshotFetch.RefusedCredential(SnapshotCredentialDenial.MISSING))
        assertThat(client(valid(expiresAt = now)).fetch())
            .isEqualTo(SnapshotFetch.RefusedCredential(SnapshotCredentialDenial.EXPIRED))
        assertThat(client(valid(audience = "https://other.example.test/onboarding")).fetch())
            .isEqualTo(SnapshotFetch.RefusedCredential(SnapshotCredentialDenial.WRONG_AUDIENCE))
        assertThat(client(valid(scopes = setOf("iam:onboarding:other"))).fetch())
            .isEqualTo(SnapshotFetch.RefusedCredential(SnapshotCredentialDenial.WRONG_SCOPE))
        assertThat(client(WorkloadCredential("", WorkloadTokenProfile.SNAPSHOT_AUDIENCE,
            setOf(WorkloadTokenProfile.SNAPSHOT_SCOPE), now.plusSeconds(300))).fetch())
            .isEqualTo(SnapshotFetch.RefusedCredential(SnapshotCredentialDenial.MISSING))
        assertThat(client(WorkloadCredential("x".repeat(9000), WorkloadTokenProfile.SNAPSHOT_AUDIENCE,
            setOf(WorkloadTokenProfile.SNAPSHOT_SCOPE), now.plusSeconds(300))).fetch())
            .isEqualTo(SnapshotFetch.RefusedCredential(SnapshotCredentialDenial.MISSING))
        assertThat(requests).isEmpty()
    }

    @Test fun `retryable and terminal statuses are classified`() {
        status = 500
        assertThat(client().fetch()).isEqualTo(SnapshotFetch.Failed(true, "HTTP_500"))
        status = 408
        assertThat(client().fetch()).isEqualTo(SnapshotFetch.Failed(true, "HTTP_408"))
        status = 503
        assertThat(client().fetch()).isEqualTo(SnapshotFetch.Failed(true, "HTTP_503"))
        status = 429
        assertThat(client().fetch()).isEqualTo(SnapshotFetch.Failed(true, "HTTP_429"))
        status = 404
        assertThat(client().fetch()).isEqualTo(SnapshotFetch.Failed(false, "HTTP_404"))
        status = 422
        assertThat(client().fetch()).isEqualTo(SnapshotFetch.Failed(false, "HTTP_422"))
    }

    @Test fun `an oversized or invalid cursor response is refused`() {
        body = "x".repeat(2048)
        val small = IamSnapshotClient(
            URI.create("http://127.0.0.1:${server.address.port}/"), "dutch-development",
            { valid() }, clock, Duration.ofSeconds(2), maximumBytes = 1024,
        )
        assertThat(small.fetch()).isEqualTo(SnapshotFetch.Failed(false, "RESPONSE_TOO_LARGE"))
        assertThat(client().fetch("")).isEqualTo(SnapshotFetch.Failed(false, "INVALID_CURSOR"))
        assertThat(client().fetch("x".repeat(513))).isEqualTo(SnapshotFetch.Failed(false, "INVALID_CURSOR"))
        assertThat(requests.size).isEqualTo(1)
    }

    @Test fun `an unavailable endpoint is retryable and non-loopback plain HTTP is refused`() {
        val closed = IamSnapshotClient(
            URI.create("http://127.0.0.1:${server.address.port + 1}/"), "dutch-development",
            { valid() }, clock, Duration.ofMillis(300),
        )
        assertThat(closed.fetch()).isInstanceOf(SnapshotFetch.Failed::class.java)
        assertThat((closed.fetch() as SnapshotFetch.Failed).retryable).isTrue()

        assertThatThrownBy {
            IamSnapshotClient(URI.create("http://iam.example.test/"), "dutch-development", { valid() }, clock)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            IamSnapshotClient(URI.create("https://iam.example.test/?token=x"), "dutch-development", { valid() }, clock)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
