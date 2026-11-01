package com.vik.ums

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(UserManagementServiceApplicationTests.TestAuthority::class)
class UserManagementServiceApplicationTests {
    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var datasource: DataSource
    @Autowired lateinit var flyway: Flyway

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("ums.security.issuer") { "https://iam.example.test" }
            registry.add("ums.security.jwk-set-uri") { "https://iam.example.test/keys" }
            registry.add("ums.security.audience") { "https://ums.example.test" }
            registry.add("ums.environment") { "test" }
            registry.add("ums.profile.locale-version") { "l1" }
            registry.add("ums.profile.timezone-version") { "t1" }
            registry.add("ums.profile.locales") { "en" }
            registry.add("ums.profile.timezones") { "UTC" }
            registry.add("ums.profile.receipt-key") { "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=" }
            registry.add("ums.iam.issuer-environment-ref") { "6f1d4a2c-3b5e-4c7d-8a9b-0c1d2e3f4a5b" }
            registry.add("ums.security.registrations") {
                """[{"appId":"dutch-development","clientId":"dutch-mobile",
                "audience":"https://ums.example.test","allowedScopes":["profile:read"],
                "requiresDpop":true}]"""
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestAuthority {
        // Tests only: no test authentication mode exists in the application artifact.
        @Bean
        @Primary
        fun testJwtDecoder(): JwtDecoder = JwtDecoder {
            Jwt.withTokenValue(it).header("alg", "ES256").header("typ", "at+jwt")
                .header("kid", "dutch-key-01")
                .subject("aps_0123456789abcdefghijkL").issuer("https://iam.example.test")
                .audience(listOf("https://ums.example.test")).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("jti", "jti_0123456789abcdef")
                .claim("client_id", "dutch-mobile")
                .claim("token_profile", "human_access")
                .claim("token_version", 1)
                .claim("subject_kind", "app_subject")
                .claim("scope", "profile:read")
                .claim("app_id", "dutch-development")
                .claim("security_version", 1)
                .claim("acr", "urn:distracted:aal2")
                .claim("amr", listOf("passkey"))
                .claim("cnf", mapOf("jkt" to "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"))
                .build()
        }
    }

    @Test
    fun `empty PostgreSQL database migrates and migration is repeatable`() {
        assertThat(flyway.info().applied().map { it.version.toString() }).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15")
        assertThat(flyway.migrate().migrationsExecuted).isZero()
        assertThat(jdbc.queryForObject("select count(*) from ums_event_inbox", Long::class.java)).isNotNull()
    }

    @Test
    fun `public liveness exposes no database or security details`() {
        val response = http.getForEntity("/actuator/health", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isEqualTo("{\"status\":\"UP\"}")
    }

    @Test
    fun `unauthenticated application and retired CRUD routes are denied`() {
        listOf("/api/v1/profile", "/api/1.0/users", "/api/test", "/internal/v1/apps/dutch/profile-policies")
            .forEach { path ->
                assertThat(http.getForEntity(path, String::class.java).statusCode)
                    .describedAs(path).isEqualTo(HttpStatus.UNAUTHORIZED)
            }
    }

    @Test
    fun `authenticated token still grants no foundation business capability`() {
        val headers = HttpHeaders().apply { setBearerAuth("test-fixture-token") }
        val response = http.exchange("/api/v1/profile", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        // An implemented business route is the only permitted one; every other route stays
        // closed to an authenticated caller. denyAll answers 401 in this configuration.
        assertThat(response.statusCode.is4xxClientError).isTrue()
        assertThat(response.body ?: "").doesNotContain("app_id")
    }

    @Test
    fun `health permission applies only to GET`() {
        assertThat(http.postForEntity("/actuator/health", null, String::class.java).statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `inbox and outbox effects roll back together`() {
        val eventId = UUID.randomUUID()
        val transactions = TransactionTemplate(DataSourceTransactionManager(datasource))
        assertThatThrownBy {
            transactions.executeWithoutResult {
                insertInbox(eventId)
                insertOutbox(eventId)
                throw IllegalStateException("simulated failure before commit")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(count("ums_event_inbox", eventId)).isZero()
        assertThat(count("ums_event_outbox", eventId)).isZero()
    }

    @Test
    fun `inbox producer and event identity prevent duplicate application`() {
        val eventId = UUID.randomUUID()
        insertInbox(eventId)
        assertThatThrownBy { insertInbox(eventId) }
            .isInstanceOf(org.springframework.dao.DuplicateKeyException::class.java)
    }

    @Test
    fun `outbox rejects nonpositive stream revision and nonobject payload`() {
        assertThatThrownBy { insertOutbox(UUID.randomUUID(), 0, "{}") }
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertOutbox(UUID.randomUUID(), 1, "[]") }
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    private fun insertInbox(id: UUID) {
        jdbc.update("""insert into ums_event_inbox
            (producer, event_id, application_id, stream_id, stream_revision)
            values ('iam', ?, 'dutch', 'fixture-stream', 1)""", id)
    }

    private fun insertOutbox(id: UUID, revision: Long = 1, payload: String = "{}") {
        jdbc.update("""insert into ums_event_outbox
            (event_id, application_id, stream_id, stream_revision, event_type, payload)
            values (?, 'dutch', ?, ?, 'fixture.v1', ?::jsonb)""", id, id.toString(), revision, payload)
    }

    private fun count(table: String, id: UUID): Long =
        jdbc.queryForObject("select count(*) from $table where event_id = ?", Long::class.java, id)!!
}
