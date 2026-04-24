# syntax=docker/dockerfile:1.7
#
# lc-checker-api — multi-stage image
# - Build stage:  Gradle 8 on JDK 21 builds the Spring Boot fat jar.
# - Runtime:      eclipse-temurin 21 JRE, non-root, curl-based healthcheck on
#                 Spring Boot Actuator /actuator/health.
# Build context: ./lc-checker-api (see docker-compose.yml).
# ---------------------------------------------------------------------------

############
# Stage 1: build
############
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace

# Copy build metadata first so dependency layer caches survive source changes.
COPY --chown=gradle:gradle settings.gradle.kts build.gradle.kts ./
COPY --chown=gradle:gradle gradle ./gradle
# gradle.properties is optional — COPY fails if the file is missing, so guard
# the copy by also sourcing a known-to-exist file in the same step.
COPY --chown=gradle:gradle gradle/wrapper/gradle-wrapper.properties gradle.properties* ./

# Warm dependency cache (ignore failure on first bring-up when some deps may
# require source to resolve).
RUN gradle --no-daemon --quiet dependencies || true

# Now copy sources and build. Tests run in CI, not at image-build time.
COPY --chown=gradle:gradle src ./src
RUN gradle --no-daemon clean bootJar -x test \
    && ls -lh build/libs/

############
# Stage 2: runtime
############
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl is needed for the Docker healthcheck; ca-certificates for outbound HTTPS
# to DeepSeek / MiniMax; tini for PID-1 signal handling.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates tini \
    && rm -rf /var/lib/apt/lists/*

# Non-root user (UID/GID 1000).
RUN groupadd --system --gid 1000 app \
    && useradd --system --uid 1000 --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app /app/logs \
    && chown -R app:app /app

WORKDIR /app

COPY --from=build --chown=app:app /workspace/build/libs/*.jar /app/app.jar

USER app

# JVM tuning: honor container memory limits; sensible defaults, overridable
# at runtime via JAVA_OPTS in docker-compose.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# tini handles zombie reaping + forwards SIGTERM cleanly to the JVM.
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
