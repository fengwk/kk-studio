#!/usr/bin/env bash
# scripts/dev/verify/e2e/lib.sh
#
# 共享函数库：e2e 入口与各 case 共用。
# 约定：
# - 默认 backend=127.0.0.1:18081、frontend=127.0.0.1:5173、profile=e2e
# - 使用 PostgreSQL durable 库；重启 backend 后由宿主 E2E runner 重新同步 provider credentials
# - frontend Vite 代理必须指向当前 backend：API_PROXY_TARGET=http://$BACKEND_HOST:$BACKEND_PORT
# - daemon 是 Node 产品：先 `npm --prefix daemon ci && npm --prefix daemon run build` 产出 `daemon/dist`，
#   再由 bootstrap helper 用 Node 启动 `daemon/dist/main.js --config <daemon.json>`；没有 JVM/JAR/classpath

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/../../lib/repo-root.sh"
resolve_repo_root "$SCRIPT_DIR"
source "$SCRIPT_DIR/../../lib/http-wait.sh"
WORK_DIR=${E2E_WORK_DIR:-"$REPO_ROOT/runtime/e2e"}

BACKEND_HOST=${BACKEND_HOST:-127.0.0.1}
BACKEND_PORT=${BACKEND_PORT:-18081}
FRONTEND_HOST=${FRONTEND_HOST:-127.0.0.1}
FRONTEND_PORT=${FRONTEND_PORT:-5173}
BACKEND_URL=${BACKEND_URL:-"http://$BACKEND_HOST:$BACKEND_PORT"}
FRONTEND_URL=${FRONTEND_URL:-"http://$FRONTEND_HOST:$FRONTEND_PORT"}
SPRING_PROFILE=${SPRING_PROFILES_ACTIVE:-e2e}
DAEMON_ENV_NAME=${DAEMON_ENV_NAME:-tool-e2e}
DAEMON_REGISTRATION_TOKEN=${DAEMON_REGISTRATION_TOKEN:-e2e-token-host-tool}
DAEMON_ENV_ROOT=${DAEMON_ENV_ROOT:-"$WORK_DIR/environment"}
# DAEMON_ROOT 是本次 E2E 隔离的 Daemon 根：daemon.json 与同目录 daemon.token 都在这里，
# 其父目录即 Daemon 运行数据目录。DAEMON_ENV_ROOT 只是 E2E case 使用的任务工作目录
# （fixture 路径与 Tool 调用的 workdir 都由它派生），两者互不影响。
DAEMON_ROOT=${DAEMON_ROOT:-"$WORK_DIR/daemon"}
DAEMON_NOTE=${DAEMON_NOTE:-E2E daemon environment.}

export DAEMON_ENV_ROOT

BACKEND_JAR=${BACKEND_JAR:-"$REPO_ROOT/web/target/kk-studio-web-2.0.1.jar"}
STORAGE_S3_ENDPOINT=${KK_STUDIO_STORAGE_S3_ENDPOINT:-http://127.0.0.1:9000}
STORAGE_S3_PUBLIC_ENDPOINT=${KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT:-http://127.0.0.1:9000}
STORAGE_S3_REGION=${KK_STUDIO_STORAGE_S3_REGION:-us-east-1}
STORAGE_S3_ACCESS_KEY=${KK_STUDIO_STORAGE_S3_ACCESS_KEY:-kk-studio}
STORAGE_S3_SECRET_KEY=${KK_STUDIO_STORAGE_S3_SECRET_KEY:-kk-studio}

is_local_minio_endpoint() {
  case "$STORAGE_S3_ENDPOINT" in
    http://127.0.0.1:9000*|http://localhost:9000*|127.0.0.1:9000*|localhost:9000*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

# S3 bucket 隔离规则：
# - 本地 MinIO (127.0.0.1/localhost:9000) 固定使用专用名 kk-studio-e2e-matrix，忽略环境变量覆盖并执行清理；
# - 外部 S3 endpoint 允许经 KK_STUDIO_STORAGE_S3_BUCKET 覆盖，但绝不执行清理。
if is_local_minio_endpoint; then
  STORAGE_S3_BUCKET="kk-studio-e2e-matrix"
else
  STORAGE_S3_BUCKET=${KK_STUDIO_STORAGE_S3_BUCKET:-kk-studio-e2e-matrix}
fi
DAEMON_DIST=${DAEMON_DIST:-"$REPO_ROOT/daemon/dist"}
# 开发 E2E 用宿主 Node 运行编译产物；发行验收使用归档自带的冻结 Node（由 smoke-archive 负责）。
DAEMON_NODE=${DAEMON_NODE:-}

# E2E 拥有固定专用的独立数据库与 S3 bucket，绝不触碰开发应用的 kk_studio_e2e 或 kk_studio
E2E_DEDICATED_DATABASE="kk_studio_e2e_matrix"
DB_HOST=${KK_STUDIO_DB_HOST:-127.0.0.1}
DB_PORT=${KK_STUDIO_DB_PORT:-5432}
DB_USER=${KK_STUDIO_DB_USER:-kk_studio}
DB_PASSWORD=${KK_STUDIO_DB_PASSWORD:-kk_studio}
if [ -n "${KK_STUDIO_DB_URL:-}" ]; then
  if [[ "$KK_STUDIO_DB_URL" =~ jdbc:postgresql://([^:/]+)(:([0-9]+))?(/([^/?#]+))? ]]; then
    DB_HOST="${BASH_REMATCH[1]}"
    DB_PORT="${BASH_REMATCH[3]:-5432}"
  fi
fi
export KK_STUDIO_DB_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${E2E_DEDICATED_DATABASE}"
export KK_STUDIO_DB_USER="$DB_USER"
export KK_STUDIO_DB_PASSWORD="$DB_PASSWORD"
export KK_STUDIO_STORAGE_S3_BUCKET="$STORAGE_S3_BUCKET"

step() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

# Emit NUL-delimited `env -u` arguments for every live TEST_* input. Runtime process
# isolation must not depend on a manually maintained list of provider credential names.
test_env_unset_args() {
  local name
  while IFS= read -r name; do
    case "$name" in
      TEST_*)
        printf '%s\0' -u "$name"
        ;;
    esac
  done < <(compgen -e)
}

run_maven() {
  local java_home=$1
  shift
  local -a offline_args=()
  case "${E2E_MAVEN_OFFLINE-false}" in
    true)
      offline_args=(-o)
      ;;
    false)
      ;;
    *)
      die "E2E_MAVEN_OFFLINE must be exactly true or false (got '${E2E_MAVEN_OFFLINE-}')"
      ;;
  esac
  env JAVA_HOME="$java_home" mvn "${offline_args[@]}" "$@"
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
  poll_http "$url" "$attempts" && return 0
  die "timed out waiting for $name: $url"
}

wait_http_process() {
  local url=$1
  local name=$2
  local pid=$3
  local log_file=$4
  local attempts=${5:-90}
  local status
  if poll_http "$url" "$attempts" "$pid"; then
    return 0
  else
    status=$?
  fi
  if [ "$status" -eq 2 ]; then
    echo "ERROR: $name process exited before readiness (pid=$pid)" >&2
  else
    echo "ERROR: timed out waiting for $name: $url" >&2
  fi
  if [ -f "$log_file" ]; then
    echo "--- $name log tail ---" >&2
    tail -n 120 "$log_file" >&2 || true
  fi
  exit 1
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
  # daemon 进程命令行携带编译入口 `$DAEMON_DIST/main.js`，按该路径匹配。
  pids=$(pgrep -f -- "$DAEMON_DIST/main.js" || true)
  if [ -n "$pids" ]; then
    step "Stopping daemon ($pids)"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
    sleep 0.4
  fi
}

package_backend() {
  local java_home=$1
  step "Clean packaging backend (Java 21, online by default; E2E_MAVEN_OFFLINE=true opts into offline, skipTests)"
  (
    cd "$REPO_ROOT"
    run_maven "$java_home" -pl web -am -DskipTests clean package
  )
}

package_daemon() {
  mkdir -p "$WORK_DIR"
  step "Building the Node daemon (npm ci + build -> $DAEMON_DIST, no JVM)"
  (
    cd "$REPO_ROOT"
    npm --prefix daemon ci
    npm --prefix daemon run build
  )
  [ -s "$DAEMON_DIST/main.js" ] || die "daemon entry missing: $DAEMON_DIST/main.js"
}

ensure_s3_storage() {
  local s3_endpoint="$STORAGE_S3_ENDPOINT"
  if [ "$s3_endpoint" = "http://127.0.0.1:9000" ] || [ "$s3_endpoint" = "http://localhost:9000" ]; then
    if ! curl -fsS "http://127.0.0.1:9000/minio/health/live" >/dev/null 2>&1; then
      step "Ensuring local MinIO dependency (deploy/local/compose.yaml)"
      command -v docker >/dev/null 2>&1 || die "docker is required to run local MinIO dependency"
      docker compose -f "$REPO_ROOT/deploy/local/compose.yaml" up -d minio minio-init
      wait_http "http://127.0.0.1:9000/minio/health/live" "minio" 30
    fi
  fi
}

ensure_dedicated_s3_bucket() {
  if ! is_local_minio_endpoint; then
    step "External S3 endpoint configured ($STORAGE_S3_ENDPOINT); skipping bucket cleanup"
    return 0
  fi

  ensure_s3_storage
  step "Ensuring clean dedicated E2E bucket $STORAGE_S3_BUCKET"
  local init_script="$REPO_ROOT/deploy/dependencies/minio-client/init-bucket.sh"
  (
    export MINIO_ROOT_USER="$STORAGE_S3_ACCESS_KEY"
    export MINIO_ROOT_PASSWORD="$STORAGE_S3_SECRET_KEY"
    export MINIO_BUCKET="$STORAGE_S3_BUCKET"
    export MINIO_ALIAS=e2e-local
    export MINIO_ENDPOINT="$STORAGE_S3_ENDPOINT"
    if command -v mc >/dev/null 2>&1; then
      sh "$init_script" --clean-existing
    elif command -v docker >/dev/null 2>&1; then
      # Read the checkout's script before invoking mc, so an older image is safe
      # and mc cannot consume the script from the container's stdin.
      docker run --rm -i --net host \
        --env MINIO_ROOT_USER \
        --env MINIO_ROOT_PASSWORD \
        --env MINIO_BUCKET \
        --env MINIO_ALIAS \
        --env MINIO_ENDPOINT \
        --entrypoint /bin/sh \
        kk-studio-minio-client:RELEASE.2025-04-16T18-13-26Z \
        -ec 'script=$(cat); exec /bin/sh -c "$script" -- --clean-existing' <"$init_script"
    else
      die "cannot initialize E2E S3 bucket: mc is not installed and docker is not available"
    fi
  )
}

exec_postgres_admin_sql() {
  local sql=$1
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres \
      -v ON_ERROR_STOP=1 -c "$sql"
  elif command -v docker >/dev/null 2>&1 && [ -n "$(docker compose -f "$REPO_ROOT/deploy/local/compose.yaml" ps --status running -q postgres 2>/dev/null)" ]; then
    docker compose -f "$REPO_ROOT/deploy/local/compose.yaml" exec -T -e PGPASSWORD="$DB_PASSWORD" postgres \
      psql -h 127.0.0.1 -p 5432 -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -c "$sql"
  else
    die "cannot reset E2E database: psql is not installed on host and deploy/local postgres container is not running"
  fi
}

reset_e2e_database() {
  step "Resetting dedicated E2E database $E2E_DEDICATED_DATABASE on $DB_HOST:$DB_PORT"
  exec_postgres_admin_sql "DROP DATABASE IF EXISTS $E2E_DEDICATED_DATABASE WITH (FORCE);"
  exec_postgres_admin_sql "CREATE DATABASE $E2E_DEDICATED_DATABASE;"
}

start_backend() {
  local java_home=$1
  mkdir -p "$WORK_DIR"
  kill_port "$BACKEND_PORT"
  reset_e2e_database
  ensure_dedicated_s3_bucket
  : >"$WORK_DIR/backend.log"
  step "Starting backend $BACKEND_URL profile=$SPRING_PROFILE"
  # Pass PostgreSQL connection overrides when set; e2e profile defaults are only for local loops.
  # Explicitly scrub every TEST_* input from the backend Java process.
  local -a test_env_unsets=()
  mapfile -d '' -t test_env_unsets < <(test_env_unset_args)
  nohup env \
    "${test_env_unsets[@]}" \
    JAVA_HOME="$java_home" \
    ${KK_STUDIO_DB_URL:+KK_STUDIO_DB_URL="$KK_STUDIO_DB_URL"} \
    ${KK_STUDIO_DB_USER:+KK_STUDIO_DB_USER="$KK_STUDIO_DB_USER"} \
    ${KK_STUDIO_DB_PASSWORD:+KK_STUDIO_DB_PASSWORD="$KK_STUDIO_DB_PASSWORD"} \
    KK_STUDIO_STORAGE_S3_ENDPOINT="$STORAGE_S3_ENDPOINT" \
    KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT="$STORAGE_S3_PUBLIC_ENDPOINT" \
    KK_STUDIO_STORAGE_S3_REGION="$STORAGE_S3_REGION" \
    KK_STUDIO_STORAGE_S3_BUCKET="$STORAGE_S3_BUCKET" \
    KK_STUDIO_STORAGE_S3_ACCESS_KEY="$STORAGE_S3_ACCESS_KEY" \
    KK_STUDIO_STORAGE_S3_SECRET_KEY="$STORAGE_S3_SECRET_KEY" \
    "$java_home/bin/java" -jar "$BACKEND_JAR" \
    --spring.profiles.active="$SPRING_PROFILE" \
    --server.address="$BACKEND_HOST" \
    --server.port="$BACKEND_PORT" \
    >"$WORK_DIR/backend.log" 2>&1 &
  local pid=$!
  echo "$pid" >"$WORK_DIR/backend.pid"
  wait_http_process \
    "$BACKEND_URL/api/platform/agents" \
    backend \
    "$pid" \
    "$WORK_DIR/backend.log" \
    120
}

source "$REPO_ROOT/scripts/dev/lib/e2e-credentials.sh"

start_frontend() {
  mkdir -p "$WORK_DIR"
  kill_port "$FRONTEND_PORT"
  : >"$WORK_DIR/frontend.log"
  step "Starting frontend $FRONTEND_URL proxy->$BACKEND_URL"
  (
    cd "$REPO_ROOT/frontend"
    local -a test_env_unsets=()
    mapfile -d '' -t test_env_unsets < <(test_env_unset_args)
    nohup env \
      "${test_env_unsets[@]}" \
      API_PROXY_TARGET="$BACKEND_URL" npm run dev -- \
      --host "$FRONTEND_HOST" \
      --port "$FRONTEND_PORT" \
      >"$WORK_DIR/frontend.log" 2>&1 &
    echo $! >"$WORK_DIR/frontend.pid"
  )
  local pid
  pid=$(cat "$WORK_DIR/frontend.pid")
  wait_http_process "$FRONTEND_URL/" frontend "$pid" "$WORK_DIR/frontend.log"
  # 额外确认代理已打到当前 backend 契约（结构化 config）
  curl -fsS "$FRONTEND_URL/api/platform/models" \
    | node "$SCRIPT_DIR/lib/json-probe.mjs" model-config-print
}

start_daemon() {
  mkdir -p "$WORK_DIR" "$DAEMON_ENV_ROOT"
  kill_daemon
  package_daemon
  : >"$WORK_DIR/daemon.log"
  step "Starting daemon env=$DAEMON_ENV_NAME"
  if [ -z "$DAEMON_REGISTRATION_TOKEN" ]; then
    die "DAEMON_REGISTRATION_TOKEN is required to start the daemon"
  fi
  local -a test_env_unsets=()
  mapfile -d '' -t test_env_unsets < <(test_env_unset_args)
  # 只有 bootstrap helper 物化 daemon.json/daemon.token；daemon 只接收 --config。
  # 凭证经 assignment 前缀进入子进程环境（不出现在任何 argv），helper 在 exec 前 unset。
  DAEMON_REGISTRATION_TOKEN="$DAEMON_REGISTRATION_TOKEN" nohup env \
    "${test_env_unsets[@]}" \
    DAEMON_ROOT="$DAEMON_ROOT" \
    DAEMON_STUDIO_URL="http://$BACKEND_HOST:$BACKEND_PORT" \
    DAEMON_NOTE="$DAEMON_NOTE" \
    DAEMON_DIST="$DAEMON_DIST" \
    DAEMON_NODE="$DAEMON_NODE" \
    bash "$SCRIPT_DIR/lib/daemon-bootstrap.sh" \
    >"$WORK_DIR/daemon.log" 2>&1 &
  echo $! >"$WORK_DIR/daemon.pid"
  local _ env_status=""
  # Disconnect retains the default 60s route grace lease; leave takeover headroom.
  for _ in $(seq 1 180); do
    env_status=$(curl -fsS "$BACKEND_URL/api/platform/environments" \
      | node "$SCRIPT_DIR/lib/json-probe.mjs" environment-status "$DAEMON_ENV_NAME" \
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
  require_cmd node
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
      start_daemon
    fi
    return
  fi

  if [ ! -f "$BACKEND_JAR" ]; then
    package_backend "$java_home"
  fi

  if ! curl -fsS "$BACKEND_URL/api/platform/agents" >/dev/null 2>&1; then
    start_backend "$java_home"
  else
    step "Reusing backend $BACKEND_URL"
    # Reused processes must expose the current structured model contract.
    if ! curl -fsS "$BACKEND_URL/api/platform/models" \
      | node "$SCRIPT_DIR/lib/json-probe.mjs" model-config-check 2>/dev/null; then
      step "Backend contract stale (missing model.config); rebuilding and restarting"
      package_backend "$java_home"
      start_backend "$java_home"
    fi
  fi

  if ! curl -fsS "$FRONTEND_URL/" >/dev/null 2>&1; then
    start_frontend
  else
    step "Reusing frontend $FRONTEND_URL"
    if ! curl -fsS "$FRONTEND_URL/api/platform/models" \
      | node "$SCRIPT_DIR/lib/json-probe.mjs" model-config-check 2>/dev/null; then
      step "Frontend proxy contract stale; restarting frontend with API_PROXY_TARGET"
      start_frontend
    fi
  fi

  if [ "$with_daemon" = "true" ]; then
    env_status=$(curl -fsS "$BACKEND_URL/api/platform/environments" \
      | node "$SCRIPT_DIR/lib/json-probe.mjs" environment-status "$DAEMON_ENV_NAME" \
      2>/dev/null || true)
    if [ "$env_status" != "READY" ]; then
      start_daemon
    else
      step "Reusing daemon env=$DAEMON_ENV_NAME status=READY"
    fi
  fi
}
