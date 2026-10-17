#!/bin/bash
# kk-studio reliability/distributed Daemon 容器入口（Node 产品）。
#
# Daemon 是完整的 Node 发行目录：入口是发行根目录的 `main.js`，运行时是随发行一起冻结、
# 按 SHA256 校验过的 `runtime/code-server/lib/node`。镜像里没有 JRE，也没有第二个 Node。
#
# 容器内没有 jq/python；序列化非机密配置（studioUrl/note）用的是**同一个冻结 Node**，
# 因此不依赖 PATH 上是否存在用户 Node。注册凭证只经内建 printf 写成 owner-only 文件：
# 凭证文本既不出现在 daemon argv（`ps` 可见），也不留在环境变量中（`/proc/<pid>/environ`
# 可见）。所有文件与目录权限显式收敛为 0600/0700，不依赖镜像 umask。
#
# 配置文件父目录即 Daemon 运行数据目录；根默认在唯一的 /workspace named volume 下，
# 因此不引入额外挂载。daemon 只接收唯一的 `--config <绝对路径>`。
#
# `KK_STUDIO_DAEMON_DIST` 只是镜像布局契约（默认 /opt/kk-studio/daemon），它必须指向一个
# 带 `main.js` 与冻结运行时的发行目录；入口不会去 PATH 上寻找别的 Node 或入口。
set -euo pipefail

# 显式 PATH：入口自身、node/npm 包装与能力子进程都解析到同一份冻结运行时。
export PATH=/usr/local/bin:/usr/bin:/bin

dist="${KK_STUDIO_DAEMON_DIST:-/opt/kk-studio/daemon}"
root="${KK_STUDIO_DAEMON_ROOT:-/workspace/.kk-studio}"
studio_url="${KK_STUDIO_DAEMON_STUDIO_URL:-}"
note="${KK_STUDIO_DAEMON_NOTE:-}"

entry="$dist/main.js"
node_bin="$dist/runtime/code-server/lib/node"

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

mkdir -p "$root"
chmod 700 "$root"
config_file="$root/daemon.json"
token_file="$root/daemon.token"

(umask 077 && printf '%s\n' "$token" >"$token_file")
chmod 600 "$token_file"
unset token

# studioUrl/note 经环境变量交给冻结 Node；值不参与 shell 拼接，因此不存在注入面。
# umask 与显式 mode 保证配置文件创建时即 owner-only，而非先宽后 chmod。
(umask 077 && KK_STUDIO_DAEMON_CONFIG_STUDIO_URL="$studio_url" \
  KK_STUDIO_DAEMON_CONFIG_NOTE="$note" \
  "$node_bin" -e 'const fs = require("fs");
const target = process.argv[1];
const config = { studioUrl: process.env.KK_STUDIO_DAEMON_CONFIG_STUDIO_URL };
const note = process.env.KK_STUDIO_DAEMON_CONFIG_NOTE;
if (note) {
  config.note = note;
}
fs.writeFileSync(target, JSON.stringify(config) + "\n", { mode: 0o600 });' "$config_file")
chmod 600 "$config_file"
unset studio_url note

exec "$node_bin" "$entry" --config "$config_file"
