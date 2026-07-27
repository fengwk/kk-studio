#
# kk-studio app image.
#
# Builder: Maven 3.9.11 on Temurin JDK 21. Produces the Spring Boot Fat JAR for the
# `web` module via the `distribution` profile, which installs Node 24 + npm 11 and
# embeds the React build output into BOOT-INF/classes/static.
#
# Runtime: Temurin JRE 21 (Ubuntu 22.04 base). Only the Fat JAR ships in the image;
# no Maven, no Node, no source. Healthcheck uses `curl` (already shipped in the
# JRE base image) against `/actuator/health`. The JVM runs as a dedicated
# unprivileged `kkstudio` user.

# ---------- builder ----------
FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /build

# Mitigate transient npm registry ECONNRESET during frontend-maven-plugin install.
ENV NPM_CONFIG_FETCH_RETRIES=5 \
    NPM_CONFIG_FETCH_RETRY_FACTOR=2 \
    NPM_CONFIG_FETCH_RETRY_MINTIMEOUT=10000 \
    NPM_CONFIG_FETCH_RETRY_MAXTIMEOUT=60000

# Copy the whole reactor. The tight `.dockerignore` excludes every output we do
# not need (target dirs, frontend node_modules/dist, IDE files, secrets, etc.).
COPY . .

# Build the Fat JAR with embedded React assets. Tests are skipped on purpose:
# the Fat JAR is a runtime artifact, and tests run in dedicated CI steps.
RUN mvn -Pdistribution -pl web -am -DskipTests -B -ntp clean package \
    && cp web/target/kk-studio-web-*.jar /build/app.jar

# ---------- runtime ----------
FROM eclipse-temurin:21.0.8_9-jre-jammy

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# Create a dedicated unprivileged user and writable app directory.
# `curl` is shipped by the eclipse-temurin JRE image, so no apt-get install is
# required for the healthcheck.
RUN set -eux; \
    groupadd --system --gid 10001 kkstudio; \
    useradd --system --uid 10001 \
            --gid kkstudio \
            --home-dir /app \
            --shell /usr/sbin/nologin \
            --comment "kk-studio app" \
            kkstudio; \
    mkdir -p /app/logs; \
    chown -R kkstudio:kkstudio /app

WORKDIR /app

COPY --from=builder --chown=kkstudio:kkstudio /build/app.jar /app/app.jar

USER kkstudio

EXPOSE 8080

# Healthcheck runs as the unprivileged user; `curl` is on PATH inside the JRE
# image. Spring Boot exposes `/actuator/health` by default with the included
# actuator starter.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=6 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

# `init: true` in docker-compose adds a tiny init that forwards signals and
# reaps zombies, so we do not need tini inside the image.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
