#!/usr/bin/env bash
#
# scripts/local-dev.sh
#
# 笔记本上的一条命令开发入口：用 NAS 上已有的 PostgreSQL/S3 数据面启动打包好的 Backend 与
# Vite/HMR preview。它只强制「本机不是 Flyway owner，也不是 Harness worker」，其余监听地址、
# 端口、日志目录与子命令都交给 scripts/dev.sh。
#
# 配置：默认读取 $HOME/.config/kk-studio/local-dev.env，只允许用 DEV_ENV_FILE 指向别的路径。
# 该文件必须是 owner-only（无 group/other 权限位）的普通非符号链接文件，内容为占位符模板
# scripts/local-dev.config.example 列出的数据面 KEY=VALUE；解析规则见 scripts/dev.sh。

set -euo pipefail

SCRIPT_HOME=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

DEV_ENV_FILE=${DEV_ENV_FILE:-}
if [ -z "$DEV_ENV_FILE" ]; then
  if [ -z "${HOME:-}" ]; then
    echo "ERROR: HOME must be set to resolve the default DEV_ENV_FILE" >&2
    exit 1
  fi
  DEV_ENV_FILE="$HOME/.config/kk-studio/local-dev.env"
fi

usage() {
  cat <<EOF
Usage: $0 {start|stop|restart|status|logs|tail}

与 scripts/dev.sh 相同的子命令，额外固定本机 preview 的执行归属：
  SPRING_PROFILES_ACTIVE=prod
  SPRING_FLYWAY_ENABLED=false
  KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false

Environment:
  DEV_ENV_FILE=/absolute/path/local-dev.env   # 默认 \$HOME/.config/kk-studio/local-dev.env
  BACKEND_PORT / FRONTEND_PORT / BACKEND_HOST / FRONTEND_HOST / DEV_WORK_DIR / JAVA_OPTS ...
    # 未显式设置时由 scripts/dev.sh 决定（Backend 127.0.0.1:18080、Vite 127.0.0.1:5173）
EOF
}

case "${1:-}" in
  '' | -h | --help)
    usage
    if [ -z "${1:-}" ]; then
      exit 1
    fi
    exit 0
    ;;
esac

# 本机只做同步 preview：Flyway 与异步 Harness Work 都必须留在 NAS 的 App 进程里。
export DEV_ENV_FILE
export KK_STUDIO_LOCAL_DEV_EXTERNAL_SERVICES=true
export SPRING_PROFILES_ACTIVE=prod
export SPRING_FLYWAY_ENABLED=false
export KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false

exec "$SCRIPT_HOME/dev.sh" "$@"
