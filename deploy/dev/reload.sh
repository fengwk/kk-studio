#!/usr/bin/env bash
#
# kk-studio Dev 节点稳定 reload 命令（容器内 `/usr/local/bin/kk-studio-dev-reload`）。
#
# Agent 完成源码修改和定向测试后运行它：对持久工作区做增量 Maven package，只重启受
# 管的 Backend/Vite 进程，Daemon 与容器保持存活（Main 与 Daemon 的连接不受影响），
# 最后等待 Backend/Vite readiness。
# 前端热更新由 Vite HMR 负责，普通前端改动无需运行本命令。
set -euo pipefail

WORKSPACE_ROOT=${KK_STUDIO_WORKSPACE_ROOT:-/workspace}
REPOSITORY_DIR=${KK_STUDIO_REPOSITORY_DIR:-$WORKSPACE_ROOT/kk-studio}
SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-prod}
SPRING_FLYWAY_ENABLED=${SPRING_FLYWAY_ENABLED:-false}
# 与 entrypoint 一致：Dev Backend 的 Harness 层只提供 control/query preview，任何 ad hoc
# override 都不能让 reload 启动进程内 Harness dispatcher（Main 独占 Harness 异步执行）。
HARNESS_RUNTIME_WORKERS_ENABLED=${KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED:-false}
BACKEND_HOST=${BACKEND_HOST:-127.0.0.1}
BACKEND_PORT=${BACKEND_PORT:-8080}
FRONTEND_HOST=${FRONTEND_HOST:-0.0.0.0}
FRONTEND_PORT=${FRONTEND_PORT:-5173}
DEV_WORK_DIR=${DEV_WORK_DIR:-/var/kk-studio/dev}
# 与 entrypoint 保持一致：容器内 Backend 起服务预算要覆盖冷 cache 与完整构建后的首次启动。
DEV_READY_TIMEOUT_SECONDS=${DEV_READY_TIMEOUT_SECONDS:-600}

if [ ! -f "$REPOSITORY_DIR/pom.xml" ]; then
  echo "ERROR: $REPOSITORY_DIR does not contain a kk-studio workspace" >&2
  exit 1
fi

# fail closed：reload 的 Backend 与 entrypoint 启动的 Backend 必须遵守同一契约，
# 显式覆盖不能让 Dev 节点开始执行异步 Work。
if [ "$HARNESS_RUNTIME_WORKERS_ENABLED" != "false" ]; then
  echo "ERROR: Dev node must keep KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false;" >&2
  echo "       Main is the only Harness worker." >&2
  exit 1
fi

java_home=${JAVA_HOME_21:-${JAVA_HOME:-}}
if [ -z "$java_home" ] || [ ! -x "$java_home/bin/java" ]; then
  echo "ERROR: JAVA_HOME_21 or JAVA_HOME must point to JDK 21" >&2
  exit 1
fi

revision=
if [ -d "$REPOSITORY_DIR/.git" ]; then
  revision=$(git -C "$REPOSITORY_DIR" rev-parse --short HEAD)
fi

cd "$REPOSITORY_DIR"
echo "==> Incremental backend package${revision:+ at $revision}"
env JAVA_HOME="$java_home" mvn -B -ntp -pl web -am -DskipTests package

echo "==> Restarting managed backend and Vite (Daemon and container stay up)"
env \
  SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE" \
  SPRING_FLYWAY_ENABLED="$SPRING_FLYWAY_ENABLED" \
  KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED="$HARNESS_RUNTIME_WORKERS_ENABLED" \
  BACKEND_HOST="$BACKEND_HOST" \
  BACKEND_PORT="$BACKEND_PORT" \
  FRONTEND_HOST="$FRONTEND_HOST" \
  FRONTEND_PORT="$FRONTEND_PORT" \
  DEV_WORK_DIR="$DEV_WORK_DIR" \
  DEV_READY_TIMEOUT_SECONDS="$DEV_READY_TIMEOUT_SECONDS" \
  DEV_SKIP_PACKAGE=true \
  "$REPOSITORY_DIR/scripts/dev.sh" restart

echo "Dev reload finished: backend $BACKEND_HOST:$BACKEND_PORT, frontend $FRONTEND_HOST:$FRONTEND_PORT"
