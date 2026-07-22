#!/usr/bin/env bash
# scripts/e2e/lib.sh
#
# 共享函数库：e2e 入口与各 case 共用。
# 约定：
# - 默认 backend=127.0.0.1:18081、frontend=127.0.0.1:5173、profile=e2e
# - H2 是内存库，重启 backend 后必须重新注入 Provider credential
# - frontend Vite 代理必须指向当前 backend：API_PROXY_TARGET=http://$BACKEND_HOST:$BACKEND_PORT
# - daemon 不是 fat jar，必须用 -cp（daemon jar + runtime classpath）启动 DaemonMain

set -euo pipefail

E2E_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$E2E_ROOT/../.." && pwd)
WORK_DIR=${E2E_WORK_DIR:-"$REPO_ROOT/target/e2e"}

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
DAEMON_CP_FILE=${DAEMON_CP_FILE:-"$WORK_DIR/daemon.classpath"}

step() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

resolve_java_home() {
  local java_home=${JAVA_HOME_17:-${JAVA_HOME:-}}
  if [ -z "$java_home" ] || [ ! -x "$java_home/bin/java" ]; then
    die "JAVA_HOME_17 or JAVA_HOME must point to JDK 17"
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
  step "Packaging backend (Java 17, offline, skipTests)"
  (
    cd "$REPO_ROOT"
    env JAVA_HOME="$java_home" mvn -o -pl web -am -DskipTests package
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
  nohup env JAVA_HOME="$java_home" "$java_home/bin/java" -jar "$BACKEND_JAR" \
    --spring.profiles.active="$SPRING_PROFILE" \
    --server.address="$BACKEND_HOST" \
    --server.port="$BACKEND_PORT" \
    >"$WORK_DIR/backend.log" 2>&1 &
  echo $! >"$WORK_DIR/backend.pid"
  wait_http "$BACKEND_URL/api/agents?pageNumber=1&pageSize=1" backend
}

# Write only env-provided baseUrl/credential into seeded providers. Missing env => leave DB null/unchanged.
sync_e2e_provider_credentials() {
  require_cmd python3
  step "Syncing e2e provider credentials from env (only set fields; secrets not printed)"
  BACKEND_URL="$BACKEND_URL" \
  MINIMAX_API_KEY="${MINIMAX_API_KEY-}" MINIMAX_BASE_URL="${MINIMAX_BASE_URL-}" \
  OPENAI_API_KEY="${OPENAI_API_KEY-}" OPENAI_BASE_URL="${OPENAI_BASE_URL-}" \
  XAI_API_KEY="${XAI_API_KEY-}" XAI_BASE_URL="${XAI_BASE_URL-}" \
  DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY-}" DEEPSEEK_BASE_URL="${DEEPSEEK_BASE_URL-}" \
  GEMINI_API_KEY="${GEMINI_API_KEY-}" GOOGLE_BASE_URL="${GOOGLE_BASE_URL-}" \
  python3 - <<'PY'
import json, os, urllib.request

BACKEND = os.environ["BACKEND_URL"].rstrip("/")
PROVIDERS = [
    (1, "minimax", "MiniMax (OpenAI Responses).", "openai_response", "MINIMAX_BASE_URL", "MINIMAX_API_KEY"),
    (2, "openai", "OpenAI (OpenAI Responses).", "openai_response", "OPENAI_BASE_URL", "OPENAI_API_KEY"),
    (3, "xai", "xAI / Grok (OpenAI Responses).", "openai_response", "XAI_BASE_URL", "XAI_API_KEY"),
    (4, "deepseek", "DeepSeek (OpenAI Chat Completions).", "openai", "DEEPSEEK_BASE_URL", "DEEPSEEK_API_KEY"),
    (5, "google", "Google Gemini.", "google", "GOOGLE_BASE_URL", "GEMINI_API_KEY"),
]

def get(url):
    with urllib.request.urlopen(url, timeout=30) as resp:
        return json.load(resp)

def put(url, payload):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="PUT",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)

def normalize_openai_compatible_base_url(base, provider_type):
    """When env already sets a base URL, ensure OpenAI-compatible hosts end with /v1.
    Never invent a default host; google and blank values are left unchanged.
    """
    if not base:
        return base
    if provider_type not in ("openai", "openai_response"):
        return base
    cleaned = base.rstrip("/")
    if cleaned.endswith("/v1"):
        return cleaned
    return cleaned + "/v1"

listed = get(f"{BACKEND}/api/providers?pageNumber=1&pageSize=50")
rows = ((listed.get("data") or {}).get("results") or [])
by_id = {str(r.get("id")): r for r in rows}

for provider_id, name, description, provider_type, base_env, key_env in PROVIDERS:
    base = (os.environ.get(base_env) or "").strip() or None
    key = (os.environ.get(key_env) or "").strip() or None
    if base is None and key is None:
        print(f"provider {name}: skip (no {base_env}/{key_env})")
        continue
    if base is not None:
        normalized = normalize_openai_compatible_base_url(base, provider_type)
        if normalized != base:
            print(f"provider {name}: normalize baseUrl {base} -> {normalized}")
        base = normalized
    current = by_id.get(str(provider_id)) or {}
    payload = {
        "name": name,
        "description": description,
        "providerType": provider_type,
        "baseUrl": base if base is not None else current.get("baseUrl"),
        "modelCallTimeoutMillis": int(current.get("modelCallTimeoutMillis") or 1800000),
        "modelCallIdleTimeoutMillis": int(current.get("modelCallIdleTimeoutMillis") or 120000),
    }
    if key is not None:
        payload["credential"] = key
    body = put(f"{BACKEND}/api/providers/{provider_id}", payload)
    data = body.get("data") or {}
    print(f"provider {name}: configured={data.get('configured')} baseUrl_set={bool(data.get('baseUrl'))}")
PY
}

# Backward-compatible alias for older callers.
sync_minimax_provider() {
  sync_e2e_provider_credentials
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
  curl -fsS "$FRONTEND_URL/api/models?pageNumber=1&pageSize=1" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); m=((d.get("data") or {}).get("results") or [None])[0];
assert m and isinstance(m.get("config"), dict) and m["config"].get("defaultVariant"), m; print("frontend proxy model.config.defaultVariant=", m["config"]["defaultVariant"])'
}

start_daemon() {
  local java_home=$1
  mkdir -p "$WORK_DIR" "$DAEMON_ENV_ROOT"
  kill_daemon
  build_daemon_classpath "$java_home"
  : >"$WORK_DIR/daemon.log"
  local cp
  cp="$DAEMON_JAR:$(cat "$DAEMON_CP_FILE")"
  step "Starting daemon env=$DAEMON_ENV_NAME"
  nohup env JAVA_HOME="$java_home" "$java_home/bin/java" \
    -Dkkstudio.daemon.environment-root="$DAEMON_ENV_ROOT" \
    -Dkkstudio.daemon.default-workdir="$DAEMON_ENV_ROOT" \
    -cp "$cp" fun.fengwk.kkstudio.harness.daemon.DaemonMain \
    --environment-name "$DAEMON_ENV_NAME" \
    --gateway-uri "ws://$BACKEND_HOST:$BACKEND_PORT/api/environments/daemon/v1" \
    --gateway-token "$DAEMON_TOKEN" \
    --daemon-id "$DAEMON_ID" \
    --skill-dir "$SKILL_DIR" \
    >"$WORK_DIR/daemon.log" 2>&1 &
  echo $! >"$WORK_DIR/daemon.pid"
  local i env_status=""
  for i in $(seq 1 60); do
    env_status=$(curl -fsS "$BACKEND_URL/api/environments" \
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

  if [ "$rebuild" = "true" ] || [ ! -f "$BACKEND_JAR" ]; then
    package_backend "$java_home"
  fi

  if ! curl -fsS "$BACKEND_URL/api/agents?pageNumber=1&pageSize=1" >/dev/null 2>&1; then
    start_backend "$java_home"
    sync_minimax_provider
  else
    step "Reusing backend $BACKEND_URL"
    # 若模型契约仍是旧 configJson，则强制重建
    if ! curl -fsS "$BACKEND_URL/api/models?pageNumber=1&pageSize=1" \
      | python3 -c 'import sys,json; d=json.load(sys.stdin); m=((d.get("data") or {}).get("results") or [None])[0];
raise SystemExit(0 if m and isinstance(m.get("config"), dict) else 1)' 2>/dev/null; then
      step "Backend contract stale (missing model.config); rebuilding and restarting"
      package_backend "$java_home"
      start_backend "$java_home"
      sync_minimax_provider
    fi
  fi

  if ! curl -fsS "$FRONTEND_URL/" >/dev/null 2>&1; then
    start_frontend
  else
    step "Reusing frontend $FRONTEND_URL"
    if ! curl -fsS "$FRONTEND_URL/api/models?pageNumber=1&pageSize=1" \
      | python3 -c 'import sys,json; d=json.load(sys.stdin); m=((d.get("data") or {}).get("results") or [None])[0];
raise SystemExit(0 if m and isinstance(m.get("config"), dict) else 1)' 2>/dev/null; then
      step "Frontend proxy contract stale; restarting frontend with API_PROXY_TARGET"
      start_frontend
    fi
  fi

  if [ "$with_daemon" = "true" ]; then
    env_status=$(curl -fsS "$BACKEND_URL/api/environments" \
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
