#!/usr/bin/env bash
# scripts/dev/verify/e2e/lib/daemon-bootstrap.sh
#
# 在隔离的 DAEMON_ROOT 下物化共享 daemon.json 与同目录 daemon.token，然后以唯一的
# `--config <绝对路径>` 启动 Daemon。studioUrl/note（非机密）经 node 序列化为合法 JSON
# 并保留 Unicode；注册凭证只经内建 printf 写入 owner-only 文件，绝不进入 JSON、argv
# 或落地后的 daemon 环境。数据目录即配置文件父目录。
set -euo pipefail

root=${DAEMON_ROOT:?DAEMON_ROOT (absolute daemon root) is required}
studio_url=${DAEMON_STUDIO_URL:?DAEMON_STUDIO_URL (http(s) origin) is required}
note=${DAEMON_NOTE:-}
jar=${DAEMON_JAR:?DAEMON_JAR is required}
java_bin=${JAVA_HOME:-}/bin/java

# 凭证先取出再立即从环境移除；daemon 只通过同目录文件读取。
token=${DAEMON_REGISTRATION_TOKEN:-}
unset DAEMON_REGISTRATION_TOKEN KK_STUDIO_DAEMON_REGISTRATION_TOKEN

if [ -z "$token" ]; then
  echo "ERROR: DAEMON_REGISTRATION_TOKEN is required to register the daemon." >&2
  exit 1
fi
if [ ! -x "$java_bin" ]; then
  echo "ERROR: JAVA_HOME must point to a JDK (missing $java_bin)." >&2
  exit 1
fi
case "$root" in
  /*) ;;
  *)
    echo "ERROR: DAEMON_ROOT must be absolute: $root" >&2
    exit 1
    ;;
esac

mkdir -p "$root"
chmod 700 "$root"
config_file="$root/daemon.json"
token_file="$root/daemon.token"

(umask 077 && printf '%s\n' "$token" >"$token_file")
chmod 600 "$token_file"
unset token

DAEMON_CONFIG_STUDIO_URL="$studio_url" DAEMON_CONFIG_NOTE="$note" \
  node -e 'const fs = require("fs");
const target = process.argv[1];
const config = { studioUrl: process.env.DAEMON_CONFIG_STUDIO_URL };
const note = process.env.DAEMON_CONFIG_NOTE;
if (note) {
  config.note = note;
}
fs.writeFileSync(target, JSON.stringify(config) + "\n");' "$config_file"
chmod 600 "$config_file"
unset DAEMON_CONFIG_STUDIO_URL DAEMON_CONFIG_NOTE

exec "$java_bin" -jar "$jar" --config "$config_file"
