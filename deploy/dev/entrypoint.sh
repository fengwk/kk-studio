#!/usr/bin/env bash
#
# kk-studio Dev 节点 entrypoint。
#
# 职责顺序（每一步失败都 fail closed，不留下半启动状态）：
#   1. 准备持久 Git 工作区：已存在的 checkout 永不覆盖；首次启动只能从配置的
#      clean remote 克隆，或显式允许时使用镜像内的源码快照。
#   2. 用仓库既有 lifecycle `scripts/dev.sh start` 启动 Backend（prod profile、
#      Flyway disabled、loopback 8080）和 Vite（0.0.0.0:5173、代理 Backend）。
#   3. 以前台 Environment Daemon 作为容器主进程，连接同容器 Backend 的内部
#      WebSocket，`environment-root` 指向持久工作区根。
#
# 凭据边界：Git secret 在启动前从自身环境摘除，只显式交给 clone 子进程和最终
# Daemon 进程；Backend/Vite 不继承它。Daemon registration token 只作为 Daemon
# 参数传递。两者都不会写入 image layer 或日志。
set -euo pipefail

WORKSPACE_ROOT=${KK_STUDIO_WORKSPACE_ROOT:-/workspace}
REPOSITORY_DIR=${KK_STUDIO_REPOSITORY_DIR:-$WORKSPACE_ROOT/kk-studio}
SOURCE_SEED=${KK_STUDIO_SOURCE_SEED:-/opt/kk-studio/source}
ALLOW_SOURCE_SEED=${KK_STUDIO_DEV_ALLOW_SOURCE_SEED:-false}
GIT_REMOTE_URL=${KK_STUDIO_GIT_REMOTE_URL:-}
GIT_BRANCH=${KK_STUDIO_GIT_BRANCH:-dev}

SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-prod}
SPRING_FLYWAY_ENABLED=${SPRING_FLYWAY_ENABLED:-false}
BACKEND_HOST=${BACKEND_HOST:-127.0.0.1}
BACKEND_PORT=${BACKEND_PORT:-8080}
FRONTEND_HOST=${FRONTEND_HOST:-0.0.0.0}
FRONTEND_PORT=${FRONTEND_PORT:-5173}
DEV_WORK_DIR=${DEV_WORK_DIR:-/var/kk-studio/dev}
# 容器首次启动会先做一次完整 Maven 构建和前端依赖安装，Backend 起服务的 90 秒默认
# 预算在冷 cache/弱 CPU 下不够用；这里给出一个有界的容器预算。
DEV_READY_TIMEOUT_SECONDS=${DEV_READY_TIMEOUT_SECONDS:-600}

DAEMON_GATEWAY_URI=${KK_STUDIO_DAEMON_GATEWAY_URI:-ws://127.0.0.1:8080/api/harness/environment-daemon/v1}
DAEMON_NOTE=${KK_STUDIO_DAEMON_NOTE:-kk-studio dev node}
DAEMON_JAR=/opt/kk-studio/daemon.jar
DAEMON_LIB=/opt/kk-studio/lib

step() {
  echo "==> $1"
}

# 立即摘除自身环境中的 secret：后续只能显式交给真正需要它的子进程。
git_username=${KK_STUDIO_GIT_USERNAME:-}
git_token=${KK_STUDIO_GIT_TOKEN:-}
unset KK_STUDIO_GIT_USERNAME KK_STUDIO_GIT_TOKEN
registration_token=${KK_STUDIO_DAEMON_REGISTRATION_TOKEN:-}
unset KK_STUDIO_DAEMON_REGISTRATION_TOKEN

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing command: $1" >&2
    exit 1
  fi
}

validate_runtime_contract() {
  if [ "$SPRING_PROFILES_ACTIVE" != "prod" ]; then
    echo "ERROR: Dev node requires SPRING_PROFILES_ACTIVE=prod; got $SPRING_PROFILES_ACTIVE." >&2
    exit 1
  fi
  if [ "$SPRING_FLYWAY_ENABLED" != "false" ]; then
    echo "ERROR: Dev node must keep SPRING_FLYWAY_ENABLED=false; Main is the only Flyway owner." >&2
    exit 1
  fi
  if [ -z "$registration_token" ]; then
    echo "ERROR: KK_STUDIO_DAEMON_REGISTRATION_TOKEN is required to register the Daemon." >&2
    exit 1
  fi
}

assert_expected_git_branch() {
  local current_branch
  current_branch=$(git -C "$REPOSITORY_DIR" symbolic-ref --quiet --short HEAD || true)
  if [ "$current_branch" != "$GIT_BRANCH" ]; then
    echo "ERROR: $REPOSITORY_DIR must stay on branch $GIT_BRANCH; found ${current_branch:-detached HEAD}." >&2
    echo "       Switch it explicitly before restarting; the entrypoint never checks out or resets existing work." >&2
    exit 1
  fi
}

# 持久工作区已经被初始化过就不再触碰：只按已存在的目录内容继续构建，不做 checkout/reset。
workspace_is_repository() {
  [ -f "$REPOSITORY_DIR/pom.xml" ] && [ -f "$REPOSITORY_DIR/scripts/dev.sh" ]
}

prepare_workspace() {
  if [ ! -d "$WORKSPACE_ROOT" ] || [ ! -w "$WORKSPACE_ROOT" ]; then
    echo "ERROR: $WORKSPACE_ROOT must exist and be writable by uid $(id -u)." >&2
    echo "       Mount the persistent workspace volume with owner 10001:10001." >&2
    exit 1
  fi
  if [ -d "$REPOSITORY_DIR/.git" ]; then
    step "Reusing the persistent git workspace $REPOSITORY_DIR"
    assert_expected_git_branch
    git -C "$REPOSITORY_DIR" rev-parse --short HEAD
    return
  fi
  if [ -d "$REPOSITORY_DIR" ] && [ -n "$(ls -A "$REPOSITORY_DIR")" ]; then
    if workspace_is_repository; then
      step "Reusing the persistent workspace $REPOSITORY_DIR (initialized from the source snapshot)"
      return
    fi
    echo "ERROR: $REPOSITORY_DIR is not a kk-studio checkout and is not empty; not overwriting it." >&2
    echo "       Move the directory aside to let this entrypoint initialize the workspace." >&2
    exit 1
  fi
  if [ -n "$GIT_REMOTE_URL" ]; then
    step "Initializing $REPOSITORY_DIR from the configured remote on branch $GIT_BRANCH"
    (
      # 只在 clone 子进程中出现凭据；remote URL 保持 clean，token 不进入 .git/config。
      export KK_STUDIO_GIT_USERNAME="$git_username"
      export KK_STUDIO_GIT_TOKEN="$git_token"
      git clone --branch "$GIT_BRANCH" "$GIT_REMOTE_URL" "$REPOSITORY_DIR"
    )
    assert_expected_git_branch
    git -C "$REPOSITORY_DIR" rev-parse --short HEAD
    return
  fi
  if [ "$ALLOW_SOURCE_SEED" = "true" ]; then
    step "Initializing $REPOSITORY_DIR from the embedded source snapshot (no git remote configured)"
    mkdir -p "$REPOSITORY_DIR"
    cp -a "$SOURCE_SEED/." "$REPOSITORY_DIR/"
    return
  fi
  echo "ERROR: KK_STUDIO_GIT_REMOTE_URL is required to initialize $REPOSITORY_DIR." >&2
  echo "       Set KK_STUDIO_GIT_BRANCH for a branch other than $GIT_BRANCH, or set" >&2
  echo "       KK_STUDIO_DEV_ALLOW_SOURCE_SEED=true for an explicit non-git fallback." >&2
  exit 1
}

start_managed_servers() {
  step "Starting backend and Vite via scripts/dev.sh (${SPRING_PROFILES_ACTIVE}, flyway=${SPRING_FLYWAY_ENABLED})"
  env \
    SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE" \
    SPRING_FLYWAY_ENABLED="$SPRING_FLYWAY_ENABLED" \
    BACKEND_HOST="$BACKEND_HOST" \
    BACKEND_PORT="$BACKEND_PORT" \
    FRONTEND_HOST="$FRONTEND_HOST" \
    FRONTEND_PORT="$FRONTEND_PORT" \
    DEV_WORK_DIR="$DEV_WORK_DIR" \
    DEV_READY_TIMEOUT_SECONDS="$DEV_READY_TIMEOUT_SECONDS" \
    "$REPOSITORY_DIR/scripts/dev.sh" start
}

run_daemon() {
  step "Starting Environment Daemon against $DAEMON_GATEWAY_URI with environment-root $WORKSPACE_ROOT"
  export KK_STUDIO_GIT_USERNAME="$git_username"
  export KK_STUDIO_GIT_TOKEN="$git_token"
  exec java -XX:MaxRAMPercentage=75.0 \
    -cp "$DAEMON_JAR:$DAEMON_LIB/*" \
    fun.fengwk.kkstudio.harness.daemon.DaemonMain \
    --gateway-uri "$DAEMON_GATEWAY_URI" \
    --registration-token "$registration_token" \
    --note "$DAEMON_NOTE" \
    --environment-root "$WORKSPACE_ROOT"
}

require_cmd git
require_cmd java
require_cmd mvn
require_cmd npm

validate_runtime_contract
prepare_workspace
start_managed_servers
run_daemon
