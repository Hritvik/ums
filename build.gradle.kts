import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.9"
    jacoco
}

group = "com.vik"
version = "0.1.0-SNAPSHOT"


repositories { mavenCentral() }

dependencyLocking { lockAllConfigurations() }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")



    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jacoco { toolVersion = "0.8.13" }

// External dependency/secret audit. The in-build SecretScanTest runs always; the external
// scanners are required in CI and optional locally.
// Resolve every resolvable configuration so dependency locking covers Kotlin metadata
// configurations too, not just the ones a normal build touches.
tasks.register("resolveAndLockAll") {
    doFirst {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}

tasks.register<Exec>("securityAudit") {
    commandLine("sh", "scripts/security-audit.sh")
}
if (System.getenv("CI") != null) {
    tasks.named("check") { dependsOn("securityAudit") }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 1
    testLogging { events("failed", "skipped") }
    outputs.dir(layout.buildDirectory.dir("generated-contract-fixtures"))
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<JacocoCoverageVerification>().configureEach {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.80".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.80".toBigDecimal() }
        }
    }
}

tasks.register<Exec>("contractTest") {
    group = "verification"
    description = "Validate strict API/event schemas and positive/negative fixtures."
    val python = providers.gradleProperty("contractPython").orElse(".venv/bin/python")
    commandLine(python.get(), "-m", "unittest", "discover", "-s", "contracts/tests", "-v")
}

tasks.register<Exec>("producerContractTest") {
    group = "verification"
    description = "Validate actual persisted profile events against the published contract."
    dependsOn(tasks.test)
    val python = providers.gradleProperty("contractPython").orElse(".venv/bin/python")
    commandLine(python.get(), "-m", "unittest", "discover", "-s", "contracts/producer-tests", "-v")
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification, "contractTest", "producerContractTest")
}
