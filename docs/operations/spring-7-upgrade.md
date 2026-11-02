# R-1: Spring Framework 7 / Boot 4 upgrade

Status: **not started** — tracked follow-up to the two accepted `spring-webmvc` advisories.

## Why

`spring-webmvc` 6.2.x carries two 9.8 advisories (`GHSA-j9f9-w8pj-32f8`,
`GHSA-pc63-qcmh-9cmg`). Their fix exists only in Spring Framework 7.0.9, which ships with Spring
Boot 4. No 6.2.x fix exists, so the only remediation is this upgrade.

## What the attempt showed

A first pass (Boot `4.0.9`) is not a drop-in:

- Boot 4 requires Gradle 8.14 or later; the repository wrapper is 8.13 and must be raised first.
- Boot 4 resolves Jackson 3 (`tools.jackson`) by default. The application imports
  `com.fasterxml.jackson.databind` throughout, and the `jackson-bom.version` override becomes a
  `tools.jackson:jackson-bom` resolution and fails.
- Spring Security 7 changes the resource-server and `HttpSecurity` surface used by
  `SecurityConfiguration`.
- The Spring dependency-management plugin should be dropped in favour of the Boot 4 BOM.

The attempt was reverted cleanly; the tree stays on Boot 3.5.16 until this work is scheduled.

## Planned steps

1. Raise the Gradle wrapper to 8.14.x with the official checksum, verify `./gradlew check` on the
   current Boot version.
2. Upgrade Boot to the latest 4.0.x, remove the dependency-management plugin, rewrite the lockfile.
3. Migrate Jackson imports to `tools.jackson` (annotations remain `com.fasterxml.jackson.annotation`),
   or confirm a supported Jackson 2 mode and pin it if one exists.
4. Adapt `SecurityConfiguration` and the OAuth2 resource-server setup to Spring Security 7.
5. Run the full suite, the restore drill, the upgrade rehearsal, and `CI=true securityAudit` with
   the two `spring-webmvc` ignores removed from `osv-scanner.toml`.

## Evidence required to close R-1

- Full `check bootJar` green, coverage gate met.
- `osv-scanner` reports no issues **without** the two Spring ignores.
- Restore drill and schema-upgrade rehearsal still pass on the new baseline.
- `docs/development/security.md` residual R-1 removed.

## Rollback

Revert the upgrade commit; the two advisories return to the accepted-risk list. No data migration
is involved, so rollback is code-only.
