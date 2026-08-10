# syntax=docker/dockerfile:1

# Build the daemon and every runtime dependency from the current reactor.
FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /build
COPY . .

RUN mvn -pl harness/daemon -am -DskipTests -B -ntp clean install \
    && mvn -pl harness/daemon -DskipTests -B -ntp \
       -DincludeScope=runtime \
       -DoutputDirectory=/build/runtime/lib \
       dependency:copy-dependencies \
    && cp harness/daemon/target/kk-studio-harness-daemon-*.jar /build/runtime/daemon.jar

# Supply a fixed Node/npm distribution without adding a package repository to
# the runtime image.
FROM node:22.19.0-bookworm-slim AS node-runtime

FROM eclipse-temurin:21.0.8_9-jdk-jammy

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends bash ca-certificates git; \
    rm -rf /var/lib/apt/lists/*; \
    groupadd --gid 10001 kkdaemon; \
    useradd --uid 10001 \
            --gid kkdaemon \
            --home-dir /home/kkdaemon \
            --create-home \
            --shell /bin/bash \
            --comment "kk-studio reliability daemon" \
            kkdaemon; \
    mkdir -p /opt/kk-studio/lib /workspace; \
    chown -R kkdaemon:kkdaemon /opt/kk-studio /workspace /home/kkdaemon

COPY --from=node-runtime /usr/local/ /usr/local/
COPY --from=builder --chown=kkdaemon:kkdaemon /build/runtime/daemon.jar /opt/kk-studio/daemon.jar
COPY --from=builder --chown=kkdaemon:kkdaemon /build/runtime/lib/ /opt/kk-studio/lib/

WORKDIR /workspace
USER 10001:10001

ENTRYPOINT ["java", "-cp", "/opt/kk-studio/daemon.jar:/opt/kk-studio/lib/*", "fun.fengwk.kkstudio.harness.daemon.DaemonMain"]
