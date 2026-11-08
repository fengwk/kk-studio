#!/usr/bin/env bash
# scripts/dev/verify/e2e/lib/daemon-bootstrap.sh
#
# 在隔离的 DAEMON_ROOT 下物化唯一 daemon.json 本地缓存与同目录 daemon.token，然后以
# `--config <绝对路径>` 启动 Node Daemon 编译入口 `$DAEMON_DIST/main.js`。
#
# daemon.json 固定为 {environmentId,version:string,daemonConfig:{studioUrl,bashExecutable,lsp,proxyUrl}}：
# 卡片由 DAEMON_ENVIRONMENT_ID 或 DAEMON_ENVIRONMENT_NAME 定位，Studio 数据库是配置权威；若卡片
# studioUrl 不是本测试进程可达的 DAEMON_STUDIO_URL，则用当前 CAS version 通过卡片 API 修正后写 cache。
# 卡片备注不进入本地 cache。注册凭证只经内建 printf 写入 owner-only 文件，绝不进入 JSON、
# argv 或落地后的 daemon 环境。数据目录即配置文件父目录。
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
config_helper="$script_dir/../../../../../deploy/reliability/daemon-config-cache.mjs"

root=${DAEMON_ROOT:?DAEMON_ROOT (absolute daemon root) is required}
studio_url=${DAEMON_STUDIO_URL:?DAEMON_STUDIO_URL (http(s) origin) is required}
environment_id=${DAEMON_ENVIRONMENT_ID:-}
environment_name=${DAEMON_ENVIRONMENT_NAME:-}
dist=${DAEMON_DIST:?DAEMON_DIST (absolute daemon distribution directory) is required}
node_bin=${DAEMON_NODE:-}

# 凭证先取出再立即从环境移除；daemon 只通过同目录文件读取。
token=${DAEMON_REGISTRATION_TOKEN:-}
unset DAEMON_REGISTRATION_TOKEN KK_STUDIO_DAEMON_REGISTRATION_TOKEN

if [ -z "$token" ]; then
  echo "ERROR: DAEMON_REGISTRATION_TOKEN is required to register the daemon." >&2
  exit 1
fi
if [ -z "$environment_id" ] && [ -z "$environment_name" ]; then
  echo "ERROR: DAEMON_ENVIRONMENT_ID or DAEMON_ENVIRONMENT_NAME is required." >&2
  exit 1
fi
case "$root" in
  /*) ;;
  *)
    echo "ERROR: DAEMON_ROOT must be absolute: $root" >&2
    exit 1
    ;;
esac
if [ -z "$node_bin" ]; then
  node_bin=$(command -v node) || {
    echo "ERROR: DAEMON_NODE is unset and no node is on PATH." >&2
    exit 1
  }
fi
entry="$dist/main.js"
if [ ! -f "$entry" ]; then
  echo "ERROR: daemon entry is missing: $entry (run: npm --prefix daemon run build)." >&2
  exit 1
fi
if [ ! -f "$config_helper" ]; then
  echo "ERROR: daemon configuration helper is missing: $config_helper" >&2
  exit 1
fi

(umask 077 && mkdir -p "$root")
chmod 700 "$root"
config_file="$root/daemon.json"
token_file="$root/daemon.token"

(umask 077 && printf '%s\n' "$token" >"$token_file")
chmod 600 "$token_file"
unset token

# 卡片 API 与配置序列化都交给唯一的 Node helper：值不参与 shell 拼接，错误信息不包含 token。
# umask 与显式 mode 保证配置文件创建时即 owner-only，临时文件同目录 rename 后原子替换。
selector_args=(--config "$config_file" --studio-url "$studio_url")
if [ -n "$environment_id" ]; then
  selector_args+=(--environment-id "$environment_id")
else
  selector_args+=(--environment-name "$environment_name")
fi
(umask 077 && "$node_bin" "$config_helper" "${selector_args[@]}")
chmod 600 "$config_file"
unset environment_id environment_name studio_url
unset selector_args

exec "$node_bin" "$entry" --config "$config_file"
