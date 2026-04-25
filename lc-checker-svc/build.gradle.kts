// lc-checker-api — LC Invoice Checker V1
//
// Stable pairing per user direction ("latest stable version... align with Spring AI"):
//   - Spring Boot 3.5.14    (latest 3.5.x GA; Spring AI GA line is built on Spring 3.x)
//   - Spring AI   1.1.4     (latest GA as of 2026-03)
//   - JDK 21 (Temurin)
// Spring Boot 4.x + Spring AI 2.0.x are newer but 2.0 is still milestone quality;
// we intentionally choose the proven stable pair for V1.

plugins {
    java
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.lc"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "1.1.4"

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

dependencies {
    // --- Spring Boot core ---------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // --- Spring AI (LLM calls only; DO NOT use for extractor HTTP) ---------
    // See memory/feedback_spring_ai_scope.md for the scope decision.
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // --- Spring JDBC + PostgreSQL (CheckSession persistence) -----------------
    // Uses JDBC Template with NamedParameterJdbcTemplate — no JPA/Hibernate.
    // Session store: L1 Caffeine (in-process) + L2 PostgreSQL (normalized tables).
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")

    // --- Observability -----------------------------------------------------
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // --- Caffeine for in-memory CheckSessionStore --------------------------
    implementation("com.github.ben-manes.caffeine:caffeine")

    // --- YAML config for rules/catalog.yml ---------------------------------
    implementation("org.yaml:snakeyaml")

    // --- Apache PDFBox 3.x: pure-Java PDF → PNG renderer --------------------
    // Used by the Vision LLM extractor (extractor/vision/PdfRenderer.java).
    // JPedal is NOT on Maven Central; PDFBox is the standard open-source option.
    implementation("org.apache.pdfbox:pdfbox:3.0.7")

    // --- Prowide Core: SWIFT MT parser --------------------------------------
    // De-facto standard open-source Java SWIFT MT parser, Apache-2.0. Used by
    // Mt700Parser to decode MT700 credit messages into typed Field* objects.
    // SRU version aligns with SWIFT Standards Release Update cadence (Nov each year);
    // bump annually when the new SRU ships on Maven Central.
    implementation("com.prowidesoftware:pw-swift-core:SRU2025-10.3.12")
    // Prowide SRU2025 uses org.apache.commons.lang3.Strings which requires
    // commons-lang3 3.18+. Spring Boot 3.5.x's BOM pins 3.17.0, so we override.
    implementation("org.apache.commons:commons-lang3:3.18.0")

    // --- springdoc-openapi: publishes /v3/api-docs (OpenAPI 3 JSON spec).
    // The Scalar UI at /static/docs.html consumes this. We use the -api starter
    // (not -ui) because we don't ship Swagger-UI; Scalar is the UI.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.11")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // -parameters retains parameter names for SpEL binding in TypeAStrategy.
    options.compilerArgs.add("-parameters")
}
