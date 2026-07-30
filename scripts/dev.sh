#!/usr/bin/env bash
set -euo pipefail

SCRIPT_HOME=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
APP_HOME=$(cd "$SCRIPT_HOME/.." && pwd)
WORK_DIR=${DEV_WORK_DIR:-"$APP_HOME/target/dev"}

BACKEND_HOST=${BACKEND_HOST:-127.0.0.1}
BACKEND_PORT=${BACKEND_PORT:-18080}
FRONTEND_HOST=${FRONTEND_HOST:-127.0.0.1}
FRONTEND_PORT=${FRONTEND_PORT:-5173}
SPRING_PROFILE=${SPRING_PROFILES_ACTIVE:-e2e}

BACKEND_URL="http://$BACKEND_HOST:$BACKEND_PORT"
FRONTEND_URL="http://$FRONTEND_HOST:$FRONTEND_PORT"
BACKEND_LOG="$WORK_DIR/backend.log"
FRONTEND_LOG="$WORK_DIR/frontend.log"
BACKEND_PID_FILE="$WORK_DIR/backend.pid"
FRONTEND_PID_FILE="$WORK_DIR/frontend.pid"
BACKEND_JAR="$APP_HOME/web/target/kk-studio-web-1.0.0.jar"

KILL_PORTS=${DEV_KILL_PORTS:-true}
SKIP_PACKAGE=${DEV_SKIP_PACKAGE:-false}
SKIP_NPM_INSTALL=${DEV_SKIP_NPM_INSTALL:-false}
LOG_LINES=${LOG_LINES:-120}

step() {
  echo "==> $1"
}

usage() {
  cat <<EOF
Usage: $0 {start|stop|restart|status|logs|tail}

Commands:
  start    Package backend, optionally sync the MiniMax E2E credential pair, then start backend and frontend.
  stop     Stop managed dev servers and, by default, listeners on dev ports.
  restart  Stop then start.
  status   Print process status and URLs.
  logs     Print recent logs. Optional target: backend, frontend, all.
  tail     Follow logs. Optional target: backend, frontend, all.

Environment:
  BACKEND_PORT=18080
  FRONTEND_PORT=5173
  SPRING_PROFILES_ACTIVE=e2e   # dev/e2e 均使用 PostgreSQL；dev=stub seed，e2e=real provider seed
  TEST_MINIMAX_API_KEY / TEST_MINIMAX_BASE_URL
  # e2e profile: the complete MiniMax pair is written to seed provider id=1 after backend readiness
  # the base URL is normalized to end with /v1
  DEV_KILL_PORTS=true
  DEV_SKIP_PACKAGE=false
  DEV_SKIP_NPM_INSTALL=false
  DEV_WORK_DIR=$APP_HOME/target/dev
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

# Synchronize the complete MiniMax credential pair into the seeded provider after backend readiness.
sync_e2e_provider_credentials() {
  require_cmd python3
  step "Syncing MiniMax E2E credentials from env (secrets not printed)"
  BACKEND_URL="$BACKEND_URL" \
  TEST_MINIMAX_API_KEY="${TEST_MINIMAX_API_KEY-}" TEST_MINIMAX_BASE_URL="${TEST_MINIMAX_BASE_URL-}" \
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

wait_http() {
  local url=$1
  local name=$2
  for _ in $(seq 1 90); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "Timed out waiting for $name: $url" >&2
  print_logs all >&2
  exit 1
}

ensure_frontend_deps() {
  if [ "$SKIP_NPM_INSTALL" = "true" ] || [ -d "$APP_HOME/frontend/node_modules" ]; then
    return
  fi
  step "Installing frontend dependencies"
  cd "$APP_HOME/frontend"
  npm install
}

package_backend() {
  local java_home=$1
  if [ "$SKIP_PACKAGE" = "true" ] && [ -f "$BACKEND_JAR" ]; then
    step "Skipping backend package"
    return
  fi
  step "Packaging backend"
  cd "$APP_HOME"
  env JAVA_HOME="$java_home" mvn -pl web -am -DskipTests package
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

  step "Starting backend on $BACKEND_URL"
  read -r -a java_opts <<< "${JAVA_OPTS:-}"
  run_detached "$BACKEND_LOG" "$java_home/bin/java" "${java_opts[@]}" -jar "$BACKEND_JAR" \
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
  run_detached "$FRONTEND_LOG" env API_PROXY_TARGET="$BACKEND_URL" npm run dev -- \
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
