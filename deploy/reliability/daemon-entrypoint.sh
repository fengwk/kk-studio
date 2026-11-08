#!/bin/bash
# kk-studio reliability/distributed Daemon 容器入口（Node 产品）。
#
# Daemon 是完整的 Node 发行目录：入口是发行根目录的 `main.js`，运行时是随发行一起冻结、
# 按 SHA256 校验过的 `runtime/code-server/lib/node`。镜像里没有 JRE，也没有第二个 Node。
#
# 容器内没有 jq/python；读取 Environment 卡片并序列化本地缓存用的是**同一个冻结 Node**，
# 因此不依赖 PATH 上是否存在用户 Node。注册凭证只经内建 printf 写成 owner-only 文件：
# 凭证文本既不出现在 daemon argv（`ps` 可见），也不留在环境变量中（`/proc/<pid>/environ`
# 可见）。所有文件与目录权限显式收敛为 0600/0700，不依赖镜像 umask。
#
# daemon.json 固定为 {environmentId,version:string,daemonConfig}；Daemon 按卡片 UUID/name
# 从 Studio 读取权威配置，必要时修正卡片 studioUrl 后写缓存。配置文件父目录即 Daemon 运行
# 数据目录；根默认在唯一的 /workspace named volume 下，因此不引入额外挂载。daemon 只接收
# 唯一的 `--config <绝对路径>`。
#
# `KK_STUDIO_DAEMON_DIST` 只是镜像布局契约（默认 /opt/kk-studio/daemon），它必须指向一个
# 带 `main.js` 与冻结运行时的发行目录；入口不会去 PATH 上寻找别的 Node 或入口。
set -euo pipefail

# 显式 PATH：入口自身、node/npm 包装与能力子进程都解析到同一份冻结运行时。
export PATH=/usr/local/bin:/usr/bin:/bin

dist="${KK_STUDIO_DAEMON_DIST:-/opt/kk-studio/daemon}"
root="${KK_STUDIO_DAEMON_ROOT:-/workspace/.kk-studio}"
studio_url="${KK_STUDIO_DAEMON_STUDIO_URL:-}"
environment_id="${KK_STUDIO_DAEMON_ENVIRONMENT_ID:-}"
environment_name="${KK_STUDIO_DAEMON_ENVIRONMENT_NAME:-}"

entry="$dist/main.js"
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
if [ ! -f "$entry" ]; then
  echo "ERROR: the Daemon distribution has no entrypoint: $entry" >&2
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

exec "$node_bin" "$entry" --config "$config_file"
