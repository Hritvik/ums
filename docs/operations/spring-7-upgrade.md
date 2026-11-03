# R-1: Spring Framework 7 / Boot 4 upgrade

Status: **complete** — the upgrade shipped; the two `spring-webmvc` ignores are removed and
`osv-scanner` reports no issues.

Result: 351 JVM tests green, `CI=true ./gradlew securityAudit` passes, runtime Spring 7.0.10 on
Boot 4.0.9, Gradle 8.14.3.

## Why

`spring-webmvc` 6.2.x carries two 9.8 advisories (`GHSA-j9f9-w8pj-32f8`,
`GHSA-pc63-qcmh-9cmg`). Their fix exists only in Spring Framework 7.0.9, which ships with Spring
Boot 4. No 6.2.x fix exists, so the only remediation is this upgrade.

## What the attempts showed

Two isolated spikes (both reverted) reached a compiling application on Boot 4.0.9 / Spring 7:

- The Gradle wrapper must be raised to 8.14.3 (with its official checksum) before the Boot 4
  plugin will apply.
- Removing `io.spring.dependency-management` in favour of `platform(SpringBootPlugin.BOM_COORDINATES)`
  is the right shape, but then every unversioned test dependency (Testcontainers) needs an
  explicit version; Boot 4 no longer manages the 1.x coordinates.
- Boot 4 splits auto-configuration: the Flyway bean does not appear until
  `spring-boot-starter-flyway` is added.
- Boot 4 auto-configures a Jackson 3 `ObjectMapper`. The application's Jackson 2 services still
  compile, but they need their own mapper bean, named so it does not collide with Boot's
  `objectMapper`; HTTP serialization then runs on Jackson 3.
- `TestRestTemplate` is gone; the full-context test must use a JDK `HttpClient` (or the new
  `RestTestClient`).
- The health endpoint now returns `{"groups":[...],"status":"UP"}`; assertions must not pin the
  exact body.
- Boot 4 brings JUnit 6 and Testcontainers 2. Testcontainers 1.21.4 fails with initialization
  errors under JUnit 6, so the test stack must either be pinned to JUnit 5 or migrated to
  Testcontainers 2 (artifact and API changes). This is the main open piece.
- A deprecated MockMvc matcher (`isUnprocessableEntity`) must become `status().`is`(422)`.

The spikes were discarded; `main` stays on Boot 3.5.16 with the two advisories documented as R-1.

## What shipped

The steps below were completed, plus: Testcontainers moved to the 2.0.5 coordinates and the
non-generic `PostgreSQLContainer`; the Maven metadata configurations are now resolved for locking
by a `resolveAndLockAll` task; the full-context test uses a JDK `HttpClient`; the health assertion
accepts Boot 4's groups field.

## Steps

1. Raise the Gradle wrapper to 8.14.3 with the official checksum and verify the current build.
2. Upgrade Boot to the latest 4.0.x, replace the dependency-management plugin with the platform
   BOM, add `spring-boot-starter-flyway`, and version the test dependencies explicitly.
3. Provide a Jackson 2 mapper bean for the services (or migrate them to `tools.jackson`), and
   confirm MVC serialization of the Kotlin response types.
4. Decide the test stack: pin JUnit 5 or migrate Testcontainers to 2.x; replace `TestRestTemplate`
   and the removed MockMvc matcher; un-pin the health body assertion.
5. Adapt `SecurityConfiguration` and the OAuth2 resource-server setup to Spring Security 7.
6. Run the full suite, the restore drill, the upgrade rehearsal, and `CI=true securityAudit` with
   the two `spring-webmvc` ignores removed from `osv-scanner.toml`.

## Evidence required to close R-1

- Full `check bootJar` green, coverage gate met.
- `osv-scanner` reports no issues **without** the two Spring ignores.
- Restore drill and schema-upgrade rehearsal still pass on the new baseline.
- `docs/development/security.md` residual R-1 removed.

## Rollback

Revert the upgrade commit; the two advisories return to the accepted-risk list. No data migration
is involved, so rollback is code-only.
