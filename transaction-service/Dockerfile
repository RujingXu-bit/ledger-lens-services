# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — build
#
# A full JDK plus Maven, none of which has any business being in the image that
# runs in production: a build toolchain on a running container is attack surface
# that can never be used for anything good. Multi-stage means it is discarded.
# ---------------------------------------------------------------------------
# --platform=$BUILDPLATFORM pins the build stage to the machine doing the
# building, while the runtime stage below is built for the target architecture.
# This is safe precisely because the output is a jar: Java bytecode is
# architecture-independent, so an arm64 laptop can produce an image that runs on
# amd64 in Azure without emulating a whole Maven build under QEMU. It would not
# be safe for a dependency shipping native code; there is none here.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# The POMs first, on their own. This layer is invalidated only when a POM
# changes, so the dependency download below is reused across every edit to the
# source - which is most edits. Copying the whole tree first would re-download
# the internet on every build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY transaction-service/pom.xml transaction-service/
COPY analytics-service/pom.xml analytics-service/

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -pl transaction-service -am -DskipTests dependency:go-offline

COPY transaction-service/src transaction-service/src

# Tests are not run here. They need a Docker daemon for Testcontainers, and
# running them inside the image build would mean either Docker-in-Docker or
# quietly weakening them. They belong in the pipeline, which is day 11.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -pl transaction-service -am -DskipTests package

# Split the fat jar into layers that change at different rates: third-party
# dependencies almost never, this application's classes on every commit. Docker
# caches and ships them separately, so a redeploy pushes a few hundred kilobytes
# instead of the whole ~60MB jar.
RUN java -Djarmode=tools -jar transaction-service/target/transaction-service-*.jar extract --layers --launcher --destination /extracted

# ---------------------------------------------------------------------------
# Stage 2 — runtime
#
# A JRE, not a JDK: no compiler, no jshell, nothing to build with.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# A container process that does not need root should not have it. If something
# does get remote execution here, it lands as an unprivileged user with no
# package manager and nothing writable outside /tmp.
RUN addgroup -S ledgerlens && adduser -S ledgerlens -G ledgerlens

WORKDIR /app

# Ordered least-likely to most-likely to change, so the layers above stay cached.
COPY --from=build --chown=ledgerlens:ledgerlens /extracted/dependencies/ ./
COPY --from=build --chown=ledgerlens:ledgerlens /extracted/spring-boot-loader/ ./
COPY --from=build --chown=ledgerlens:ledgerlens /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=ledgerlens:ledgerlens /extracted/application/ ./

USER ledgerlens

EXPOSE 8081

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget --quiet --tries=1 --spider http://localhost:8081/actuator/health/readiness || exit 1

# MaxRAMPercentage, not -Xmx. The JVM reads the container's memory limit, so the
# heap follows whatever the orchestrator grants without the number being
# duplicated in two places and drifting apart.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", \
            "org.springframework.boot.loader.launch.JarLauncher"]
