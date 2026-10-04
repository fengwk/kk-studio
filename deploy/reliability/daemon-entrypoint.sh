#!/bin/bash
# kk-studio reliability/distributed Daemon 容器入口。
#
# Daemon 是 shaded 单文件 JAR，只经 `java -jar` 启动，不存在 lib/ 目录或 classpath 文件。
#
# 容器内没有 jq/python；镜像按需提供 node，因此非机密配置（studioUrl/note）经 node
# 序列化为合法 JSON 并保留 Unicode。注册凭证只经内建 printf 写成 owner-only 文件：
# 凭证文本既不出现在 daemon argv（`ps` 可见），也不留在环境变量中（`/proc/<pid>/environ`
# 可见）。所有文件与目录权限显式收敛为 0600/0700，不依赖镜像 umask。
#
# 配置文件父目录即 Daemon 运行数据目录；根默认在唯一的 /workspace named volume 下，
# 因此不引入额外挂载。daemon 只接收唯一的 `--config <绝对路径>`。
set -euo pipefail

root="${KK_STUDIO_DAEMON_ROOT:-/workspace/.kk-studio}"
studio_url="${KK_STUDIO_DAEMON_STUDIO_URL:-}"
note="${KK_STUDIO_DAEMON_NOTE:-}"

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

mkdir -p "$root"
chmod 700 "$root"
config_file="$root/daemon.json"
token_file="$root/daemon.token"

(umask 077 && printf '%s\n' "$token" >"$token_file")
chmod 600 "$token_file"
unset token

# studioUrl/note 经环境变量交给 node；值不参与 shell 拼接，因此不存在注入面。
KK_STUDIO_DAEMON_CONFIG_STUDIO_URL="$studio_url" \
  KK_STUDIO_DAEMON_CONFIG_NOTE="$note" \
  node -e 'const fs = require("fs");
const target = process.argv[1];
const config = { studioUrl: process.env.KK_STUDIO_DAEMON_CONFIG_STUDIO_URL };
const note = process.env.KK_STUDIO_DAEMON_CONFIG_NOTE;
if (note) {
  config.note = note;
}
fs.writeFileSync(target, JSON.stringify(config) + "\n");' "$config_file"
chmod 600 "$config_file"
unset studio_url note

exec java -jar /opt/kk-studio/daemon.jar --config "$config_file"
