#!/usr/bin/env bash
# scripts/dev/verify/e2e/lib/daemon-bootstrap.sh
#
# 在隔离的 DAEMON_ROOT 下物化共享 daemon.json 与同目录 daemon.token，然后以唯一的
# `--config <绝对路径>` 启动 Node Daemon 编译入口 `$DAEMON_DIST/main.js`。
# studioUrl/note（非机密）经 Node 序列化为合法 JSON 并保留 Unicode；注册凭证只经内建
# printf 写入 owner-only 文件，绝不进入 JSON、argv 或落地后的 daemon 环境。数据目录即
# 配置文件父目录。
set -euo pipefail

root=${DAEMON_ROOT:?DAEMON_ROOT (absolute daemon root) is required}
studio_url=${DAEMON_STUDIO_URL:?DAEMON_STUDIO_URL (http(s) origin) is required}
note=${DAEMON_NOTE:-}
dist=${DAEMON_DIST:?DAEMON_DIST (absolute daemon distribution directory) is required}
node_bin=${DAEMON_NODE:-}

# 凭证先取出再立即从环境移除；daemon 只通过同目录文件读取。
token=${DAEMON_REGISTRATION_TOKEN:-}
unset DAEMON_REGISTRATION_TOKEN KK_STUDIO_DAEMON_REGISTRATION_TOKEN

if [ -z "$token" ]; then
  echo "ERROR: DAEMON_REGISTRATION_TOKEN is required to register the daemon." >&2
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

mkdir -p "$root"
chmod 700 "$root"
config_file="$root/daemon.json"
token_file="$root/daemon.token"

(umask 077 && printf '%s\n' "$token" >"$token_file")
chmod 600 "$token_file"
unset token

# studioUrl/note 经环境变量交给 Node；值不参与 shell 拼接，因此不存在注入面。
# umask 与显式 mode 保证配置文件创建时即 owner-only，而非先宽后 chmod。
(umask 077 && DAEMON_CONFIG_STUDIO_URL="$studio_url" DAEMON_CONFIG_NOTE="$note" \
  "$node_bin" -e 'const fs = require("fs");
const target = process.argv[1];
const config = { studioUrl: process.env.DAEMON_CONFIG_STUDIO_URL };
const note = process.env.DAEMON_CONFIG_NOTE;
if (note) {
  config.note = note;
}
fs.writeFileSync(target, JSON.stringify(config) + "\n", { mode: 0o600 });' "$config_file")
chmod 600 "$config_file"
unset DAEMON_CONFIG_STUDIO_URL DAEMON_CONFIG_NOTE

exec "$node_bin" "$entry" --config "$config_file"
