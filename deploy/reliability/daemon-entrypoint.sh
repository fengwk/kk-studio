#!/bin/bash
# kk-studio reliability/distributed Daemon 容器入口。
#
# 注册凭证只经环境变量传入，在任何进程参数出现之前就被物化为 owner-only
# 普通文件：daemon 只接收 `--registration-token-file <绝对路径>`，因此凭证
# 文本既不出现在 argv（`ps` 可见），也不再留在环境变量中（`/proc/<pid>/environ`
# 可见）。文件与目录权限显式收敛为 0600/0700，不依赖镜像 umask。
set -euo pipefail

token_dir="${HOME:-/home/kkdaemon}/.kkstudio"
token_file="$token_dir/daemon-registration.token"

token="${KK_STUDIO_DAEMON_REGISTRATION_TOKEN:-}"
unset KK_STUDIO_DAEMON_REGISTRATION_TOKEN

if [ -z "$token" ]; then
  echo "ERROR: KK_STUDIO_DAEMON_REGISTRATION_TOKEN is required to register the Daemon." >&2
  exit 1
fi

mkdir -p "$token_dir"
chmod 700 "$token_dir"
(umask 077 && printf '%s\n' "$token" >"$token_file")
chmod 600 "$token_file"
unset token

exec java -cp /opt/kk-studio/daemon.jar:/opt/kk-studio/lib/* \
  fun.fengwk.kkstudio.harness.daemon.DaemonMain \
  --registration-token-file "$token_file" \
  "$@"
