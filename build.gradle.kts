import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
}

group = "com.vik"
version = "0.1.0-SNAPSHOT"

// Security patches above the versions the Boot 3.5 BOM pins. Each fixes an advisory osv-scanner
// reports against the lockfile. spring-webmvc is handled separately because its fix only exists
// in Spring Framework 7 (see docs/development/security.md).
extra["jackson-bom.version"] = "2.21.8"
extra["tomcat.version"] = "10.1.61"
extra["commons-compress.version"] = "1.28.0"
extra["log4j2.version"] = "2.26.1"
extra["postgresql.version"] = "42.7.12"
extra["commons-lang3.version"] = "3.18.0"

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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")



    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jacoco { toolVersion = "0.8.13" }

// External dependency/secret audit. The in-build SecretScanTest runs always; the external
// scanners are required in CI and optional locally.
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
