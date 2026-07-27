#
# kk-studio app image (runtime only).
#
# The Spring Boot Fat JAR is built on the host with the Maven `distribution`
# profile so Node/npm reuse the host network and local frontend cache:
#
#   env JAVA_HOME=$JAVA_HOME_21 mvn -Pdistribution -pl web -am -DskipTests clean package
#
# The image only ships Temurin JRE 21 + the Fat JAR. Healthcheck uses `curl`
# (already present in the JRE base image) against `/actuator/health`. The JVM
# runs as a dedicated unprivileged `kkstudio` user.
#

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

# Host-built Fat JAR with embedded React assets under BOOT-INF/classes/static.
COPY --chown=kkstudio:kkstudio web/target/kk-studio-web-1.0.0.jar /app/app.jar

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
