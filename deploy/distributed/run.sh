#!/usr/bin/env bash
# deploy/distributed/run.sh — 双节点分布式测试栈单一生命周期入口
#
# 子命令：
#   up          构建镜像（可跳过）、启动完整栈并等待 health/READY
#   status      打印容器、两个 App health 与 Environment 状态
#   logs        打印容器日志（默认全部服务，可用参数过滤）
#   disconnect-db-a|reconnect-db-a
#               对 app-a 的 node-a-db 网络做幂等 disconnect/reconnect 故障注入；
#               daemon-a 网络与 workspace 不受影响
#   verify      静态验证 Compose config 与网络不变量（不启动容器）
#   down        停止并删除容器与网络；--volumes 连数据卷一起删除
#
# 该栈不读取宿主 MiniMax 凭据；真实 Provider 仍只能经 scripts/e2e.sh
# 显式 --real 同步。全部凭据是固定、可丢弃的测试值。

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
COMPOSE_FILE="$SCRIPT_DIR/compose.yaml"
COMPOSE=(docker compose -f "$COMPOSE_FILE")

DISTRIBUTED_APP_A_PORT=${DISTRIBUTED_APP_A_PORT:-18082}
DISTRIBUTED_APP_B_PORT=${DISTRIBUTED_APP_B_PORT:-18083}
APP_A_URL="http://127.0.0.1:$DISTRIBUTED_APP_A_PORT"
APP_B_URL="http://127.0.0.1:$DISTRIBUTED_APP_B_PORT"
DISTRIBUTED_ENV_A_NAME=${DISTRIBUTED_ENV_A_NAME:-distributed-a}
DISTRIBUTED_ENV_B_NAME=${DISTRIBUTED_ENV_B_NAME:-distributed-b}
export DISTRIBUTED_APP_A_PORT DISTRIBUTED_APP_B_PORT DISTRIBUTED_ENV_A_NAME DISTRIBUTED_ENV_B_NAME

SERVICES=(postgres minio minio-init http-mock app-a app-b workspace-init daemon-a daemon-b)
NODE_A_DB_NETWORK=kk-studio-distributed_node-a-db
APP_A_CONTAINER=kk-studio-distributed-app-a-1

usage() {
  cat <<'EOF'
Usage: deploy/distributed/run.sh <command> [arguments]

Commands:
  up [--skip-build]      Build images unless --skip-build, start the stack and
                         wait for dependency health, both app healthchecks and
                         both daemon READY projections.
  status                 Print container state, both app health endpoints and
                         the public Environment projections.
  logs [services...]     Print the last DISTRIBUTED_LOG_TAIL lines (default 200).
                         Without arguments all services are included.
  disconnect-db-a        Idempotently disconnect app-a from the shared node-a-db
                         network (DB fault injection). daemon-a is untouched.
  reconnect-db-a         Idempotently reconnect app-a to node-a-db.
  verify                 Validate Compose config and network invariants without
                         starting any container.
  down [--volumes]       Remove containers and networks; add --volumes to drop
                         the PostgreSQL/MinIO/daemon workspace volumes.
  help                   Show this help.
EOF
}

step() {
  printf '==> %s\n' "$*"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_docker() {
  command -v docker >/dev/null 2>&1 || die "docker is required"
  docker info >/dev/null 2>&1 || die "Docker daemon is not available"
  docker compose version >/dev/null 2>&1 || die "docker compose is required"
}

build_images() {
  step "Building app image"
  docker build \
    --file "$REPO_ROOT/deploy/local/Dockerfile" \
    --tag "${DISTRIBUTED_APP_IMAGE:-kk-studio-app:distributed}" \
    --build-arg "KK_STUDIO_BUILD_HTTP_PROXY=${KK_STUDIO_BUILD_HTTP_PROXY:-}" \
    --build-arg "KK_STUDIO_BUILD_HTTPS_PROXY=${KK_STUDIO_BUILD_HTTPS_PROXY:-}" \
    --build-arg "KK_STUDIO_BUILD_NO_PROXY=${KK_STUDIO_BUILD_NO_PROXY:-}" \
    --build-arg "KK_STUDIO_MAVEN_BUILD_OPTS=${KK_STUDIO_MAVEN_BUILD_OPTS:-}" \
    "$REPO_ROOT"
  step "Building daemon image"
  docker build \
    --file "$REPO_ROOT/deploy/reliability/daemon.Dockerfile" \
    --tag "${DISTRIBUTED_DAEMON_IMAGE:-kk-studio-daemon:distributed}" \
    "$REPO_ROOT"
}

wait_http() {
  local url=$1
  local name=$2
  local attempt
  for attempt in $(seq 1 "${DISTRIBUTED_WAIT_ATTEMPTS:-120}"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  die "timed out waiting for $name: $url"
}

environment_status() {
  local app_url=$1
  local env_name=$2
  curl -fsS "$app_url/api/ai/environments" 2>/dev/null | python3 -c '
import json
import sys

name = sys.argv[1]
rows = json.load(sys.stdin).get("data") or []
match = next((row for row in rows if row.get("name") == name), None)
print(match.get("status") if match else "MISSING")
' "$env_name"
}

wait_environment_ready() {
  local app_url=$1
  local env_name=$2
  local node_label=$3
  local status=""
  local attempt
  for attempt in $(seq 1 "${DISTRIBUTED_WAIT_ATTEMPTS:-120}"); do
    status=$(environment_status "$app_url" "$env_name")
    if [ "$status" = "READY" ]; then
      step "node $node_label environment $env_name READY"
      return 0
    fi
    sleep 1
  done
  die "node $node_label environment $env_name not READY (last status='$status')"
}

cmd_up() {
  local skip_build=false
  if [ "${1:-}" = "--skip-build" ]; then
    skip_build=true
  elif [ -n "${1:-}" ]; then
    usage >&2
    die "unknown up argument: $1"
  fi
  require_docker
  step "Validating Compose configuration"
  "${COMPOSE[@]}" config --quiet

  if [ "$skip_build" = "false" ]; then
    build_images
  else
    step "Skipping image build (--skip-build)"
  fi

  step "Starting distributed stack"
  "${COMPOSE[@]}" up -d --wait --no-build
  wait_http "$APP_A_URL/actuator/health" "app-a health"
  wait_http "$APP_B_URL/actuator/health" "app-b health"
  wait_environment_ready "$APP_A_URL" "$DISTRIBUTED_ENV_A_NAME" a
  wait_environment_ready "$APP_B_URL" "$DISTRIBUTED_ENV_B_NAME" b
  step "Distributed stack is up"
  step "app-a: $APP_A_URL  app-b: $APP_B_URL"
}

cmd_status() {
  require_docker
  "${COMPOSE[@]}" ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}' || true
  local label url env_name status
  for label in a b; do
    if [ "$label" = "a" ]; then
      url=$APP_A_URL
      env_name=$DISTRIBUTED_ENV_A_NAME
    else
      url=$APP_B_URL
      env_name=$DISTRIBUTED_ENV_B_NAME
    fi
    if curl -fsS "$url/actuator/health" >/dev/null 2>&1; then
      status=$(environment_status "$url" "$env_name")
      printf '%s health=UP environment=%s=%s\n' "$url" "$env_name" "$status"
    else
      printf '%s health=DOWN\n' "$url"
    fi
  done
}

cmd_logs() {
  require_docker
  local tail=${DISTRIBUTED_LOG_TAIL:-200}
  if [ "$#" -gt 0 ]; then
    "${COMPOSE[@]}" logs --tail "$tail" "$@"
  else
    "${COMPOSE[@]}" logs --tail "$tail" "${SERVICES[@]}"
  fi
}

# 幂等 DB 故障注入：只把 app-a 容器从 node-a-db 网络断开/接回。
# daemon-a 网络和 daemon workspace 不被触碰；app-a 继续通过 app-ingress-a 暴露。
app_a_db_disconnect_state() {
  docker inspect -f '{{range $net, $conf := .NetworkSettings.Networks}}{{$net}} {{end}}' \
    "$APP_A_CONTAINER" 2>/dev/null | tr ' ' '\n' | grep -Fx "$NODE_A_DB_NETWORK" || true
}

cmd_disconnect_db_a() {
  require_docker
  if [ -n "$(app_a_db_disconnect_state)" ]; then
    step "Disconnecting $APP_A_CONTAINER from $NODE_A_DB_NETWORK"
    docker network disconnect "$NODE_A_DB_NETWORK" "$APP_A_CONTAINER"
  else
    step "$APP_A_CONTAINER is already disconnected from $NODE_A_DB_NETWORK"
  fi
}

cmd_reconnect_db_a() {
  require_docker
  if [ -z "$(app_a_db_disconnect_state)" ]; then
    step "Reconnecting $APP_A_CONTAINER to $NODE_A_DB_NETWORK"
    docker network connect "$NODE_A_DB_NETWORK" "$APP_A_CONTAINER"
  else
    step "$APP_A_CONTAINER is already connected to $NODE_A_DB_NETWORK"
  fi
}

cmd_down() {
  require_docker
  local -a volumes=()
  if [ "${1:-}" = "--volumes" ]; then
    volumes=(--volumes)
  elif [ -n "${1:-}" ]; then
    usage >&2
    die "unknown down argument: $1"
  fi
  step "Removing distributed stack"
  "${COMPOSE[@]}" down --remove-orphans "${volumes[@]}"
}

cmd_verify() {
  require_docker
  step "Validating Compose configuration"
  "${COMPOSE[@]}" config --quiet
  step "Validating network invariants"
  python3 "$REPO_ROOT/scripts/e2e/tests/distributed_topology.py" --compose-file "$COMPOSE_FILE"
  step "Compose config and topology invariants verified"
}

case "${1:-help}" in
  up)
    shift
    cmd_up "$@"
    ;;
  status)
    cmd_status
    ;;
  logs)
    shift
    cmd_logs "$@"
    ;;
  disconnect-db-a)
    cmd_disconnect_db_a
    ;;
  reconnect-db-a)
    cmd_reconnect_db_a
    ;;
  verify)
    cmd_verify
    ;;
  down)
    shift
    cmd_down "$@"
    ;;
  help | -h | --help)
    usage
    ;;
  *)
    usage >&2
    die "unknown command: $1"
    ;;
esac
