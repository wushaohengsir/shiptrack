# syntax=docker/dockerfile:1

# ---- build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY contract/pom.xml contract/
COPY backend/pom.xml backend/
COPY collector/pom.xml collector/
COPY contract/src contract/src
COPY collector/src collector/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -Dmaven.test.skip=true -pl collector -am package \
 && cp collector/target/collector-*.jar /build/collector.jar

# ---- runtime stage ---------------------------------------------------------
# The browser image and the Playwright Java dependency are the same version.
# The image ships Chromium plus its dependencies; this image must not run as
# root, and the compose service provides shm_size and the seccomp profile.
FROM mcr.microsoft.com/playwright/java:v1.64.0-noble

WORKDIR /app
COPY --from=build --chown=pwuser:pwuser /build/collector.jar /app/collector.jar

USER pwuser

ENTRYPOINT ["java", "-jar", "/app/collector.jar"]
