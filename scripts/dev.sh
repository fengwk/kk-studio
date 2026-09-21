#!/usr/bin/env bash
set -euo pipefail

SCRIPT_HOME=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
APP_HOME=$(cd "$SCRIPT_HOME/.." && pwd)
WORK_DIR=${DEV_WORK_DIR:-"$APP_HOME/runtime/dev"}

# 本机 preview 的外部数据面配置：只有调用方显式给出 DEV_ENV_FILE 时才读取（见 usage），
# 未给出时本脚本保持通用/e2e 行为，不接触任何外部配置文件。
DEV_ENV_FILE=${DEV_ENV_FILE:-}
# 配置文件允许出现的键。整份白名单之外的名字一律视为配置错误，避免把任意环境变量注入
# 长驻 Backend/Vite 进程。
DEV_ENV_REQUIRED_KEYS=(
  KK_STUDIO_DB_URL
  KK_STUDIO_DB_USER
  KK_STUDIO_DB_PASSWORD
  KK_STUDIO_STORAGE_S3_ENDPOINT
  KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT
  KK_STUDIO_STORAGE_S3_REGION
  KK_STUDIO_STORAGE_S3_BUCKET
  KK_STUDIO_STORAGE_S3_ACCESS_KEY
  KK_STUDIO_STORAGE_S3_SECRET_KEY
)
DEV_ENV_OPTIONAL_KEYS=(
  KK_STUDIO_PLUGINS_CREDENTIAL_KEY_FILE
  KK_STUDIO_CANVAS_H3_COMFY_BEARER_TOKEN
)

fail() {
  echo "ERROR: $1" >&2
  exit 1
}

step() {
  echo "==> $1"
}

# 属主与权限查询只返回单个数值；失败时由调用方按「无法读取」处理，不回显文件内容。
file_owner_uid() {
  stat -c '%u' "$1" 2>/dev/null || stat -f '%u' "$1" 2>/dev/null
}

file_mode() {
  stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1" 2>/dev/null
}

dev_env_key_allowed() {
  local key=$1 candidate
  for candidate in "${DEV_ENV_REQUIRED_KEYS[@]}" "${DEV_ENV_OPTIONAL_KEYS[@]}"; do
    if [ "$key" = "$candidate" ]; then
      return 0
    fi
  done
  return 1
}

# 读取 owner-only 的 KEY=VALUE 文件：逐行字面量解析，只按第一个 `=` 拆分，忽略空行与
# 注释行，不做 source/eval 或任何 shell 求值。任何错误信息只包含行号与键名，绝不打印值。
load_dev_env_file() {
  local file=$1
  local line_number=0 line trimmed key value candidate
  local owner mode
  case "$file" in
    /*) ;;
    *) fail "DEV_ENV_FILE must be an absolute path" ;;
  esac
  if [ ! -f "$file" ]; then
    fail "DEV_ENV_FILE must be an existing regular file"
  fi
  if [ -L "$file" ]; then
    fail "DEV_ENV_FILE must not be a symbolic link"
  fi
  owner=$(file_owner_uid "$file") || fail "cannot read the DEV_ENV_FILE owner"
  if [ "$owner" != "$(id -u)" ]; then
    fail "DEV_ENV_FILE must be owned by the current user"
  fi
  mode=$(file_mode "$file") || fail "cannot read the DEV_ENV_FILE permissions"
  case "$mode" in
    *[!0-7]*) fail "cannot interpret the DEV_ENV_FILE permissions" ;;
  esac
  if (( (8#$mode & 8#077) != 0 )); then
    fail "DEV_ENV_FILE must not grant group or other permissions"
  fi

  # 配置文件是这组键的唯一事实源，不能与调用 shell 偶然继承的同名变量混用。先清空，再把
  # 解析值保留为未导出的 shell 变量；只有 Backend fork 的瞬间才向子进程导出。
  for candidate in "${DEV_ENV_REQUIRED_KEYS[@]}" "${DEV_ENV_OPTIONAL_KEYS[@]}"; do
    unset "$candidate"
  done
  while IFS= read -r line || [ -n "$line" ]; do
    line_number=$((line_number + 1))
    line=${line%$'\r'}
    trimmed=${line#"${line%%[![:space:]]*}"}
    case "$trimmed" in
      '' | '#'*) continue ;;
    esac
    case "$trimmed" in
      *=*) ;;
      *) fail "malformed DEV_ENV_FILE line $line_number: expected KEY=VALUE" ;;
    esac
    key=${trimmed%%=*}
    value=${trimmed#*=}
    if ! dev_env_key_allowed "$key"; then
      fail "unknown key on DEV_ENV_FILE line $line_number: $key"
    fi
    printf -v "$key" '%s' "$value"
  done < "$file"
  step "Loaded external data-plane settings from DEV_ENV_FILE (values not printed)"
}

if [ -n "$DEV_ENV_FILE" ]; then
  load_dev_env_file "$DEV_ENV_FILE"
fi

BACKEND_HOST=${BACKEND_HOST:-127.0.0.1}
BACKEND_PORT=${BACKEND_PORT:-18080}
FRONTEND_HOST=${FRONTEND_HOST:-127.0.0.1}
FRONTEND_PORT=${FRONTEND_PORT:-5173}
SPRING_PROFILE=${SPRING_PROFILES_ACTIVE:-e2e}
# scripts/local-dev.sh 用该标记声明「本次运行必须连外部数据面」：此时 profile、Flyway 与
# Harness worker 开关都必须与 NAS 上唯一 Flyway owner / Harness worker 的取值一致。
KK_STUDIO_LOCAL_DEV_EXTERNAL_SERVICES=${KK_STUDIO_LOCAL_DEV_EXTERNAL_SERVICES:-false}

# 外部数据面 preview 的 fail-closed 校验：配置缺失、profile 不是 prod，或本机进程试图
# 承担 Flyway / 分布式 Work，都在启动任何服务之前失败。
require_external_data_plane() {
  local key
  if [ -z "$DEV_ENV_FILE" ]; then
    fail "KK_STUDIO_LOCAL_DEV_EXTERNAL_SERVICES=true requires DEV_ENV_FILE"
  fi
  for key in "${DEV_ENV_REQUIRED_KEYS[@]}"; do
    if [ -z "${!key:-}" ]; then
      fail "DEV_ENV_FILE must define a value for $key"
    fi
  done
  if [ "$SPRING_PROFILE" != "prod" ]; then
    fail "external data-plane preview requires SPRING_PROFILES_ACTIVE=prod"
  fi
  if [ "${SPRING_FLYWAY_ENABLED:-}" != "false" ]; then
    fail "external data-plane preview requires SPRING_FLYWAY_ENABLED=false"
  fi
  if [ "${KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED:-}" != "false" ]; then
    fail "external data-plane preview requires KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false"
  fi
}

if [ "$KK_STUDIO_LOCAL_DEV_EXTERNAL_SERVICES" = "true" ]; then
  require_external_data_plane
fi

BACKEND_URL="http://$BACKEND_HOST:$BACKEND_PORT"
FRONTEND_URL="http://$FRONTEND_HOST:$FRONTEND_PORT"
BACKEND_LOG="$WORK_DIR/backend.log"
FRONTEND_LOG="$WORK_DIR/frontend.log"
BACKEND_PID_FILE="$WORK_DIR/backend.pid"
FRONTEND_PID_FILE="$WORK_DIR/frontend.pid"
BACKEND_JAR="$APP_HOME/web/target/kk-studio-web-1.0.0.jar"
# 产物与修订绑定：stamp 与 JAR 同目录，`mvn clean` 会同时移除二者。
BACKEND_JAR_REVISION_STAMP="$APP_HOME/web/target/.kk-studio-revision"
FRONTEND_PACKAGE_LOCK="$APP_HOME/frontend/package-lock.json"
FRONTEND_NODE_MODULES="$APP_HOME/frontend/node_modules"
FRONTEND_PACKAGE_LOCK_STAMP="$FRONTEND_NODE_MODULES/.kk-studio-package-lock.sha"

KILL_PORTS=${DEV_KILL_PORTS:-true}
SKIP_PACKAGE=${DEV_SKIP_PACKAGE:-false}
SKIP_NPM_INSTALL=${DEV_SKIP_NPM_INSTALL:-false}
READY_TIMEOUT_SECONDS=${DEV_READY_TIMEOUT_SECONDS:-90}
LOG_LINES=${LOG_LINES:-120}

# 当前工作区修订；非 Git 工作区（源码快照）输出空值，此时构建产物不受修订绑定约束。
current_revision() {
  git -C "$APP_HOME" rev-parse HEAD 2>/dev/null || true
}

# 判断已有构建产物是否仍然对应当前修订：产物不存在时必须重建；非 Git 工作区
# （expected-value 为空）只要产物存在就沿用；否则要求产物与记录该产物的修订 stamp
# 一致。`mvn clean` 会同时移除产物与同目录 stamp，因此旧 stamp 不会掩盖缺失的产物。
artifact_current() {
  local artifact=$1
  local stamp=$2
  local expected=$3
  local recorded
  if [ ! -e "$artifact" ]; then
    return 1
  fi
  if [ -z "$expected" ]; then
    return 0
  fi
  if [ ! -f "$stamp" ]; then
    return 1
  fi
  # stamp 由脚本以 `printf '%s\n'` 写入，比较前去掉任意空白，避免换行/CR 造成假不匹配。
  recorded=$(cat "$stamp")
  recorded=${recorded//[[:space:]]/}
  [ "$recorded" = "$expected" ]
}

# package-lock.json 的内容摘要；缺失时返回非零，由调用方退回既有行为。
frontend_package_lock_digest() {
  if [ ! -f "$FRONTEND_PACKAGE_LOCK" ]; then
    return 1
  fi
  git hash-object "$FRONTEND_PACKAGE_LOCK"
}

# 记录本次安装所对应的 lock 摘要；`npm ci` 会重建整个 node_modules，所以 stamp 只在
# 安装完成之后写入。
record_frontend_package_lock_stamp() {
  local digest=$1
  if [ -z "$digest" ] || [ ! -d "$FRONTEND_NODE_MODULES" ]; then
    return
  fi
  printf '%s\n' "$digest" > "$FRONTEND_PACKAGE_LOCK_STAMP"
}

usage() {
  cat <<EOF
Usage: $0 {start|stop|restart|status|logs|tail}

Commands:
  start    Package backend when its recorded revision is stale, optionally sync the four real E2E providers, then start backend and frontend.
  stop     Stop managed dev servers and, by default, listeners on dev ports.
  restart  Stop then start.
  status   Print process status and URLs.
  logs     Print recent logs. Optional target: backend, frontend, all.
  tail     Follow logs. Optional target: backend, frontend, all.

Environment:
  BACKEND_PORT=18080
  FRONTEND_PORT=5173
  SPRING_PROFILES_ACTIVE=e2e   # dev/e2e 均使用 PostgreSQL；dev=stub seed，e2e=real provider seed
  DEV_ENV_FILE=/absolute/path/to/local-dev.env
  # 只有显式给出时才按字面量解析 KEY=VALUE 外部数据面配置（owner-only、无 group/other 权限位、
  # 非符号链接）；未给出时脚本行为与过去一致。
  # scripts/local-dev.sh 会额外设置 KK_STUDIO_LOCAL_DEV_EXTERNAL_SERVICES=true，此时缺配置或
  # profile/Flyway/worker 开关不是 prod/false/false 都在启动前失败。
  TEST_GOOGLE_BASE_URL / TEST_GOOGLE_API_KEY
  TEST_OPENAI_BASE_URL / TEST_OPENAI_API_KEY
  TEST_ANTHROPIC_BASE_URL / TEST_ANTHROPIC_API_KEY
  TEST_DEEPSEEK_BASE_URL / TEST_DEEPSEEK_API_KEY
  # e2e profile: complete pairs are written to the matching seeded providers after backend readiness
  DEV_KILL_PORTS=true
  DEV_SKIP_PACKAGE=false
  # DEV_SKIP_PACKAGE=true 只在 web/target/.kk-studio-revision 记录的是当前 HEAD 时才复用 JAR
  DEV_SKIP_NPM_INSTALL=false
  DEV_READY_TIMEOUT_SECONDS=90   # seconds to wait for backend/frontend readiness before failing
  DEV_WORK_DIR=$APP_HOME/runtime/dev
EOF
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing command: $1" >&2
    exit 1
  fi
}

resolve_java_home() {
  local java_home=${JAVA_HOME_21:-${JAVA_HOME:-}}
  if [ -z "$java_home" ] || [ ! -x "$java_home/bin/java" ]; then
    echo "JAVA_HOME_21 or JAVA_HOME must point to JDK 21" >&2
    exit 1
  fi
  echo "$java_home"
}

profile_enabled() {
  local profile=$1
  case ",$SPRING_PROFILE," in
    *,"$profile",*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

# Build `env -u` arguments from the live environment so every current and future TEST_*
# input is excluded from long-lived processes without maintaining a provider-name allowlist.
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

# Synchronize complete real-provider credential pairs after backend readiness.
sync_e2e_provider_credentials() {
  require_cmd python3
  step "Syncing provider E2E credentials from env (secrets not printed)"
  env \
    -u TEST_MINIMAX_BASE_URL -u TEST_MINIMAX_API_KEY \
    TEST_GOOGLE_BASE_URL="${TEST_GOOGLE_BASE_URL-}" \
    TEST_GOOGLE_API_KEY="${TEST_GOOGLE_API_KEY-}" \
    TEST_OPENAI_BASE_URL="${TEST_OPENAI_BASE_URL-}" \
    TEST_OPENAI_API_KEY="${TEST_OPENAI_API_KEY-}" \
    TEST_ANTHROPIC_BASE_URL="${TEST_ANTHROPIC_BASE_URL-}" \
    TEST_ANTHROPIC_API_KEY="${TEST_ANTHROPIC_API_KEY-}" \
    TEST_DEEPSEEK_BASE_URL="${TEST_DEEPSEEK_BASE_URL-}" \
    TEST_DEEPSEEK_API_KEY="${TEST_DEEPSEEK_API_KEY-}" \
    python3 "$APP_HOME/scripts/e2e/sync_provider_credentials.py" --backend-url "$BACKEND_URL"
}

stop_pid_file() {
  local pid_file=$1
  local name=$2
  local pid
  pid=$(cat "$pid_file" 2>/dev/null || true)
  if [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1; then
    step "Stopping $name pid $pid"
    kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      if ! kill -0 "$pid" >/dev/null 2>&1; then
        break
      fi
      sleep 0.2
    done
    if kill -0 "$pid" >/dev/null 2>&1; then
      kill -9 -- "-$pid" 2>/dev/null || kill -9 "$pid" 2>/dev/null || true
    fi
  fi
  rm -f "$pid_file"
}

kill_port_listeners() {
  local port=$1
  local pids
  pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
  if [ -z "$pids" ]; then
    return
  fi
  step "Stopping listeners on port $port: $pids"
  kill $pids 2>/dev/null || true
  sleep 0.5
  pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
  if [ -n "$pids" ]; then
    kill -9 $pids 2>/dev/null || true
  fi
}

assert_port_free() {
  local port=$1
  if lsof -iTCP:"$port" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
    echo "Port $port is still in use" >&2
    exit 1
  fi
}

run_detached() {
  local log_file=$1
  shift
  if command -v setsid >/dev/null 2>&1; then
    setsid "$@" >"$log_file" 2>&1 </dev/null &
  else
    nohup "$@" >"$log_file" 2>&1 </dev/null &
  fi
  DETACHED_PID=$!
}

# 只有 Backend 需要数据库/S3/Plugin 数据面配置。构建步骤、npm 与 Vite 不继承这些值；
# `env` 的 argv 也不会携带凭据正文。
set_loaded_dev_env_exported() {
  local exported=$1 key
  if [ -z "$DEV_ENV_FILE" ]; then
    return
  fi
  for key in "${DEV_ENV_REQUIRED_KEYS[@]}" "${DEV_ENV_OPTIONAL_KEYS[@]}"; do
    if [[ ! -v $key ]]; then
      continue
    fi
    if [ "$exported" = "true" ]; then
      export "$key"
    else
      export -n "$key"
    fi
  done
}

run_backend_detached() {
  local log_file=$1
  shift
  set_loaded_dev_env_exported true
  run_detached "$log_file" "$@"
  set_loaded_dev_env_exported false
}

wait_http() {
  local url=$1
  local name=$2
  for _ in $(seq 1 "$READY_TIMEOUT_SECONDS"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "Timed out after ${READY_TIMEOUT_SECONDS}s waiting for $name: $url" >&2
  print_logs all >&2
  exit 1
}

ensure_frontend_deps() {
  if [ "$SKIP_NPM_INSTALL" = "true" ]; then
    return
  fi
  local digest
  digest=$(frontend_package_lock_digest || true)
  if [ ! -d "$FRONTEND_NODE_MODULES" ]; then
    step "Installing frontend dependencies"
    cd "$APP_HOME/frontend"
    npm install
    record_frontend_package_lock_stamp "$digest"
    return
  fi
  if [ -z "$digest" ]; then
    # 没有 package-lock.json 时无法判断依赖是否过期，沿用"已安装即复用"的既有行为。
    return
  fi
  if [ -f "$FRONTEND_PACKAGE_LOCK_STAMP" ] \
    && [ "$(cat "$FRONTEND_PACKAGE_LOCK_STAMP")" = "$digest" ]; then
    step "Reusing frontend dependencies installed for the current package-lock.json"
    return
  fi
  step "package-lock.json changed: reinstalling frontend dependencies with npm ci"
  cd "$APP_HOME/frontend"
  npm ci
  record_frontend_package_lock_stamp "$digest"
}

package_backend() {
  local java_home=$1
  # 只有产物确实由当前修订构建时才允许跳过 Maven；否则会启动与源码不匹配的旧 JAR。
  if [ "$SKIP_PACKAGE" = "true" ] \
    && artifact_current "$BACKEND_JAR" "$BACKEND_JAR_REVISION_STAMP" "$(current_revision)"; then
    step "Skipping backend package: $BACKEND_JAR was built from the current revision"
    return
  fi
  step "Clean packaging backend"
  cd "$APP_HOME"
  env JAVA_HOME="$java_home" mvn -pl web -am -DskipTests clean package
  printf '%s\n' "$(current_revision)" > "$BACKEND_JAR_REVISION_STAMP"
}

stop_all() {
  mkdir -p "$WORK_DIR"
  stop_pid_file "$FRONTEND_PID_FILE" frontend
  stop_pid_file "$BACKEND_PID_FILE" backend
  if [ "$KILL_PORTS" = "true" ]; then
    kill_port_listeners "$FRONTEND_PORT"
    kill_port_listeners "$BACKEND_PORT"
  fi
}

start_all() {
  require_cmd curl
  require_cmd jq
  require_cmd lsof
  require_cmd mvn
  require_cmd npm

  local java_home
  java_home=$(resolve_java_home)

  mkdir -p "$WORK_DIR"
  stop_all
  assert_port_free "$BACKEND_PORT"
  assert_port_free "$FRONTEND_PORT"
  rm -f "$BACKEND_LOG" "$FRONTEND_LOG"

  package_backend "$java_home"
  ensure_frontend_deps

  # Backend 的日志文件按 CWD 解析：前面的步骤可能已经把 CWD 切到 frontend，所以启动
  # 之前显式回到仓库根目录，日志才落在 $APP_HOME/logs。
  cd "$APP_HOME"
  step "Starting backend on $BACKEND_URL"
  read -r -a java_opts <<< "${JAVA_OPTS:-}"
  local -a test_env_unsets=()
  mapfile -d '' -t test_env_unsets < <(test_env_unset_args)
  run_backend_detached "$BACKEND_LOG" env \
    "${test_env_unsets[@]}" \
    "$java_home/bin/java" "${java_opts[@]}" -jar "$BACKEND_JAR" \
    --spring.profiles.active="$SPRING_PROFILE" \
    --server.address="$BACKEND_HOST" \
    --server.port="$BACKEND_PORT"
  echo "$DETACHED_PID" > "$BACKEND_PID_FILE"
  wait_http "$BACKEND_URL/api/ai/catalog/agents?pageNumber=1&pageSize=1" backend

  if profile_enabled e2e; then
    sync_e2e_provider_credentials
  fi

  step "Starting frontend on $FRONTEND_URL"
  cd "$APP_HOME/frontend"
  run_detached "$FRONTEND_LOG" env \
    "${test_env_unsets[@]}" \
    API_PROXY_TARGET="$BACKEND_URL" npm run dev -- \
    --host "$FRONTEND_HOST" \
    --port "$FRONTEND_PORT"
  echo "$DETACHED_PID" > "$FRONTEND_PID_FILE"
  wait_http "$FRONTEND_URL/threads" frontend

  echo "Dev environment is running"
  echo "Backend:  $BACKEND_URL"
  echo "Frontend: $FRONTEND_URL"
  echo "Profile:  $SPRING_PROFILE"
  echo "Logs:     $WORK_DIR"
}

status_one() {
  local name=$1
  local pid_file=$2
  local url=$3
  local pid
  pid=$(cat "$pid_file" 2>/dev/null || true)
  if [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1; then
    echo "$name: running pid=$pid url=$url"
  else
    echo "$name: stopped url=$url"
  fi
}

status_all() {
  status_one backend "$BACKEND_PID_FILE" "$BACKEND_URL"
  status_one frontend "$FRONTEND_PID_FILE" "$FRONTEND_URL"
}

print_logs() {
  local target=${1:-all}
  case "$target" in
    backend)
      echo "==== backend.log ===="
      tail -n "$LOG_LINES" "$BACKEND_LOG" 2>/dev/null || true
      ;;
    frontend)
      echo "==== frontend.log ===="
      tail -n "$LOG_LINES" "$FRONTEND_LOG" 2>/dev/null || true
      ;;
    all)
      print_logs backend
      print_logs frontend
      ;;
    *)
      echo "Unknown log target: $target" >&2
      exit 1
      ;;
  esac
}

tail_logs() {
  local target=${1:-all}
  case "$target" in
    backend)
      tail -n "$LOG_LINES" -f "$BACKEND_LOG"
      ;;
    frontend)
      tail -n "$LOG_LINES" -f "$FRONTEND_LOG"
      ;;
    all)
      touch "$BACKEND_LOG" "$FRONTEND_LOG"
      tail -n "$LOG_LINES" -f "$BACKEND_LOG" "$FRONTEND_LOG"
      ;;
    *)
      echo "Unknown log target: $target" >&2
      exit 1
      ;;
  esac
}

cmd=${1:-}
target=${2:-all}

# 同一个文件既作为 lifecycle 命令直接执行，也被契约测试 `source` 后逐函数行为化验证；
# 只有直接执行时才派发子命令。
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  case "$cmd" in
    start)
      start_all
      ;;
    stop)
      stop_all
      ;;
    restart)
      stop_all
      start_all
      ;;
    status)
      status_all
      ;;
    logs)
      print_logs "$target"
      ;;
    tail)
      tail_logs "$target"
      ;;
    *)
      usage
      exit 1
      ;;
  esac
fi
