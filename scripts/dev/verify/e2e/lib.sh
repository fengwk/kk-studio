#!/usr/bin/env bash
# scripts/dev/verify/e2e/lib.sh
#
# 共享函数库：e2e 入口与各 case 共用。
# 约定：
# - frontend Vite 代理必须指向当前 backend：API_PROXY_TARGET=http://$BACKEND_HOST:$BACKEND_PORT
# - Environment Daemon 使用 Go 1.27.4 构建的独立二进制；bootstrap helper 用它读取 daemon.json 启动。

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/../../lib/repo-root.sh"
resolve_repo_root "$SCRIPT_DIR"
source "$SCRIPT_DIR/../../lib/http-wait.sh"
source "$SCRIPT_DIR/../../lib/toolchain.sh"
source "$SCRIPT_DIR/../../lib/backend-jar.sh"
WORK_DIR=${E2E_WORK_DIR:-"$REPO_ROOT/runtime/e2e"}
STACK_COMPOSE_FILE="$SCRIPT_DIR/stack.compose.yaml"
STACK_PROJECT="kk-studio-e2e-${UID:-$(id -u)}-$$"
STACK_STARTED=false
OWNED_BACKEND_PID=''
OWNED_FRONTEND_PID=''
OWNED_DAEMON_PID=''

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
# 卡片由 name 或 UUID 定位；默认使用 e2e seed 中的 tool-e2e 卡片。
DAEMON_ENVIRONMENT_NAME=${DAEMON_ENVIRONMENT_NAME:-$DAEMON_ENV_NAME}
DAEMON_ENVIRONMENT_ID=${DAEMON_ENVIRONMENT_ID:-}

export DAEMON_ENV_ROOT

# 产物名由根 POM 版本决定；调用方已构建好产物时可用 BACKEND_JAR 覆盖。
BACKEND_JAR=${BACKEND_JAR:-$(backend_jar_path "$REPO_ROOT")}
STORAGE_S3_ENDPOINT=''
STORAGE_S3_PUBLIC_ENDPOINT=''
STORAGE_S3_REGION=${KK_STUDIO_STORAGE_S3_REGION:-us-east-1}
STORAGE_S3_ACCESS_KEY=kk-studio
STORAGE_S3_SECRET_KEY=kk-studio

STORAGE_S3_BUCKET=kk-studio-e2e-matrix
DAEMON_DIST=${DAEMON_DIST:-"$WORK_DIR/daemon-distribution"}
DAEMON_BINARY=${DAEMON_BINARY:-"$DAEMON_DIST/kk-studio-daemon"}
GO_BIN=${GO_BIN:-/usr/local/opt/go/bin/go}

# E2E 拥有固定专用的独立数据库与 S3 bucket，绝不触碰开发应用的 kk_studio_e2e 或 kk_studio
E2E_DEDICATED_DATABASE="kk_studio_e2e_matrix"
DB_HOST=127.0.0.1
DB_PORT=''
DB_USER=kk_studio
DB_PASSWORD=kk_studio

export KK_STUDIO_DB_USER="$DB_USER"
export KK_STUDIO_DB_PASSWORD="$DB_PASSWORD"
export KK_STUDIO_STORAGE_S3_BUCKET="$STORAGE_S3_BUCKET"

step() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

runtime_log_target() {
  if [ "${RESULTS_ONLY:-false}" = "true" ]; then
    printf '/dev/null\n'
  else
    printf '%s/%s.log\n' "$WORK_DIR" "$1"
  fi
}

source "$SCRIPT_DIR/lib/studio-auth.sh"
E2E_STUDIO_COOKIE=''

source "$REPO_ROOT/scripts/dev/lib/e2e-credentials.sh"

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

package_backend() {
  local java_home=$1
  step "Clean packaging backend (Java 21, online by default; E2E_MAVEN_OFFLINE=true opts into offline, skipTests)"
  (
    cd "$REPO_ROOT"
    run_maven "$java_home" -pl web -am -DskipTests clean package
  )
}

package_daemon() {
  mkdir -p "$DAEMON_DIST"
  step "Building the Go 1.27.4 daemon ($DAEMON_BINARY)"
  [ -x "$GO_BIN" ] || die "Go compiler not executable: $GO_BIN"
  local version
  version=$("$GO_BIN" version)
  [[ "$version" == *"go1.27.4"* ]] || die "Go 1.27.4 is required; found: $version"
  (
    cd "$REPO_ROOT/daemon"
    CGO_ENABLED=0 "$GO_BIN" build -o "$DAEMON_BINARY" ./cmd/kk-studio-daemon
  )
  rm -rf "$DAEMON_DIST/ide-extension"
  cp -R "$REPO_ROOT/daemon/ide-extension" "$DAEMON_DIST/ide-extension"
  [ -x "$DAEMON_BINARY" ] || die "daemon binary missing: $DAEMON_BINARY"
}

compose_stack() {
  docker compose -p "$STACK_PROJECT" -f "$STACK_COMPOSE_FILE" "$@"
}

start_owned_stack() {
  command -v docker >/dev/null 2>&1 || die "docker is required for the isolated E2E dependency stack"
  local mapping
  step "Starting isolated E2E dependencies (project=$STACK_PROJECT)"
  STACK_STARTED=true
  compose_stack up -d --wait postgres minio
  local minio_client_image=kk-studio-minio-client:RELEASE.2025-04-16T18-13-26Z
  if ! docker image inspect "$minio_client_image" >/dev/null 2>&1; then
    step "Building missing MinIO init image $minio_client_image"
    compose_stack build minio-init
  fi
  compose_stack run --rm minio-init
  mapping=$(compose_stack port postgres 5432)
  DB_PORT=${mapping##*:}
  mapping=$(compose_stack port minio 9000)
  STORAGE_S3_ENDPOINT="http://127.0.0.1:${mapping##*:}"
  STORAGE_S3_PUBLIC_ENDPOINT=$STORAGE_S3_ENDPOINT
  export KK_STUDIO_DB_URL="jdbc:postgresql://127.0.0.1:${DB_PORT}/${E2E_DEDICATED_DATABASE}"
  export KK_STUDIO_STORAGE_S3_ENDPOINT="$STORAGE_S3_ENDPOINT"
  export KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT="$STORAGE_S3_PUBLIC_ENDPOINT"
  wait_http "$STORAGE_S3_ENDPOINT/minio/health/live" minio 30
}

cleanup_owned_stack() {
  local status=$?
  set +e
  for pid in "$OWNED_BACKEND_PID" "$OWNED_FRONTEND_PID" "$OWNED_DAEMON_PID"; do
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      for _ in {1..20}; do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
      kill -9 "$pid" 2>/dev/null || true
    fi
  done
  rm -f "$WORK_DIR/backend.pid" "$WORK_DIR/frontend.pid" "$WORK_DIR/daemon.pid"
  if [ "$STACK_STARTED" = true ]; then
    compose_stack down --volumes --remove-orphans
  fi
  exit "$status"
}

exec_postgres_admin_sql() {
  local sql=$1
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres \
      -v ON_ERROR_STOP=1 -c "$sql"
  else
    compose_stack exec -T -e PGPASSWORD="$DB_PASSWORD" postgres \
      psql -h 127.0.0.1 -p 5432 -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -c "$sql"
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
  if lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    die "backend port $BACKEND_PORT is already in use; refusing to stop an unrelated process"
  fi
  reset_e2e_database
  local log_file
  log_file=$(runtime_log_target backend)
  : >"$log_file"
  local -a jvm_logging_args=() spring_logging_args=()
  if [ "${RESULTS_ONLY:-false}" = "true" ]; then
    # Cover Logback's early auto-init and Boot's subsequent logging initialization.
    local config="$SCRIPT_DIR/logback-results-only.xml"
    [ -r "$config" ] || die "Missing results-only Logback configuration"
    jvm_logging_args=("-Dlogback.configurationFile=$config")
    spring_logging_args=("--logging.config=file:$config" --server.tomcat.accesslog.enabled=false)
  fi
  step "Starting backend $BACKEND_URL profile=$SPRING_PROFILE"
  # Backend always connects to the dedicated E2E database and bucket; scrub every TEST_* input.
  local -a test_env_unsets=()
  mapfile -d '' -t test_env_unsets < <(test_env_unset_args)
  nohup env \
    "${test_env_unsets[@]}" \
    JAVA_HOME="$java_home" \
    KK_STUDIO_EXIT_CONFIRMATION_DIRECTORY="$WORK_DIR/execution-exits" \
    KK_STUDIO_DB_URL="$KK_STUDIO_DB_URL" \
    KK_STUDIO_DB_USER="$KK_STUDIO_DB_USER" \
    KK_STUDIO_DB_PASSWORD="$KK_STUDIO_DB_PASSWORD" \
    KK_STUDIO_STORAGE_S3_ENDPOINT="$STORAGE_S3_ENDPOINT" \
    KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT="$STORAGE_S3_PUBLIC_ENDPOINT" \
    KK_STUDIO_STORAGE_S3_REGION="$STORAGE_S3_REGION" \
    KK_STUDIO_STORAGE_S3_BUCKET="$STORAGE_S3_BUCKET" \
    KK_STUDIO_STORAGE_S3_ACCESS_KEY="$STORAGE_S3_ACCESS_KEY" \
    KK_STUDIO_STORAGE_S3_SECRET_KEY="$STORAGE_S3_SECRET_KEY" \
    "$java_home/bin/java" "${jvm_logging_args[@]}" -jar "$BACKEND_JAR" \
    --spring.profiles.active="$SPRING_PROFILE" \
    --server.address="$BACKEND_HOST" \
    --server.port="$BACKEND_PORT" \
    "${spring_logging_args[@]}" \
    >"$log_file" 2>&1 &
  local pid=$!
  OWNED_BACKEND_PID=$pid
  echo "$pid" >"$WORK_DIR/backend.pid"
  wait_http_process \
    "$BACKEND_URL/actuator/health" \
    backend \
    "$pid" \
    "$log_file" \
    120
  E2E_STUDIO_COOKIE=$(e2e_studio_login "$BACKEND_URL")
}

start_frontend() {
  mkdir -p "$WORK_DIR"
  if lsof -nP -iTCP:"$FRONTEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    die "frontend port $FRONTEND_PORT is already in use; refusing to stop an unrelated process"
  fi
  local log_file
  log_file=$(runtime_log_target frontend)
  : >"$log_file"
  step "Starting frontend $FRONTEND_URL proxy->$BACKEND_URL"
  (
    cd "$REPO_ROOT/frontend"
    local -a test_env_unsets=()
    mapfile -d '' -t test_env_unsets < <(test_env_unset_args)
    nohup env \
      "${test_env_unsets[@]}" \
      API_PROXY_TARGET="$BACKEND_URL" "$REPO_ROOT/frontend/node_modules/.bin/vite" \
      --host "$FRONTEND_HOST" \
      --port "$FRONTEND_PORT" \
      --strictPort \
      >"$log_file" 2>&1 &
    echo $! >"$WORK_DIR/frontend.pid"
  )
  local pid
  pid=$(cat "$WORK_DIR/frontend.pid")
  OWNED_FRONTEND_PID=$pid
  wait_http_process "$FRONTEND_URL/" frontend "$pid" "$log_file"
  # 额外确认代理已打到当前 backend 契约（结构化 config）
  e2e_studio_get "$E2E_STUDIO_COOKIE" "$FRONTEND_URL/api/platform/models" \
    | node "$SCRIPT_DIR/lib/json-probe.mjs" model-config-print
}

start_daemon() {
  mkdir -p "$WORK_DIR" "$DAEMON_ENV_ROOT"
  package_daemon
  local log_file
  log_file=$(runtime_log_target daemon)
  : >"$log_file"
  step "Starting daemon env=$DAEMON_ENV_NAME"
  if [ -z "$DAEMON_REGISTRATION_TOKEN" ]; then
    die "DAEMON_REGISTRATION_TOKEN is required to start the daemon"
  fi
  local -a test_env_unsets=()
  mapfile -d '' -t test_env_unsets < <(test_env_unset_args)
  # 只有 bootstrap helper 物化 daemon.json/daemon.token；daemon 只接收 --config。
  # 凭证经 assignment 前缀进入子进程环境（不出现在任何 argv），helper 在 exec 前 unset。
  local -a environment_selector=()
  if [ -n "$DAEMON_ENVIRONMENT_ID" ]; then
    environment_selector=(DAEMON_ENVIRONMENT_ID="$DAEMON_ENVIRONMENT_ID")
  else
    environment_selector=(DAEMON_ENVIRONMENT_NAME="$DAEMON_ENVIRONMENT_NAME")
  fi
  DAEMON_REGISTRATION_TOKEN="$DAEMON_REGISTRATION_TOKEN" nohup env \
    "${test_env_unsets[@]}" \
    DAEMON_ROOT="$DAEMON_ROOT" \
    DAEMON_STUDIO_URL="$BACKEND_URL" \
    "${environment_selector[@]}" \
    DAEMON_BINARY="$DAEMON_BINARY" \
    bash "$SCRIPT_DIR/lib/daemon-bootstrap.sh" \
    >"$log_file" 2>&1 <<<"$E2E_STUDIO_COOKIE" &
  OWNED_DAEMON_PID=$!
  echo "$OWNED_DAEMON_PID" >"$WORK_DIR/daemon.pid"
  local _ env_status=""
  # Disconnect retains the default 60s route grace lease; leave takeover headroom.
  for _ in $(seq 1 180); do
    env_status=$(e2e_studio_get "$E2E_STUDIO_COOKIE" "$BACKEND_URL/api/platform/environments" \
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
  if [ "${RESULTS_ONLY:-false}" = "true" ]; then
    [[ "$BACKEND_HOST" == 127.0.0.1 && "$FRONTEND_HOST" == 127.0.0.1 \
      && "$BACKEND_PORT" =~ ^[0-9]+$ && "$FRONTEND_PORT" =~ ^[0-9]+$ \
      && "$BACKEND_URL" == "http://127.0.0.1:$BACKEND_PORT" \
      && "$FRONTEND_URL" == "http://127.0.0.1:$FRONTEND_PORT" ]] \
      || die "Results-only mode requires owned loopback endpoints"
  fi
  require_cmd curl
  require_cmd node
  require_cmd lsof
  require_cmd mvn
  require_cmd npm
  [ -x "$REPO_ROOT/frontend/node_modules/.bin/vite" ] || die "Frontend dependencies are missing; run npm --prefix frontend ci"
  java_home=$(resolve_java_home)
  mkdir -p "$WORK_DIR"
  start_owned_stack
  if [ "$rebuild" = "true" ] || [ ! -f "$BACKEND_JAR" ]; then
    package_backend "$java_home"
  fi
  start_backend "$java_home"
  start_frontend

  if [ "$with_daemon" = "true" ]; then
    start_daemon
  fi
}
