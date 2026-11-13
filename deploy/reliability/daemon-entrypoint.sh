#!/bin/bash
# kk-studio reliability/distributed Go Daemon container entrypoint.
#
# The Go daemon executable is at the distribution root. A bundled Node runtime remains only
# for the code-server IDE and the small configuration-cache helper.
#
# Container config serialization uses that bundled Node because jq/python are absent. The token
# is written with shell builtins to an owner-only file:
# 凭证文本既不出现在 daemon argv（`ps` 可见），也不留在环境变量中（`/proc/<pid>/environ`
# 可见）。所有文件与目录权限显式收敛为 0600/0700，不依赖镜像 umask。
#
# daemon.json 固定为 {environmentId,version:string,daemonConfig}；Daemon 按卡片 UUID/name
# 从 Studio 读取权威配置，必要时修正卡片 studioUrl 后写缓存。配置文件父目录即 Daemon 运行
# 数据目录；根默认在唯一的 /workspace named volume 下，因此不引入额外挂载。daemon 只接收
# 唯一的 `--config <绝对路径>`。
#
# `KK_STUDIO_DAEMON_DIST` defaults to /opt/kk-studio/daemon and points to the Go distribution root.
set -euo pipefail

# 显式 PATH：入口自身、node/npm 包装与能力子进程都解析到同一份冻结运行时。
export PATH=/usr/local/bin:/usr/bin:/bin

dist="${KK_STUDIO_DAEMON_DIST:-/opt/kk-studio/daemon}"
root="${KK_STUDIO_DAEMON_ROOT:-/workspace/.kk-studio}"
studio_url="${KK_STUDIO_DAEMON_STUDIO_URL:-}"
environment_id="${KK_STUDIO_DAEMON_ENVIRONMENT_ID:-}"
environment_name="${KK_STUDIO_DAEMON_ENVIRONMENT_NAME:-}"

entry="$dist/kk-studio-daemon"
node_bin="$dist/runtime/code-server/lib/node"
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
config_helper="$script_dir/daemon-config-cache.mjs"

token="${KK_STUDIO_DAEMON_REGISTRATION_TOKEN:-}"
unset KK_STUDIO_DAEMON_REGISTRATION_TOKEN

if [ -z "$token" ]; then
  echo "ERROR: KK_STUDIO_DAEMON_REGISTRATION_TOKEN is required to register the Daemon." >&2
  exit 1
fi
if [ -z "$studio_url" ]; then
  echo "ERROR: KK_STUDIO_DAEMON_STUDIO_URL (http(s) origin) is required." >&2
  exit 1
fi
if [ -z "$environment_id" ] && [ -z "$environment_name" ]; then
  echo "ERROR: KK_STUDIO_DAEMON_ENVIRONMENT_ID or KK_STUDIO_DAEMON_ENVIRONMENT_NAME is required." >&2
  exit 1
fi
case "$root" in
  /*) ;;
  *)
    echo "ERROR: KK_STUDIO_DAEMON_ROOT must be absolute: $root" >&2
    exit 1
    ;;
esac
if [ ! -x "$entry" ]; then
  echo "ERROR: the Go Daemon executable is missing or not executable: $entry" >&2
  exit 1
fi
if [ ! -x "$node_bin" ]; then
  echo "ERROR: the Daemon distribution has no frozen runtime: $node_bin" >&2
  exit 1
fi
if [ ! -f "$config_helper" ]; then
  echo "ERROR: the Daemon configuration helper is missing: $config_helper" >&2
  exit 1
fi

(umask 077 && mkdir -p "$root")
chmod 700 "$root"
config_file="$root/daemon.json"
token_file="$root/daemon.token"

(umask 077 && printf '%s\n' "$token" >"$token_file")
chmod 600 "$token_file"
unset token

# 卡片 API 与配置序列化都交给唯一的 Node helper；注册凭证不经过 helper 参数或环境。
# umask 与显式 mode 保证配置文件创建时即 owner-only，临时文件同目录 rename 后原子替换。
selector_args=(--config "$config_file" --studio-url "$studio_url")
if [ -n "$environment_id" ]; then
  selector_args+=(--environment-id "$environment_id")
else
  selector_args+=(--environment-name "$environment_name")
fi
(umask 077 && "$node_bin" "$config_helper" "${selector_args[@]}")
chmod 600 "$config_file"
unset studio_url environment_id environment_name selector_args

exec "$entry" --config "$config_file"
