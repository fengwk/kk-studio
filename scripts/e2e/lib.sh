#!/usr/bin/env bash
# scripts/e2e/lib.sh
#
# 共享函数库：e2e 入口与各 case 共用。
# 约定：
# - 默认 backend=127.0.0.1:18081、frontend=127.0.0.1:5173、profile=e2e
# - 使用 PostgreSQL durable 库；重启 backend 后由宿主 E2E runner 重新同步 MiniMax credential
# - frontend Vite 代理必须指向当前 backend：API_PROXY_TARGET=http://$BACKEND_HOST:$BACKEND_PORT
# - daemon 不是 fat jar，必须用 -cp（daemon jar + 其自身 runtime 依赖 classpath）启动 DaemonMain，而非 harness-runtime 模块 classpath

set -euo pipefail

E2E_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$E2E_ROOT/../.." && pwd)
WORK_DIR=${E2E_WORK_DIR:-"$REPO_ROOT/runtime/e2e"}

BACKEND_HOST=${BACKEND_HOST:-127.0.0.1}
BACKEND_PORT=${BACKEND_PORT:-18081}
FRONTEND_HOST=${FRONTEND_HOST:-127.0.0.1}
FRONTEND_PORT=${FRONTEND_PORT:-5173}
BACKEND_URL=${BACKEND_URL:-"http://$BACKEND_HOST:$BACKEND_PORT"}
FRONTEND_URL=${FRONTEND_URL:-"http://$FRONTEND_HOST:$FRONTEND_PORT"}
SPRING_PROFILE=${SPRING_PROFILES_ACTIVE:-e2e}
DAEMON_ENV_NAME=${DAEMON_ENV_NAME:-tool-e2e}
DAEMON_ID=${DAEMON_ID:-tool-e2e-daemon}
DAEMON_TOKEN=${DAEMON_TOKEN:-e2e-daemon-token}
DAEMON_ENV_ROOT=${DAEMON_ENV_ROOT:-/tmp/kk-studio-e2e-env}
SKILL_DIR=${SKILL_DIR:-"$HOME/.agents/skills"}

BACKEND_JAR=${BACKEND_JAR:-"$REPO_ROOT/web/target/kk-studio-web-1.0.0.jar"}
DAEMON_JAR=${DAEMON_JAR:-"$REPO_ROOT/harness/daemon/target/kk-studio-harness-daemon-1.0.0.jar"}
DAEMON_TOOL_JAR=${DAEMON_TOOL_JAR:-"$REPO_ROOT/harness/tool/target/kk-studio-harness-tool-1.0.0.jar"}
DAEMON_CP_FILE=${DAEMON_CP_FILE:-"$WORK_DIR/daemon.classpath"}

step() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

resolve_java_home() {
  local java_home=${JAVA_HOME_21:-${JAVA_HOME:-}}
  if [ -z "$java_home" ] || [ ! -x "$java_home/bin/java" ]; then
    die "JAVA_HOME_21 or JAVA_HOME must point to JDK 21"
  fi
  echo "$java_home"
}

wait_http() {
  local url=$1
  local name=$2
  local attempts=${3:-90}
  local i
  for i in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  die "timed out waiting for $name: $url"
}

kill_port() {
  local port=$1
  local pids
  pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
  if [ -n "$pids" ]; then
    step "Stopping listeners on :$port ($pids)"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
    sleep 0.4
    pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
    if [ -n "$pids" ]; then
      # shellcheck disable=SC2086
      kill -9 $pids 2>/dev/null || true
    fi
  fi
}

kill_daemon() {
  local pids
  pids=$(pgrep -f 'fun.fengwk.kkstudio.harness.daemon.DaemonMain' || true)
  if [ -n "$pids" ]; then
    step "Stopping daemon ($pids)"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
    sleep 0.4
  fi
}

package_backend() {
  local java_home=$1
  step "Clean packaging backend (Java 21, offline, skipTests)"
  (
    cd "$REPO_ROOT"
    env JAVA_HOME="$java_home" mvn -o -pl web -am -DskipTests clean package
  )
}

package_daemon() {
  local java_home=$1
  step "Clean packaging daemon (Java 21, offline, skipTests)"
  (
    cd "$REPO_ROOT"
    env JAVA_HOME="$java_home" mvn -o -pl harness/daemon -am -DskipTests clean package
  )
}

build_daemon_classpath() {
  local java_home=$1
  mkdir -p "$WORK_DIR"
  step "Building daemon runtime classpath"
  (
    cd "$REPO_ROOT"
    env JAVA_HOME="$java_home" mvn -o -pl harness/daemon -q \
      -DincludeScope=runtime dependency:build-classpath \
      -Dmdep.outputFile="$DAEMON_CP_FILE"
  )
  [ -s "$DAEMON_CP_FILE" ] || die "daemon classpath file empty: $DAEMON_CP_FILE"
}

start_backend() {
  local java_home=$1
  mkdir -p "$WORK_DIR"
  kill_port "$BACKEND_PORT"
  : >"$WORK_DIR/backend.log"
  step "Starting backend $BACKEND_URL profile=$SPRING_PROFILE"
  # Pass PostgreSQL connection overrides when set; e2e profile defaults are only for local loops.
  nohup env JAVA_HOME="$java_home" \
    ${KK_STUDIO_DB_URL:+KK_STUDIO_DB_URL="$KK_STUDIO_DB_URL"} \
    ${KK_STUDIO_DB_USER:+KK_STUDIO_DB_USER="$KK_STUDIO_DB_USER"} \
    ${KK_STUDIO_DB_PASSWORD:+KK_STUDIO_DB_PASSWORD="$KK_STUDIO_DB_PASSWORD"} \
    "$java_home/bin/java" -jar "$BACKEND_JAR" \
    --spring.profiles.active="$SPRING_PROFILE" \
    --server.address="$BACKEND_HOST" \
    --server.port="$BACKEND_PORT" \
    >"$WORK_DIR/backend.log" 2>&1 &
  echo $! >"$WORK_DIR/backend.pid"
  wait_http "$BACKEND_URL/api/ai/catalog/agents?pageNumber=1&pageSize=1" backend 120
}

# Synchronize the complete MiniMax credential pair into the seeded provider after backend readiness.
sync_e2e_provider_credentials() {
  require_cmd python3
  step "Syncing MiniMax E2E credentials from env (secrets not printed)"
  BACKEND_URL="$BACKEND_URL" \
  TEST_MINIMAX_API_KEY="${TEST_MINIMAX_API_KEY-}" TEST_MINIMAX_BASE_URL="${TEST_MINIMAX_BASE_URL-}" \
  python3 "$REPO_ROOT/scripts/e2e/sync_provider_credentials.py" --backend-url "$BACKEND_URL"
}

start_frontend() {
  mkdir -p "$WORK_DIR"
  kill_port "$FRONTEND_PORT"
  : >"$WORK_DIR/frontend.log"
  step "Starting frontend $FRONTEND_URL proxy->$BACKEND_URL"
  (
    cd "$REPO_ROOT/frontend"
    nohup env API_PROXY_TARGET="$BACKEND_URL" npm run dev -- \
      --host "$FRONTEND_HOST" \
      --port "$FRONTEND_PORT" \
      >"$WORK_DIR/frontend.log" 2>&1 &
    echo $! >"$WORK_DIR/frontend.pid"
  )
  wait_http "$FRONTEND_URL/" frontend
  # 额外确认代理已打到当前 backend 契约（结构化 config）
  curl -fsS "$FRONTEND_URL/api/ai/catalog/models?pageNumber=1&pageSize=1" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); m=((d.get("data") or {}).get("results") or [None])[0];
assert m and isinstance(m.get("config"), dict) and m["config"].get("defaultVariant"), m; print("frontend proxy model.config.defaultVariant=", m["config"]["defaultVariant"])'
}

start_daemon() {
  local java_home=$1
  mkdir -p "$WORK_DIR" "$DAEMON_ENV_ROOT"
  kill_daemon
  package_daemon "$java_home"
  build_daemon_classpath "$java_home"
  : >"$WORK_DIR/daemon.log"
  local cp
  # dependency:build-classpath may resolve a previously installed local harness-tool;
  # put the reactor-built jar first so clean-slate daemon/tool protocol changes are exercised.
  cp="$DAEMON_JAR:$DAEMON_TOOL_JAR:$(cat "$DAEMON_CP_FILE")"
  step "Starting daemon env=$DAEMON_ENV_NAME"
  nohup env JAVA_HOME="$java_home" "$java_home/bin/java" \
    -cp "$cp" fun.fengwk.kkstudio.harness.daemon.DaemonMain \
    --environment-name "$DAEMON_ENV_NAME" \
    --gateway-uri "ws://$BACKEND_HOST:$BACKEND_PORT/api/ai/environment/daemon/v2" \
    --gateway-token "$DAEMON_TOKEN" \
    --daemon-id "$DAEMON_ID" \
    --workdir "$DAEMON_ENV_ROOT" \
    --skill-dir "$SKILL_DIR" \
    >"$WORK_DIR/daemon.log" 2>&1 &
  echo $! >"$WORK_DIR/daemon.pid"
  local i env_status=""
  for i in $(seq 1 60); do
    env_status=$(curl -fsS "$BACKEND_URL/api/ai/environment" \
      | python3 -c 'import sys,json; d=json.load(sys.stdin); arr=d.get("data") or [];
print(next((x.get("status") for x in arr if x.get("name")=="'"$DAEMON_ENV_NAME"'"), ""))' \
      2>/dev/null || true)
    if [ "$env_status" = "READY" ]; then
      step "Daemon READY"
      return 0
    fi
    sleep 0.5
  done
  die "daemon not READY (last status='$env_status'); see $WORK_DIR/daemon.log"
}

ensure_stack() {
  local rebuild=${1:-false}
  local with_daemon=${2:-false}
  local java_home
  require_cmd curl
  require_cmd python3
  require_cmd lsof
  require_cmd mvn
  require_cmd npm
  java_home=$(resolve_java_home)
  mkdir -p "$WORK_DIR"

  if [ "$rebuild" = "true" ]; then
    package_backend "$java_home"
    start_backend "$java_home"
    start_frontend
    if [ "$with_daemon" = "true" ]; then
      start_daemon "$java_home"
    fi
    return
  fi

  if [ ! -f "$BACKEND_JAR" ]; then
    package_backend "$java_home"
  fi

  if ! curl -fsS "$BACKEND_URL/api/ai/catalog/agents?pageNumber=1&pageSize=1" >/dev/null 2>&1; then
    start_backend "$java_home"
    sync_e2e_provider_credentials
  else
    step "Reusing backend $BACKEND_URL"
    # Reused processes must expose the current structured model contract.
    if ! curl -fsS "$BACKEND_URL/api/ai/catalog/models?pageNumber=1&pageSize=1" \
      | python3 -c 'import sys,json; d=json.load(sys.stdin); m=((d.get("data") or {}).get("results") or [None])[0];
raise SystemExit(0 if m and isinstance(m.get("config"), dict) else 1)' 2>/dev/null; then
      step "Backend contract stale (missing model.config); rebuilding and restarting"
      package_backend "$java_home"
      start_backend "$java_home"
      sync_e2e_provider_credentials
    fi
  fi

  if ! curl -fsS "$FRONTEND_URL/" >/dev/null 2>&1; then
    start_frontend
  else
    step "Reusing frontend $FRONTEND_URL"
    if ! curl -fsS "$FRONTEND_URL/api/ai/catalog/models?pageNumber=1&pageSize=1" \
      | python3 -c 'import sys,json; d=json.load(sys.stdin); m=((d.get("data") or {}).get("results") or [None])[0];
raise SystemExit(0 if m and isinstance(m.get("config"), dict) else 1)' 2>/dev/null; then
      step "Frontend proxy contract stale; restarting frontend with API_PROXY_TARGET"
      start_frontend
    fi
  fi

  if [ "$with_daemon" = "true" ]; then
    env_status=$(curl -fsS "$BACKEND_URL/api/ai/environment" \
      | python3 -c 'import sys,json; d=json.load(sys.stdin); arr=d.get("data") or [];
print(next((x.get("status") for x in arr if x.get("name")=="'"$DAEMON_ENV_NAME"'"), ""))' \
      2>/dev/null || true)
    if [ "$env_status" != "READY" ]; then
      start_daemon "$java_home"
    else
      step "Reusing daemon env=$DAEMON_ENV_NAME status=READY"
    fi
  fi
}
