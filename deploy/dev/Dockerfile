# syntax=docker/dockerfile:1.7
#
# kk-studio Dev 节点镜像（NAS `vps-kk-studio-dev`）。
#
# 与 `deploy/local/Dockerfile` 的差别：这个镜像不是不可变产物，而是"持久源码工作区
# + 完整开发工具链"的运行基座。源码工作区必须是 `${KK_STUDIO_WORKSPACE_ROOT}` 上的
# 持久 Git checkout，容器重建不覆盖它；镜像内的源码快照只在显式允许时作为首次
# 初始化 fallback，Daemon 始终从镜像内的 Jar 运行。
#
# builder：Maven 3.9.11 + Temurin 21 构建 Environment Daemon 及其 runtime 依赖。
# runtime：Temurin 21 JDK（含 javap）、Maven 3.9.11、Node 24.14.0/npm 11.9.0、
#          git、curl、jq、lsof、python3、ffmpeg/ffprobe、CA certificates；
#          以 uid/gid 10001 的非 root 用户运行。
#
# 该镜像不包含 Docker socket、真实 credential 或 Git 历史；BuildKit cache mount 只
# 承载 Maven 依赖，`.dockerignore` 排除 `.git`、`.env*`、key/credential 文件、
# 构建报告、`target/`、`node_modules/` 和本地工作区。

# ---------- daemon builder ----------
FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy the whole reactor; the tight `.dockerignore` keeps outputs and secrets out.
COPY . .

RUN --mount=type=cache,id=kk-studio-dev-maven,target=/root/.m2/repository,sharing=locked \
    mvn -U -pl harness/daemon -am -DskipTests -B -ntp clean install \
    && mvn -pl harness/daemon -DskipTests -B -ntp \
       -DincludeScope=runtime \
       -DoutputDirectory=/build/daemon-lib \
       dependency:copy-dependencies \
    && cp harness/daemon/target/kk-studio-harness-daemon-*.jar /build/daemon.jar

# ---------- node distribution ----------
# Fixed Node/npm distribution matching the `distribution` profile versions, without
# adding a package repository to the runtime image.
FROM node:24.14.0-bookworm-slim AS node-runtime

RUN npm install --global npm@11.9.0 --no-fund --no-audit \
    && npm cache clean --force

# ---------- runtime ----------
FROM eclipse-temurin:21.0.8_9-jdk-jammy

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    HOME=/home/kkdaemon \
    PATH="/opt/maven/bin:${PATH}" \
    GIT_ASKPASS=/usr/local/bin/kk-studio-git-askpass \
    GIT_TERMINAL_PROMPT=0 \
    KK_STUDIO_WORKSPACE_ROOT=/workspace \
    KK_STUDIO_REPOSITORY_DIR=/workspace/kk-studio \
    KK_STUDIO_SOURCE_SEED=/opt/kk-studio/source \
    SPRING_PROFILES_ACTIVE=prod \
    SPRING_FLYWAY_ENABLED=false \
    BACKEND_HOST=127.0.0.1 \
    BACKEND_PORT=8080 \
    FRONTEND_HOST=0.0.0.0 \
    FRONTEND_PORT=5173 \
    DEV_WORK_DIR=/var/kk-studio/dev \
    DEV_SKIP_PACKAGE=true

# Refresh the Ubuntu base packages on every build so the runtime absorbs current
# Ubuntu security updates before installing the Development toolchain. `ffmpeg`
# also provides `ffprobe`; `lsof` and `jq` are required by `scripts/dev.sh`.
RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get upgrade --yes; \
    apt-get install -y --no-install-recommends \
        bash ca-certificates curl ffmpeg git jq lsof python3; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*; \
    command -v ffmpeg; \
    command -v ffprobe; \
    command -v javap; \
    groupadd --gid 10001 kkdaemon; \
    useradd --uid 10001 \
            --gid kkdaemon \
            --home-dir /home/kkdaemon \
            --create-home \
            --shell /bin/bash \
            --comment "kk-studio dev node" \
            kkdaemon; \
    mkdir -p /opt/kk-studio/lib /opt/kk-studio/source /workspace /var/kk-studio/dev \
             /home/kkdaemon/.m2 /home/kkdaemon/.npm; \
    chown -R kkdaemon:kkdaemon /opt/kk-studio /workspace /var/kk-studio /home/kkdaemon; \
    git config --system credential.helper /usr/local/bin/kk-studio-git-askpass; \
    git config --system core.askpass /usr/local/bin/kk-studio-git-askpass; \
    git config --system user.name "kk-studio dev"; \
    git config --system user.email "dev@kk-studio.invalid"

# Maven and Node come from their pinned build images instead of a runtime package
# repository, so the toolchain versions match the build that produced this image.
COPY --from=builder /usr/share/maven /opt/maven
COPY --from=node-runtime /usr/local/ /usr/local/

COPY --from=builder --chown=kkdaemon:kkdaemon /build/daemon.jar /opt/kk-studio/daemon.jar
COPY --from=builder --chown=kkdaemon:kkdaemon /build/daemon-lib/ /opt/kk-studio/lib/

# Immutable source snapshot (`dev` commit at image build time). It contains no
# `.git`, credential or build report; normal NAS startup still requires a real Git
# checkout in the persistent workspace.
COPY --chown=kkdaemon:kkdaemon . /opt/kk-studio/source

COPY deploy/dev/git-askpass.sh /usr/local/bin/kk-studio-git-askpass
COPY deploy/dev/entrypoint.sh /usr/local/bin/kk-studio-dev-entrypoint
COPY deploy/dev/reload.sh /usr/local/bin/kk-studio-dev-reload
COPY deploy/dev/healthcheck.sh /usr/local/bin/kk-studio-dev-healthcheck
RUN chmod 0755 \
        /usr/local/bin/kk-studio-git-askpass \
        /usr/local/bin/kk-studio-dev-entrypoint \
        /usr/local/bin/kk-studio-dev-reload \
        /usr/local/bin/kk-studio-dev-healthcheck

WORKDIR /workspace

USER 10001:10001

# 5173 is the Vite dev server behind the Gateway; 8080 stays loopback-only for the
# Daemon WebSocket and Vite proxy, but is exposed here for optional internal probes.
EXPOSE 5173 8080

# Backend and Vite are built/started by the entrypoint on first start. On a cold
# cache volume that bootstrap also fetches the whole Maven/npm dependency set: the
# cold first boot measured ~29 minutes (25:44 Maven + 2:00 npm + servers) while a
# warm-cache boot measured 41 seconds, so the grace period covers the cold path
# with ~50% margin instead of the steady-state restart path.
HEALTHCHECK --interval=30s --timeout=10s --start-period=2700s --retries=20 \
    CMD /usr/local/bin/kk-studio-dev-healthcheck

# Run with `init: true` in Compose: the entrypoint execs the Daemon as PID 1, and
# the tiny init forwards signals and reaps the backend/Vite children.
ENTRYPOINT ["/usr/local/bin/kk-studio-dev-entrypoint"]
