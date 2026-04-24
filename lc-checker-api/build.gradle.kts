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

    // --- Observability -----------------------------------------------------
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // --- Caffeine for in-memory CheckSessionStore --------------------------
    implementation("com.github.ben-manes.caffeine:caffeine")

    // --- YAML config for rules/catalog.yml ---------------------------------
    implementation("org.yaml:snakeyaml")

    // --- JPedal: pure-Java PDF renderer (no native deps, Docker-friendly) ---
    implementation("org.jpedal:ppedal:8.115.04")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // -parameters retains parameter names for SpEL binding in TypeAStrategy.
    options.compilerArgs.add("-parameters")
}
