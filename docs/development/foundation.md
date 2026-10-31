# Contract and harness foundation

This slice replaces the Kotlin/Mongo/WebFlux CRUD prototype with a Kotlin/JVM 21,
Spring Boot MVC, JDBC/PostgreSQL, Flyway, and deny-all resource-server foundation.
Public profile behavior and real IAM/product integrations are not implemented.
The subsequent [internal provisioning slice](provisioning.md) adds a constructor-only
application service exercised through deterministic PostgreSQL integration tests.

## Run verification

Prerequisites: JDK 21, a running Docker-compatible daemon for Testcontainers,
Python 3.12 or later, and network access for initial dependency/image downloads.
Docker runs an isolated disposable PostgreSQL container, not your existing data.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r contracts/requirements-test.txt
./gradlew check bootJar --no-daemon --console=plain
```

If JDK 21 is not your default, set `JAVA_HOME` for the command. The Gradle Java
21 toolchain does not install a JDK or change your machine's default Java.
`-PcontractPython=/absolute/path/to/python` selects another isolated interpreter.
Tests require Docker and fail rather than silently skip database verification.

With Colima or another Docker context without `/var/run/docker.sock`, pass the
active context to Testcontainers without changing machine-wide Docker settings:

```sh
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Reports are in `build/reports/tests/test/` and `build/reports/jacoco/test/`.
`check` enforces 80% line and branch coverage and runs the contract fixture suite.
No private application packages are excluded to satisfy the threshold.

## Startup boundary

Explicit deployment settings are required:

- `UMS_DATABASE_URL`: PostgreSQL JDBC URL.
- `UMS_DATABASE_USERNAME` and `UMS_DATABASE_PASSWORD`: externally supplied credentials.
- `UMS_IAM_ISSUER` and `UMS_IAM_JWK_SET_URI`: HTTPS issuer and JWK location, without embedded credentials or fragments.
- `UMS_RESOURCE_AUDIENCE`: the registered UMS resource audience.
- `SERVER_PORT`: optional, default 8080.

There are no default database credentials, runtime test-authority mode, HTTP
Basic login, Mongo connection, public docs console, or profile CRUD routes.
The only permitted request is GET `/actuator/health`, which reveals aggregate
status only. It is an aggregate health endpoint, not a guarantee that the service
is live or ready under every dependency failure. All application/internal routes
remain denied even with a valid JWT until their authorization is implemented.
Bearer-only stateless authentication does not use cookies or browser sessions;
a product BFF's cookie/CSRF requirements remain separate.

The decoder checks issuer and audience, but that alone is **not** the complete
IAM contract. Subject kind, app binding, assurance, sender constraints, current
security versions, real delegation, and profile-level authorization are future
integration gates. The test decoder is compiled only into tests. The main
artifact contains no development authority/key fixture.

## Database harness

Flyway V1 initializes inbox/outbox infrastructure; V2 adds app adoption/policy,
profile binding, and source checkpoint storage for the internal provisioning
slice. V3 adds typed display fields and scoped patch receipts for
[profile editing](profile-editing.md). Integration tests prove migration replay, database constraints, and
transaction rollback using real PostgreSQL. This does not implement a relay,
producer authentication, HTTP event ingestion, or durable privacy recovery.
JSON payload storage is internal infrastructure, not permission to accept
arbitrary event payloads at an API.

## Contract and learning boundaries

See [contract bundle](../../contracts/README.md) for strict schemas, fixtures,
actor permissions, and runtime-only checks. OpenAPI describes target operations;
none of those business operations is shipped by this slice. See the
[Wayfinder handoff](../ums-platform/issues/08-define-verification-and-implementation-handoff.md)
for the next vertical slice and unresolved provider gates.

Kotlin concepts in this foundation: `val` for immutable references, constructor
injection for required collaborators, `init` guards for invalid startup state,
nullable values handled explicitly, and small interfaces supplied by Spring.
The application uses ordinary synchronous transactions so learning Kotlin does
not also require learning Reactor or coroutine transaction propagation.

Dependencies are pinned at the build/plugin and contract-test requirement level;
Spring Boot's BOM aligns runtime library versions. Version setup was checked
against [Spring Boot Kotlin support](https://docs.spring.io/spring-boot/3.5/reference/features/kotlin.html)
and [system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
The foundation's checks do not replace a dependency vulnerability audit or the
independent security/restore review required before a live release.
