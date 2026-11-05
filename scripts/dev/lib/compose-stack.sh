#!/usr/bin/env bash
# scripts/dev/lib/compose-stack.sh — 隔离 Compose 栈的共享生命周期骨架。
#
# 调用约定：
# - 调用方定义 `COMPOSE=(docker compose ...)` 完整调用前缀与 `die()`；本库只复用
#   `COMPOSE`，不重新拼装项目名、profile 或 compose 文件。
# - 本库只收敛各栈逐字相同的启动前检查、健康等待、失败诊断与 down。各栈的构建
#   策略、业务检查和销毁时机仍留在自己的入口：
#   * smoke/performance 的 canvas-test 栈每次运行前后都删卷；不带 app 的 smoke
#     只等待常驻依赖并单独执行一次性 minio-init，因此不使用 stack_up_wait；
#   * reliability 是长期手工栈，用 `compose up --build` 构建 app/daemon，只在显式
#     `down` 时销毁，并在 up 前检查展开后的 config 不含凭据或 bind mount；
#   * distributed 不自动销毁（E2E 入口持有 EXIT trap），并在 up/verify 中额外校验
#     双节点网络不变量。

_KK_STUDIO_COMPOSE_LIB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=http-wait.sh
source "$_KK_STUDIO_COMPOSE_LIB_DIR/http-wait.sh"
unset _KK_STUDIO_COMPOSE_LIB_DIR

# Docker CLI、daemon 与 Compose v2 的公共前置检查。
stack_require_docker() {
  command -v docker >/dev/null 2>&1 || die "docker is required"
  docker info >/dev/null 2>&1 || die "Docker daemon is not available"
  docker compose version >/dev/null 2>&1 || die "docker compose is required"
}

# 校验 compose 配置；额外的全局参数（如 --profile app）在子命令前透传。
stack_config_quiet() {
  "${COMPOSE[@]}" "$@" config --quiet
}

# 启动不复用、由 Compose 等待 health 的常驻服务。
stack_up_wait() {
  "${COMPOSE[@]}" "$@" up -d --wait --no-build
}

# 轮询 HTTP 端点直到就绪；调用方的 die 负责带上入口上下文。
stack_wait_http() {
  local url=$1
  local name=$2
  local attempts=${3:-90}
  poll_http "$url" "$attempts" || die "timed out waiting for $name: $url"
}

# 轮询 Environment 就绪：probe 提供 environment-ready 退出码与
# environment-status 文本；后者只在超时诊断时使用。
stack_wait_environment() {
  local app_url=$1
  local env_name=$2
  local probe=$3
  local label=$4
  local attempts=${5:-120}
  local interval=${6:-1}
  local attempt status=""

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if curl -fsS "$app_url/api/environments" 2>/dev/null \
      | node "$probe" environment-ready "$env_name" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$interval"
  done

  status=$(curl -fsS "$app_url/api/environments" 2>/dev/null \
    | node "$probe" environment-status "$env_name" 2>/dev/null || true)
  die "timed out waiting for $label (last status='${status:-unknown}')"
}

# 失败时先保留 ps/logs 证据；额外的 compose 全局参数同样透传。
stack_diagnose() {
  "${COMPOSE[@]}" "$@" ps -a >&2 || true
  "${COMPOSE[@]}" "$@" logs --no-color >&2 || true
}

# 统一 down 参数；remove_volumes=true 时同时删除项目卷。
stack_down() {
  local remove_volumes=$1
  shift
  if [ "$remove_volumes" = true ]; then
    "${COMPOSE[@]}" "$@" down --volumes --remove-orphans
  else
    "${COMPOSE[@]}" "$@" down --remove-orphans
  fi
}

# smoke/performance 的 EXIT trap 骨架：失败诊断后删除一次性栈。down 的失败必须
# 被吞掉，否则会覆盖原命令的退出状态。
cleanup_compose_stack() {
  local status=$1
  local remove_volumes=$2
  shift 2
  if [ "$status" -ne 0 ]; then
    stack_diagnose "$@"
  fi
  stack_down "$remove_volumes" "$@" >/dev/null 2>&1 || true
}
