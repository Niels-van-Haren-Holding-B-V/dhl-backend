plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.spring") version "2.4.0"
    kotlin("plugin.jpa") version "2.4.0"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
    // Compile-time object mapping (no KSP, no reflection): generates the
    // entity→DTO copy code. Version is <kotlin>-<mappie>; pinned to Kotlin 2.4.0.
    id("tech.mappie.plugin") version "2.4.0-2.4.1"
}

group = "nl.callido.dhl"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    // Boot 4 modularized auto-configuration: WebClient.Builder lives here.
    implementation("org.springframework.boot:spring-boot-webclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Coroutines ARE the concurrency API of this codebase: suspend controllers
    // and services on WebFlux. Reactor runs underneath, but no hand-written
    // Mono/Flux in production code.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.3")
    // Core resilience4j, wired programmatically — no Spring Boot 4 starter exists yet.
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.4.0")
    implementation("io.github.resilience4j:resilience4j-kotlin:2.4.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-redpanda")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Local dev: bootRun reads the secrets it needs from infra/.env itself
// (gitignored), so `./gradlew bootRun` and IDE gradle runs just work. Only
// whitelisted keys: the rest of .env holds PROD values (e.g. DB_PASSWORD)
// that must not leak into the local run. Real env vars win.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // OpenAPI docs are dev-only; the local run is the place they exist
    if (System.getenv("OPENAPI_ENABLED") == null) environment("OPENAPI_ENABLED", "true")
    val wanted = setOf("LOCKER_CLIENT_SECRET")
    val envFile = file("infra/.env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.substringBefore("=") in wanted }
            .forEach { line ->
                val key = line.substringBefore("=")
                if (System.getenv(key) == null) environment(key, line.substringAfter("="))
            }
    }
}
