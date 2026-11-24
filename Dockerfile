# syntax=docker/dockerfile:1

# ---- build stage -----------------------------------------------------------
# Maven comes from the image so the build never bootstraps the wrapper
# distribution over the network. The Maven repository is a BuildKit cache, so
# a source-only change does not download dependencies again.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY contract/pom.xml contract/
COPY backend/pom.xml backend/
COPY collector/pom.xml collector/
COPY contract/src contract/src
COPY backend/src backend/src

# Only the API and the code it depends on are packaged; the collector stays a
# separate image because it carries Chromium and never gets database credentials.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -Dmaven.test.skip=true -pl backend -am package \
 && cp backend/target/backend-*.jar /build/app.jar

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre

RUN groupadd --system --gid 10001 shiptrack \
 && useradd --system --uid 10001 --gid shiptrack --home-dir /app --shell /usr/sbin/nologin shiptrack

WORKDIR /app
COPY --from=build --chown=10001:10001 /build/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

# Exec form so the JVM is PID 1 and receives SIGTERM from `docker stop`.
# Tune the heap through JAVA_TOOL_OPTIONS (docker-compose.yml sets it).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
