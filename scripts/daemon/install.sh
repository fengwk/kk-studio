#!/usr/bin/env bash
# Official-release-only, per-environment installer for the Node Environment Daemon.
# One installation is one Studio card: every managed path and OS service is scoped by its
# canonical environment UUID. Bash 3.2 / GNU and BSD utilities. JSON semantics belong to the
# downloaded daemon preflight; this script enforces the installation envelope only.
set -euo pipefail
umask 077

RELEASE_BASE=https://github.com/fengwk/kk-studio/releases
SERVICE_PREFIX=kk-studio-daemon
LAUNCHD_PREFIX=fun.fengwk.kkstudio.environment-daemon
UNIT_MARKER='# Managed by scripts/daemon/install.sh'
PLIST_MARKER='<!-- Managed by scripts/daemon/install.sh -->'
UUID_PATTERN='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
VERSION_PATTERN='^[0-9A-Za-z._+-]{1,64}$'

ACTION=help
ACTION_LABEL=安装
ENV_ID=
VERSION=
VERSION_JSON=null
CONFIG_FILE=
TOKEN_FILE=
SEEN_OPTIONS=
HOST_OS=
INSTALL_ROOT=
ENV_ROOT=
INSTALLED_LIB=
INSTALLED_CONFIG=
INSTALLED_TOKEN=
WORK_DIR=
LAST_OPERATION=
STDOUT_LOG=
STDERR_LOG=
SERVICE_NAME=
SERVICE_PATH=
SERVICE_MARKER=
LAUNCHD_DOMAIN=
LAUNCHD_TARGET=
BOOTSTRAP_DIR=
MGMT_DIR=
STAGED_NODE=
STAGED_MAIN=
DOWNLOADED_ARCHIVE=
OPERATION_ID=
PHASE='1/7 检查环境与输入'
PHASE_CODE=input
FAILURE_REASON=
STOP_ATTEMPTED=false
PUBLISHED=false
HELPER_PID=
HELPER_FIFO=
HELPER_FD_OPEN=false
HELPER_READY=false
TEMP_PATHS=()
VERIFY_TIMEOUT_SECONDS=${DAEMON_VERIFY_TIMEOUT_SECONDS:-30}
VERIFY_STABLE_SECONDS=${DAEMON_VERIFY_STABLE_SECONDS:-3}

usage() {
  cat <<'EOF'
Usage: bash install.sh install --environment-id <UUID> --version <version> \
                                --config-file <absolute daemon.json> --token-file <absolute daemon.token>
       bash install.sh update --environment-id <UUID> --version <version>
       bash install.sh status --environment-id <UUID>
       bash install.sh uninstall --environment-id <UUID>
       bash install.sh help

Installs, updates, inspects or removes the official Environment Daemon of exactly one Studio
card. The canonical --environment-id UUID scopes the installation directory and the OS service;
two cards of the same user never share a service, a program directory or an operation lock.

install is the only bootstrap/overwrite command. update replaces only the program and the
service definition of an existing installation: it reads the current daemon.json, re-validates
it after the service has stopped and never accepts or writes a configuration or token.
The release must be named explicitly by --version; no mutable "latest" release is resolved.

Fixed layout (0700 directories, 0600 files):
  ~/.kk-studio/environments/<environment-id>/{daemon.json,daemon.token,daemon.lock,install.lock,
  lib/,ide/,skills/,.work/,last-operation.json,daemon.stdout.log,daemon.stderr.log}

Installation operations are serialized by a kernel lock (install.lock) held through a separate
temporary helper copy: no PID guessing, stale lock directory or flock command is used, and the
helper never loads the library that is about to be replaced. A busy card is reported, not waited on.

There is no backup, no historical program directory, no operation log history and no automatic
rollback: a failure after publication can leave the service unavailable and is recovered by
running install again. .work/ is the only staging area and is cleaned by this operation.
last-operation.json holds at most one bounded diagnostic record without secrets.

Uninstall stops and removes only this card's service and program; configuration, credentials,
skills and other user data remain. It does not delete the Studio card.

Status exits 0 when the service is active/loaded, 1 when it is absent, 3 when it is installed
but inactive/not loaded. Loaded is not a running-process check.
DAEMON_VERIFY_TIMEOUT_SECONDS=30 and DAEMON_VERIFY_STABLE_SECONDS=3 control the startup and
stability windows (non-negative integers; 0 checks immediately).
EOF
}

stage() {
  PHASE=$1
  PHASE_CODE=${2:-$PHASE_CODE}
  echo "[$PHASE]" >&2
}
fail() {
  FAILURE_REASON=$*
  exit 1
}
require_cmd() { command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"; }
absolute_path() {
  case "$1" in /*) ;; *) fail "$2 must be an absolute path" ;; esac
  case "$1" in *[[:cntrl:]]*) fail "$2 must not contain control characters" ;; esac
  case "/${1#/}/" in */../* | */./* | *//*) fail "$2 must not contain dot or empty path segments" ;; esac
}
file_owner_uid() { stat -c '%u' "$1" 2>/dev/null || stat -f '%u' "$1" 2>/dev/null; }
file_mode() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1" 2>/dev/null; }
check_metadata() {
  local path=$1 mask=$2 owner mode
  owner=$(file_owner_uid "$path") || fail "cannot inspect owner: $path"
  [ "$owner" = "$(id -u)" ] || fail "must be owned by the current user: $path"
  mode=$(file_mode "$path") || fail "cannot inspect permissions: $path"
  case "$mode" in '' | *[!0-7]*) fail "cannot interpret permissions: $path" ;; esac
  (( (8#$mode & mask) == 0 )) || fail "unsafe permissions: $path"
}
check_private_directory() {
  local path=$1
  [ ! -L "$path" ] || fail "symbolic link directory is unsafe: $path"
  if [ -e "$path" ]; then
    [ -d "$path" ] || fail "must be a directory: $path"
    check_metadata "$path" 8#077
  fi
}
check_file() {
  local path=$1 private=$2
  [ ! -L "$path" ] || fail "symbolic link file is unsafe: $path"
  [ -f "$path" ] && [ -r "$path" ] || fail "must be a readable regular file: $path"
  if [ "$private" = true ]; then check_metadata "$path" 8#077; else check_metadata "$path" 8#022; fi
  local mode
  mode=$(file_mode "$path")
  (( (8#$mode & 8#400) != 0 )) || fail "must be readable by its owner: $path"
}
check_input() {
  local path=$1 maximum=$2 size
  check_private_directory "${path%/*}"
  check_file "$path" true
  check_input_size "$path" "$maximum"
}
check_input_size() {
  local size
  size=$(wc -c <"$1")
  (( size > 0 && size <= $2 )) || fail "input is empty or exceeds size limit: $1"
}
ensure_private_root() {
  local path=$1
  if [ ! -e "$path" ]; then mkdir -m 0700 "$path" || fail "无法创建受管目录。"; fi
  check_private_directory "$path"
}
check_managed_paths() {
  local path
  check_private_directory "$INSTALL_ROOT"
  check_private_directory "$INSTALL_ROOT/environments"
  check_private_directory "$ENV_ROOT"
  for path in "$INSTALLED_LIB" "$ENV_ROOT/ide" "$ENV_ROOT/skills" "$WORK_DIR"; do
    check_private_directory "$path"
  done
  for path in "$INSTALLED_CONFIG" "$INSTALLED_TOKEN" "$LAST_OPERATION" "$STDOUT_LOG" "$STDERR_LOG"; do
    if [ -e "$path" ] || [ -L "$path" ]; then check_file "$path" true; fi
  done
}

validate_environment_id_value() {
  [[ $1 =~ $UUID_PATTERN ]] ||
    fail "--environment-id must be a canonical lowercase UUID (value not echoed)"
  ENV_ID=$1
}
validate_version() { [[ $VERSION =~ $VERSION_PATTERN ]] || fail "invalid version"; }
new_operation_id() {
  local hex nibble variant
  hex=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')
  nibble=$((16#${hex:16:1}))
  case $((nibble % 4)) in 0) variant=8 ;; 1) variant=9 ;; 2) variant=a ;; 3) variant=b ;; esac
  printf '%s-%s-4%s-%s%s-%s\n' "${hex:0:8}" "${hex:8:4}" "${hex:13:3}" "$variant" "${hex:17:3}" "${hex:20:12}"
}

# Written only while this operation holds install.lock. Fixed fields only: no free-form message,
# no configuration or token, no caller-supplied text. The record is atomically replaced.
write_last_operation() {
  local result=$1 code=$2 timestamp json temporary
  [ -d "$ENV_ROOT" ] || return 0
  timestamp=$(( $(date +%s) * 1000 ))
  json=$(printf '{"operationId":"%s","type":"%s","targetVersion":%s,"phase":"%s","result":"%s","time":%s,"errorCode":%s}' \
    "$OPERATION_ID" "$ACTION" "$VERSION_JSON" "$PHASE_CODE" "$result" "$timestamp" "$code")
  (( ${#json} <= 4096 )) || return 0
  temporary="$ENV_ROOT/.last-operation.json.tmp.$$"
  printf '%s\n' "$json" >"$temporary" || return 0
  chmod 0600 "$temporary" || { rm -f "$temporary"; return 0; }
  mv -f "$temporary" "$LAST_OPERATION" || { rm -f "$temporary"; return 0; }
}
cleanup() {
  local code=$? failure=$FAILURE_REASON path error=null
  trap - EXIT
  for path in ${TEMP_PATHS[@]+"${TEMP_PATHS[@]}"}; do rm -f "$path"; done
  if [ "$HELPER_READY" = true ]; then
    # .work/ is this card's only staging area; every operation result cleans it here.
    if [ -n "$WORK_DIR" ]; then rm -rf "$WORK_DIR"; fi
    if [ "$code" -ne 0 ]; then error=$(printf '"%s"' "$PHASE_CODE"); fi
    write_last_operation "$(if [ "$code" -eq 0 ]; then echo succeeded; else echo failed; fi)" "$error"
  fi
  if ! release_install_lock; then
    if [ "$code" -eq 0 ]; then
      echo "安装完成，但安装操作锁未能正常释放；锁由进程退出释放。" >&2
      code=1
    fi
  fi
  if [ -n "$MGMT_DIR" ]; then rm -rf "$MGMT_DIR"; fi
  if [ -n "$BOOTSTRAP_DIR" ]; then rm -rf "$BOOTSTRAP_DIR"; fi
  if [ "$code" -ne 0 ] && [ "$ACTION" != status ] && [ "$ACTION" != help ]; then
    echo "${ACTION_LABEL}失败 [${PHASE}]：${failure:-该阶段操作未完成。}" >&2
    if [ "$PUBLISHED" = true ]; then
      echo "受管程序或服务已开始替换；未自动回滚、未保留备份目录。" >&2
    elif [ "$STOP_ATTEMPTED" = true ]; then
      echo "受管程序尚未替换；已尝试停止服务，请检查服务状态。" >&2
    else
      echo "现有程序、配置和服务未改动。" >&2
    fi
    if [ -d "$ENV_ROOT" ] && [ -f "$LAST_OPERATION" ]; then
      echo "本机诊断：$LAST_OPERATION" >&2
    fi
    if [ "$HOST_OS" = Darwin ] && [ "$PUBLISHED" = true ]; then
      echo "下一步：查看日志 ${STDERR_LOG}，修正后重新执行对应命令。" >&2
    elif [ "$HOST_OS" = Linux ]; then
      echo "下一步：运行 systemctl --user status $SERVICE_NAME 检查服务状态。" >&2
    fi
    echo "下一步：修正上述问题后重新执行对应命令。" >&2
  fi
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

ownership_conflict() {
  local reason=$1 actual_path=${2:-$SERVICE_PATH}
  echo "安装失败：拒绝操作非受管服务（${reason}）。未自动回滚，未知文件未变更。" >&2
  echo "服务定义：$actual_path" >&2
  if [ "$HOST_OS" = Linux ]; then
    echo "下一步：运行 systemctl --user status $SERVICE_NAME 确认归属后再处理。不要伪造所有权标记，不要改动未知文件。" >&2
  else
    echo "下一步：运行 launchctl print $LAUNCHD_TARGET 确认归属后再处理。不要伪造所有权标记，不要改动未知文件。" >&2
  fi
  FAILURE_REASON="拒绝操作非受管服务（$reason）"
  PHASE_CODE=ownership
  exit 1
}
# Ownership requires the current user, the exact marker and the UUID-scoped program/config paths;
# a definition that merely shares the expected name is not claimable.
require_ownership() {
  local fragment= marker owner mode
  if [ "$HOST_OS" = Linux ]; then
    fragment=$(systemctl --user show --property=FragmentPath --value "$SERVICE_NAME") ||
      fail "cannot inspect systemd service ownership"
    if [ -n "$fragment" ] && [ "$fragment" != "$SERVICE_PATH" ] &&
      [ ! "$fragment" -ef "$SERVICE_PATH" ]; then
      ownership_conflict "service resolves to another definition" "$fragment"
    fi
  fi
  if [ -e "$SERVICE_PATH" ] || [ -L "$SERVICE_PATH" ]; then
    [ ! -L "$SERVICE_PATH" ] && [ -f "$SERVICE_PATH" ] ||
      ownership_conflict "definition is a symlink or not a regular file"
    if [ "$HOST_OS" = Linux ]; then marker=$(head -n 1 "$SERVICE_PATH");
    else marker=$(sed -n '2p' "$SERVICE_PATH"); fi
    [ "$marker" = "$SERVICE_MARKER" ] || ownership_conflict "missing ownership marker"
    owner=$(file_owner_uid "$SERVICE_PATH") || ownership_conflict "cannot inspect definition owner"
    [ "$owner" = "$(id -u)" ] || ownership_conflict "definition belongs to another user"
    mode=$(file_mode "$SERVICE_PATH") || ownership_conflict "cannot inspect definition permissions"
    case "$mode" in '' | *[!0-7]*) ownership_conflict "cannot interpret definition permissions" ;; esac
    (( (8#$mode & 8#022) == 0 )) || ownership_conflict "definition is group/other writable"
    check_file "$SERVICE_PATH" false
    grep -Fq -- "$INSTALLED_LIB/runtime/code-server/lib/node" "$SERVICE_PATH" ||
      ownership_conflict "definition does not run this environment program"
    grep -Fq -- "$INSTALLED_LIB/main.js" "$SERVICE_PATH" ||
      ownership_conflict "definition does not run this environment program"
    grep -Fq -- "$INSTALLED_CONFIG" "$SERVICE_PATH" ||
      ownership_conflict "definition does not use this environment configuration"
  elif [ "$HOST_OS" = Darwin ] && launchd_loaded; then
    ownership_conflict "job is loaded but its definition is absent"
  elif [ "$HOST_OS" = Linux ] && [ -n "$fragment" ]; then
    ownership_conflict "resolved definition is absent from disk" "$fragment"
  fi
}
require_manager() {
  if [ "$HOST_OS" = Linux ]; then
    require_cmd systemctl
    systemctl --user --no-pager show-environment >/dev/null 2>&1 ||
      fail "systemctl --user is unavailable or unusable"
  else
    require_cmd launchctl
    launchctl print "$LAUNCHD_DOMAIN" >/dev/null 2>&1 ||
      fail "launchctl GUI domain $LAUNCHD_DOMAIN is unavailable"
  fi
}
launchd_loaded() { launchctl print "$LAUNCHD_TARGET" >/dev/null 2>&1; }

# 本机发行平台：uname 结果映射到官方归档的 os/arch 命名。不支持的平台 fail closed。
resolve_release_platform() {
  local machine
  case "$HOST_OS" in
    Linux) RELEASE_OS=linux; KO_OS=linux ;;
    Darwin) RELEASE_OS=macos; KO_OS=darwin ;;
    *) fail "unsupported operating system: $HOST_OS" ;;
  esac
  machine=$(uname -m)
  case "$machine" in
    x86_64 | amd64) RELEASE_ARCH=amd64; KO_ARCH=x64 ;;
    arm64 | aarch64) RELEASE_ARCH=arm64; KO_ARCH=arm64 ;;
    *) fail "unsupported architecture: $machine" ;;
  esac
}
release_curl() {
  local progress=--silent
  if [ "${1:-}" = --archive ]; then
    shift
    if [ -t 2 ]; then progress=--progress-bar; fi
  fi
  curl -q --fail "$progress" --show-error --location --proto '=https' --proto-redir '=https' "$@"
}
# 下载/解包在系统临时目录进行，绝不进入卡片目录：取得安装操作锁之前，另一个操作不得清理这里的文件。
bootstrap_directory() {
  local base=${TMPDIR:-/tmp}
  [ -d "$base" ] || fail "系统临时目录不存在。"
  BOOTSTRAP_DIR=$(mktemp -d "$base/kk-studio-install.XXXXXXXX") ||
    fail "无法创建系统临时目录。"
  chmod 0700 "$BOOTSTRAP_DIR"
}
download_daemon() {
  local asset checksum expected actual pattern
  require_cmd curl
  RELEASE_TAG="v$VERSION"
  [[ $RELEASE_TAG =~ ^v[0-9A-Za-z._+-]{1,64}$ ]] || fail "invalid official release tag"
  echo "版本：${RELEASE_TAG}；平台：${RELEASE_OS}-${RELEASE_ARCH}" >&2
  stage '3/7 下载归档' download
  bootstrap_directory
  asset="kk-studio-daemon-$RELEASE_TAG-$RELEASE_OS-$RELEASE_ARCH.tar.gz"
  DOWNLOADED_ARCHIVE="$BOOTSTRAP_DIR/$asset"
  checksum="$DOWNLOADED_ARCHIVE.sha256"
  release_curl --archive --output "$DOWNLOADED_ARCHIVE" "$RELEASE_BASE/download/$RELEASE_TAG/$asset" ||
    fail "无法下载：正式版本发行归档获取失败。请检查网络后重试。"
  release_curl --output "$checksum" "$RELEASE_BASE/download/$RELEASE_TAG/$asset.sha256" ||
    fail "无法下载：正式版本 SHA256 获取失败。请检查网络后重试。"
  stage '4/7 校验解包配置' verify
  expected=$(cat "$checksum")
  pattern=${asset//./\\.}
  pattern=${pattern//+/\\+}
  pattern="^([0-9a-fA-F]{64})[[:blank:]]+\\*?${pattern}$"
  [[ $expected =~ $pattern ]] || fail "校验失败：SHA256 文件格式无效。"
  expected=${BASH_REMATCH[1]}
  if command -v sha256sum >/dev/null 2>&1; then actual=$(sha256sum "$DOWNLOADED_ARCHIVE");
  else require_cmd shasum; actual=$(shasum -a 256 "$DOWNLOADED_ARCHIVE"); fi
  expected=$(printf '%s' "$expected" | tr 'A-F' 'a-f')
  [ "${actual%% *}" = "$expected" ] || fail "校验失败：SHA256 与正式版本不一致。"
}
# 配置信封检查：旧平铺 JSON 与其它卡片的配置都必须在停止服务前被拒绝。退出码 3 专门表示
# UUID 不匹配；daemonConfig 的内部规则由发行归档的 --check-config 负责。
check_config_envelope() {
  local file=$1 status=0
  "$STAGED_NODE" -e '
    const fs = require("node:fs");
    let value;
    try { value = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); } catch { process.exit(2); }
    if (value === null || typeof value !== "object" || Array.isArray(value)) process.exit(2);
    const keys = Object.keys(value).sort().join(",");
    if (keys !== "daemonConfig,environmentId,version") process.exit(2);
    if (typeof value.environmentId !== "string" || value.environmentId !== process.argv[2]) process.exit(3);
    if (typeof value.version !== "string" || !/^(0|[1-9][0-9]*)$/.test(value.version)) process.exit(2);
    if (value.daemonConfig === null || typeof value.daemonConfig !== "object" ||
        Array.isArray(value.daemonConfig)) process.exit(2);
  ' "$file" "$ENV_ID" || status=$?
  case "$status" in
    0) ;;
    3) fail "配置属于另一张环境卡片；未改动现有安装。" ;;
    *) fail "配置格式不是当前版本；未改动现有安装。" ;;
  esac
}
early_input_check() {
  local file=$1
  grep -Fq '"environmentId"' "$file" && grep -Fq '"daemonConfig"' "$file" &&
    grep -Fq '"version"' "$file" ||
    fail "配置格式不是当前版本；未改动现有安装。"
}
preflight_failure_detail() {
  local line
  while IFS= read -r line; do
    case "$line" in
      'Invalid daemon configuration:'*)
        line=${line%$'\r'}
        case "$line" in *[[:cntrl:]]*) return 1 ;; esac
        (( ${#line} <= 512 )) || line=${line:0:512}
        printf '%s' "$line"
        return 0
        ;;
    esac
  done <"$BOOTSTRAP_DIR/preflight.log"
  return 1
}
run_check_config() {
  local node=$1 main=$2 config=$3 output detail
  if ! "$node" "$main" --check-config "$config" >"$BOOTSTRAP_DIR/preflight.log" 2>&1; then
    detail=$(preflight_failure_detail) &&
      fail "配置校验失败：$detail 现有安装未改动。"
    fail "配置校验失败：daemon.json 与同目录 daemon.token 未通过校验。现有安装未改动。"
  fi
  output=$(cat "$BOOTSTRAP_DIR/preflight.log")
  [ "$output" = "Daemon configuration is valid" ] ||
    fail "配置校验失败：--check-config 输出无法识别。现有安装未改动。"
}
extract_release() {
  local paths path manifest_platform version_output
  [ -s "$DOWNLOADED_ARCHIVE" ] || fail "校验失败：下载的发行归档为空。"
  paths=$(tar -tzf "$DOWNLOADED_ARCHIVE") || fail "校验失败：发行归档无法列出。"
  while IFS= read -r path; do
    case "$path" in
      /* | ../* | .. | */../* | */.. | *\\* | *:*) fail "校验失败：发行归档包含不安全路径。" ;;
    esac
  done <<<"$paths"
  mkdir -p "$BOOTSTRAP_DIR/extract"
  tar -xzf "$DOWNLOADED_ARCHIVE" -C "$BOOTSTRAP_DIR/extract" || fail "校验失败：发行归档无法解包。"
  STAGED_NODE="$BOOTSTRAP_DIR/extract/runtime/code-server/lib/node"
  STAGED_MAIN="$BOOTSTRAP_DIR/extract/main.js"
  [ -x "$STAGED_NODE" ] || fail "校验失败：发行归档缺少运行时。"
  [ -f "$BOOTSTRAP_DIR/extract/native/lock-helper.js" ] ||
    fail "校验失败：发行归档缺少安装操作锁 helper。"
  [ -d "$BOOTSTRAP_DIR/extract/node_modules/koffi" ] ||
    fail "校验失败：发行归档缺少 helper 的 native 依赖。"
  [ -f "$BOOTSTRAP_DIR/extract/runtime/code-server/out/node/entry.js" ] ||
    fail "校验失败：发行归档缺少 code-server 入口。"
  [ -f "$STAGED_MAIN" ] || fail "校验失败：发行归档缺少程序入口。"
  [ -f "$BOOTSTRAP_DIR/extract/runtime-manifest.json" ] || fail "校验失败：发行归档缺少运行时清单。"
  manifest_platform=$(
    "$STAGED_NODE" -e '
      const fs = require("fs");
      const manifest = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
      const key = process.argv[2];
      const asset = manifest.codeServer && manifest.codeServer[key];
      if (!asset || typeof asset !== "object") process.exit(2);
      process.stdout.write(key);
    ' "$BOOTSTRAP_DIR/extract/runtime-manifest.json" "$RELEASE_OS-$RELEASE_ARCH" 2>/dev/null
  ) || fail "校验失败：发行清单不是本机平台。"
  [ "$manifest_platform" = "$RELEASE_OS-$RELEASE_ARCH" ] || fail "校验失败：发行清单不是本机平台。"
  version_output=$("$STAGED_NODE" "$STAGED_MAIN" --version 2>&1) ||
    fail "校验失败：下载的发行归档无法执行。"
  [ "$version_output" = "kk-studio-daemon $VERSION" ] ||
    fail "daemon --version does not match resolved release tag"
}
stage_release_inputs() {
  local source_config=$1 source_token=$2
  check_input "$source_config" 1048576
  check_input "$source_token" 16384
  mkdir -p "$BOOTSTRAP_DIR/inputs"
  chmod 0700 "$BOOTSTRAP_DIR/inputs"
  cp "$source_config" "$BOOTSTRAP_DIR/inputs/daemon.json" || fail "无法暂存环境配置。"
  cp "$source_token" "$BOOTSTRAP_DIR/inputs/daemon.token" || fail "无法暂存环境凭据。"
  chmod 0600 "$BOOTSTRAP_DIR/inputs/daemon.json" "$BOOTSTRAP_DIR/inputs/daemon.token"
  STAGED_CONFIG="$BOOTSTRAP_DIR/inputs/daemon.json"
  STAGED_TOKEN="$BOOTSTRAP_DIR/inputs/daemon.token"
  check_input_size "$STAGED_CONFIG" 1048576
  check_input_size "$STAGED_TOKEN" 16384
  check_config_envelope "$STAGED_CONFIG"
  run_check_config "$BOOTSTRAP_DIR/extract/runtime/code-server/lib/node" \
    "$BOOTSTRAP_DIR/extract/main.js" "$STAGED_CONFIG"
}
# 独立管理副本：只复制冻结 Node、锁 helper 及其 native 依赖。它不加载即将删除的旧 lib，
# 也不加载即将 rename 的新 lib，因此 Windows 上被加载的 node/native 文件不会被移动或删除。
prepare_management_copy() {
  local source=$1 koffi_arch
  koffi_arch="koffi-${KO_OS}-${KO_ARCH}"
  MGMT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/kk-studio-managed.XXXXXXXX") ||
    fail "无法创建独立管理副本。"
  chmod 0700 "$MGMT_DIR"
  cp "$source/runtime/code-server/lib/node" "$MGMT_DIR/node" || fail "无法复制冻结运行时。"
  chmod 0700 "$MGMT_DIR/node"
  cp "$source/package.json" "$MGMT_DIR/package.json" || fail "无法复制 helper 模块清单。"
  cp "$source/delay.js" "$MGMT_DIR/delay.js" || fail "无法复制 helper 依赖。"
  mkdir -p "$MGMT_DIR/native" "$MGMT_DIR/node_modules/@koromix"
  cp "$source/native/"*.js "$MGMT_DIR/native/" || fail "无法复制安装操作锁 helper。"
  cp -R "$source/node_modules/koffi" "$MGMT_DIR/node_modules/koffi" ||
    fail "无法复制 helper 的 native 依赖。"
  cp -R "$source/node_modules/@koromix/$koffi_arch" "$MGMT_DIR/node_modules/@koromix/$koffi_arch" ||
    fail "无法复制 helper 的 native 预编译模块。"
  [ -f "$MGMT_DIR/native/lock-helper.js" ] && [ -x "$MGMT_DIR/node" ] ||
    fail "独立管理副本不完整。"
}
# 安装操作锁：helper 先取得内核 install.lock，再在 stdout 报一行 locked。脚本保持管道打开直到
# 受管发布与启动验证结束；脚本异常退出时管道关闭，helper 随之释放内核锁。
hold_install_lock() {
  local label=$1 ready errors status attempt
  stage "$label" lock
  ready="$BOOTSTRAP_DIR/helper.ready"
  errors="$BOOTSTRAP_DIR/helper.err"
  HELPER_FIFO="$BOOTSTRAP_DIR/helper.stdin"
  mkfifo -m 600 "$HELPER_FIFO" || fail "无法创建安装操作锁管道。"
  "$MGMT_DIR/node" "$MGMT_DIR/native/lock-helper.js" hold "$ENV_ROOT" \
    <"$HELPER_FIFO" >"$ready" 2>"$errors" &
  HELPER_PID=$!
  exec 9>"$HELPER_FIFO" || fail "无法打开安装操作锁管道。"
  HELPER_FD_OPEN=true
  for ((attempt = 0; attempt <= VERIFY_TIMEOUT_SECONDS * 10; attempt++)); do
    if grep -qx 'locked' "$ready" 2>/dev/null; then
      HELPER_READY=true
      return 0
    fi
    if ! kill -0 "$HELPER_PID" 2>/dev/null; then
      status=0
      wait "$HELPER_PID" || status=$?
      HELPER_PID=
      exec 9>&- || true
      HELPER_FD_OPEN=false
      case "$status" in
        75) fail "该环境的另一个安装操作正在执行；本次未改动任何受管文件。" ;;
        *) fail "无法取得安装操作锁；本次未改动任何受管文件。" ;;
      esac
    fi
    sleep 0.1
  done
  fail "取得安装操作锁超时；本次未改动任何受管文件。"
}
release_install_lock() {
  local status=0
  if [ "$HELPER_FD_OPEN" = true ]; then
    exec 9>&- || status=1
    HELPER_FD_OPEN=false
  fi
  if [ -n "$HELPER_PID" ]; then
    wait "$HELPER_PID" || status=$?
    HELPER_PID=
    HELPER_READY=false
    if [ "$status" -ne 0 ]; then return 1; fi
  fi
  return 0
}
# 停止服务后用真实内核运行锁证明目标进程已经退出。超时或无法检查都必须失败，而不是继续替换文件。
stop_service_and_wait() {
  local label=$1 status=0
  stage "$label" stop
  if [ -e "$SERVICE_PATH" ] || { [ "$HOST_OS" = Darwin ] && launchd_loaded; }; then
    STOP_ATTEMPTED=true
    if [ "$HOST_OS" = Linux ]; then
      systemctl --user stop "$SERVICE_NAME" >/dev/null 2>&1 ||
        fail "systemd 未能停止服务；程序和配置尚未替换。"
    else
      launchctl bootout "$LAUNCHD_TARGET" >/dev/null 2>&1 ||
        fail "无法停止服务，未替换或删除任何文件。"
    fi
  fi
  PHASE_CODE=wait-lock
  "$MGMT_DIR/node" "$MGMT_DIR/native/lock-helper.js" wait "$ENV_ROOT" "$VERIFY_TIMEOUT_SECONDS" ||
    status=$?
  case "$status" in
    0) ;;
    76) fail "服务停止后仍持有运行锁，未替换或删除任何受管文件。" ;;
    *) fail "无法确认服务已释放运行锁，未替换或删除任何受管文件。" ;;
  esac
}
xml_escape() {
  local value=$1 index=0 character
  while [ "$index" -lt "${#value}" ]; do
    character=${value:index:1}
    case "$character" in
      '&') printf '&amp;' ;; '<') printf '&lt;' ;; '>') printf '&gt;' ;;
      "'") printf '&apos;' ;; '"') printf '&quot;' ;; *) printf '%s' "$character" ;;
    esac
    index=$((index + 1))
  done
}
xml_string() { printf '<string>%s</string>\n' "$(xml_escape "$1")"; }
escape_exec_argument() {
  local value=$1 percent_escape='%%' dollar_escape='$$'
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//%/$percent_escape}
  value=${value//\$/$dollar_escape}
  printf '"%s"' "$value"
}
stage_service() {
  local node_bin="$INSTALLED_LIB/runtime/code-server/lib/node"
  local arguments=("$node_bin" "$INSTALLED_LIB/main.js" --config "$INSTALLED_CONFIG") argument line=
  if [ "$HOST_OS" = Linux ]; then
    for argument in "${arguments[@]}"; do line="$line $(escape_exec_argument "$argument")"; done
    cat >"$BOOTSTRAP_DIR/service" <<EOF
$UNIT_MARKER
[Unit]
Description=kk-studio Environment Daemon ($ENV_ID)
Wants=network-online.target
After=network-online.target
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
WorkingDirectory=%h
ExecStart=${line# }
Restart=on-failure
RestartSec=10
TimeoutStopSec=30
KillMode=mixed
UMask=0077
NoNewPrivileges=yes
SyslogIdentifier=kk-studio-daemon-$ENV_ID

[Install]
WantedBy=default.target
EOF
  else
    {
      printf '<?xml version="1.0" encoding="UTF-8"?>\n%s\n' "$PLIST_MARKER"
      printf '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict>\n<key>Label</key>\n'
      xml_string "$LAUNCHD_LABEL"
      printf '<key>ProgramArguments</key><array>\n'
      for argument in "${arguments[@]}"; do xml_string "$argument"; done
      printf '</array>\n<key>RunAtLoad</key><true/><key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>\n<key>ThrottleInterval</key><integer>10</integer>\n<key>Umask</key><integer>63</integer>\n<key>WorkingDirectory</key>\n'
      xml_string "$HOME"
      printf '<key>StandardOutPath</key>\n'; xml_string "$STDOUT_LOG"
      printf '<key>StandardErrorPath</key>\n'; xml_string "$STDERR_LOG"
      printf '</dict></plist>\n'
    } >"$BOOTSTRAP_DIR/service"
    require_cmd plutil
    plutil -lint "$BOOTSTRAP_DIR/service" >/dev/null ||
      fail "生成的 LaunchAgent 未通过校验；现有安装未改动。"
  fi
}
stage_publish_file() {
  local source=$1 directory=$2 name=$3 mode=$4
  mkdir -p "$directory"
  STAGED_FILE=$(mktemp "$directory/.$name.tmp.XXXXXXXX") || fail "无法创建待发布文件。"
  TEMP_PATHS+=("$STAGED_FILE")
  cp "$source" "$STAGED_FILE" || fail "无法复制待发布文件。"
  chmod "$mode" "$STAGED_FILE" || fail "无法设置待发布文件权限。"
}
# .work 是本卡片唯一暂存区：取得操作锁后先清理上一次的残留，再从已验证归档解包到目标文件系统，
# lib 的发布因此是同文件系统 rename，而不是可能部分完成的跨文件系统复制。
stage_program() {
  WORK_DIR="$ENV_ROOT/.work"
  rm -rf "$WORK_DIR"
  mkdir -m 0700 "$WORK_DIR" || fail "无法在卡片目录创建暂存区。"
  cp "$DOWNLOADED_ARCHIVE" "$WORK_DIR/archive.tar.gz" || fail "无法复制发行归档到暂存区。"
  chmod 0600 "$WORK_DIR/archive.tar.gz"
  mkdir -m 0700 "$WORK_DIR/lib" || fail "无法创建暂存程序目录。"
  tar -xzf "$WORK_DIR/archive.tar.gz" -C "$WORK_DIR/lib" ||
    fail "无法在卡片暂存区解包发行归档。"
  rm -f "$WORK_DIR/archive.tar.gz"
  [ -f "$WORK_DIR/lib/main.js" ] && [ -x "$WORK_DIR/lib/runtime/code-server/lib/node" ] ||
    fail "暂存程序不完整。"
}
publish_program() {
  rm -rf "$INSTALLED_LIB" || fail "无法删除旧程序目录。"
  mv "$WORK_DIR/lib" "$INSTALLED_LIB" || fail "无法发布新程序目录。"
  PUBLISHED=true
}
start_and_verify() {
  local attempt stable pid active
  stage '7/7 稳定性验证' start
  if [ "$HOST_OS" = Linux ]; then
    systemctl --user daemon-reload >/dev/null 2>&1 || fail "systemd 未能重新加载服务定义。"
    systemctl --user enable "$SERVICE_NAME" >/dev/null 2>&1 || fail "systemd 未能启用用户服务。"
    systemctl --user reset-failed "$SERVICE_NAME" >/dev/null 2>&1 ||
      fail "无法清除旧 systemd 失败计数；请检查用户服务管理器后重新安装。"
    systemctl --user restart "$SERVICE_NAME" >/dev/null 2>&1 || fail "systemd 未能启动服务。"
  else
    launchctl bootstrap "$LAUNCHD_DOMAIN" "$SERVICE_PATH" >/dev/null 2>&1 ||
      fail "launchd 未能加载 LaunchAgent。"
  fi
  PHASE_CODE=verify
  for ((attempt = 0; attempt <= VERIFY_TIMEOUT_SECONDS; attempt++)); do
    active=false
    if [ "$HOST_OS" = Linux ]; then
      if systemctl --user is-active --quiet "$SERVICE_NAME"; then active=true; fi
    elif pid=$(launchctl kickstart -p "$LAUNCHD_TARGET" 2>/dev/null); then
      pid=${pid//[[:space:]]/}
      case "$pid" in '' | *[!0-9]*) fail "服务启动失败：LaunchAgent 没有报告数字进程号。" ;; esac
      if kill -0 "$pid" 2>/dev/null; then active=true; fi
    fi
    if [ "$active" = true ]; then
      for ((stable = 0; stable < VERIFY_STABLE_SECONDS; stable++)); do
        sleep 1
        if [ "$HOST_OS" = Linux ]; then
          systemctl --user is-active --quiet "$SERVICE_NAME" || fail "服务启动失败：服务未能保持运行。"
        else kill -0 "$pid" 2>/dev/null || fail "服务启动失败：LaunchAgent 未能保持运行。"; fi
      done
      return 0
    fi
    if (( attempt < VERIFY_TIMEOUT_SECONDS )); then sleep 1; fi
  done
  fail "服务启动失败：服务启动后没有保持运行。"
}

cmd_install_arguments() {
  local option value
  while [ $# -gt 0 ]; do
    option=$1
    case "$option" in
      --environment-id | --version | --config-file | --token-file) ;;
      *) fail "unknown option (value not echoed)" ;;
    esac
    case "$SEEN_OPTIONS" in *"|$option|"*) fail "duplicate option: $option" ;; esac
    SEEN_OPTIONS="$SEEN_OPTIONS|$option|"
    [ $# -ge 2 ] || fail "missing value for $option"
    value=$2
    [ -n "$value" ] || fail "missing value for $option"
    case "$option" in
      --environment-id) validate_environment_id_value "$value" ;;
      --version) VERSION=$value ;;
      --config-file) absolute_path "$value" "$option"; CONFIG_FILE=$value ;;
      --token-file) absolute_path "$value" "$option"; TOKEN_FILE=$value ;;
    esac
    shift 2
  done
  [ -n "$ENV_ID" ] && [ -n "$VERSION" ] && [ -n "$CONFIG_FILE" ] && [ -n "$TOKEN_FILE" ] ||
    fail "install requires --environment-id, --version, --config-file and --token-file"
  validate_version
  [ "${CONFIG_FILE##*/}" = daemon.json ] && [ "${TOKEN_FILE##*/}" = daemon.token ] &&
    [ "${CONFIG_FILE%/*}" = "${TOKEN_FILE%/*}" ] || fail "inputs must be sibling daemon.json and daemon.token"
}
cmd_update_arguments() {
  local option value
  while [ $# -gt 0 ]; do
    option=$1
    case "$option" in --environment-id | --version) ;; *) fail "unknown option (value not echoed)" ;; esac
    case "$SEEN_OPTIONS" in *"|$option|"*) fail "duplicate option: $option" ;; esac
    SEEN_OPTIONS="$SEEN_OPTIONS|$option|"
    [ $# -ge 2 ] || fail "missing value for $option"
    value=$2
    [ -n "$value" ] || fail "missing value for $option"
    case "$option" in
      --environment-id) validate_environment_id_value "$value" ;;
      --version) VERSION=$value ;;
    esac
    shift 2
  done
  [ -n "$ENV_ID" ] && [ -n "$VERSION" ] ||
    fail "update requires --environment-id and --version (it never accepts configuration or token)"
  validate_version
}
cmd_install_or_update() {
  local operation=$1 staged_config= staged_token= staged_service path
  if [ "$operation" = install ]; then
    ACTION_LABEL=安装
    stage '1/7 检查环境与输入' input
    require_manager
    require_ownership
    check_input "$CONFIG_FILE" 1048576
    check_input "$TOKEN_FILE" 16384
    early_input_check "$CONFIG_FILE"
    if [ -e "$INSTALLED_LIB" ] && [ ! -f "$SERVICE_PATH" ] && ! { [ "$HOST_OS" = Darwin ] && launchd_loaded; }; then
      ownership_conflict "program directory exists without the owned service"
    fi
  else
    ACTION_LABEL=更新
    stage '1/7 检查环境与输入' input
    require_manager
    require_ownership
    [ -d "$ENV_ROOT" ] || fail "未安装：update 只能更新已有的受管安装。"
    [ -d "$INSTALLED_LIB" ] || fail "未安装：缺少受管程序目录。"
    for path in "$INSTALLED_CONFIG" "$INSTALLED_TOKEN"; do
      [ -f "$path" ] || fail "缺少当前环境配置或凭据；update 不会创建它们。"
      check_file "$path" true
    done
    grep -Fq '"environmentId"' "$INSTALLED_CONFIG" ||
      fail "现有配置不是当前版本；请重新执行 install。"
  fi
  check_managed_paths
  stage '2/7 解析版本' version
  resolve_release_platform
  download_daemon
  extract_release
  if [ "$operation" = install ]; then
    stage_release_inputs "$CONFIG_FILE" "$TOKEN_FILE"
  else
    # update 只读取当前配置做信封检查；最终语义复检在停机之后用新程序完成。
    check_config_envelope "$INSTALLED_CONFIG"
  fi
  prepare_management_copy "$BOOTSTRAP_DIR/extract"
  stage_service
  # All semantic checks precede target writes and stop.
  require_ownership
  hold_install_lock '5/7 取得操作锁'
  ensure_private_root "$INSTALL_ROOT"
  ensure_private_root "$INSTALL_ROOT/environments"
  ensure_private_root "$ENV_ROOT"
  check_managed_paths
  if [ "$operation" = install ]; then
    stage_publish_file "$STAGED_CONFIG" "$ENV_ROOT" daemon.json 0600
    staged_config=$STAGED_FILE
    stage_publish_file "$STAGED_TOKEN" "$ENV_ROOT" daemon.token 0600
    staged_token=$STAGED_FILE
  fi
  stage_publish_file "$BOOTSTRAP_DIR/service" "${SERVICE_PATH%/*}" service 0644
  staged_service=$STAGED_FILE
  stage_program
  stop_service_and_wait '6/7 停止发布启动'
  # Final checks after the service is stopped: install validates the exact snapshot that will be
  # published; update re-validates the current configuration against the new program and never
  # accepts another one.
  PHASE_CODE=validate
  if [ "$operation" = install ]; then
    check_config_envelope "$staged_config"
    run_check_config "$BOOTSTRAP_DIR/extract/runtime/code-server/lib/node" \
      "$BOOTSTRAP_DIR/extract/main.js" "$staged_config"
  else
    check_config_envelope "$INSTALLED_CONFIG"
    run_check_config "$BOOTSTRAP_DIR/extract/runtime/code-server/lib/node" \
      "$BOOTSTRAP_DIR/extract/main.js" "$INSTALLED_CONFIG"
  fi
  PHASE_CODE=publish
  publish_program
  mv -f "$staged_service" "$SERVICE_PATH" || fail "无法发布受管服务定义。"
  if [ "$operation" = install ]; then
    mv -f "$staged_config" "$INSTALLED_CONFIG" || fail "无法发布环境配置。"
    mv -f "$staged_token" "$INSTALLED_TOKEN" || fail "无法发布环境凭据。"
  fi
  if [ "$HOST_OS" = Darwin ]; then
    : >>"$STDOUT_LOG"
    : >>"$STDERR_LOG"
    chmod 0600 "$STDOUT_LOG" "$STDERR_LOG"
  fi
  start_and_verify
  PHASE_CODE=done
  if [ "$operation" = install ]; then
    echo "KK Studio 环境安装完成：程序/配置已写入；服务已运行。"
  else
    echo "KK Studio 环境更新完成：程序/服务定义已替换；配置与凭据未改动。"
  fi
  echo
  echo "请返回 Studio，确认环境状态为 READY。"
}
cmd_status() {
  ACTION_LABEL=检查
  if [ ! -d "$ENV_ROOT" ]; then
    echo "未安装：找不到该卡片环境的受管目录。" >&2
    return 1
  fi
  if [ ! -e "$SERVICE_PATH" ] && ! { [ "$HOST_OS" = Darwin ] && launchd_loaded; }; then
    echo "未安装：找不到该卡片环境的受管服务。" >&2
    return 1
  fi
  check_managed_paths
  require_ownership
  if [ "$HOST_OS" = Linux ]; then
    local code=0
    systemctl --user --no-pager status "$SERVICE_NAME" || code=$?
    case "$code" in 0 | 3) ;; *) fail "systemctl status failed ($code)" ;; esac
    if command -v journalctl >/dev/null 2>&1; then journalctl --user --no-pager -n 20 -u "$SERVICE_NAME"; fi
    if [ -f "$LAST_OPERATION" ]; then cat "$LAST_OPERATION"; fi
    systemctl --user is-active --quiet "$SERVICE_NAME" || return 3
  else
    local loaded=false path
    if launchctl print "$LAUNCHD_TARGET"; then loaded=true; fi
    for path in "$STDOUT_LOG" "$STDERR_LOG"; do
      if [ -e "$path" ] || [ -L "$path" ]; then check_file "$path" true; tail -n 20 "$path"; fi
    done
    if [ -f "$LAST_OPERATION" ]; then cat "$LAST_OPERATION"; fi
    [ "$loaded" = true ] || return 3
  fi
}
cmd_uninstall() {
  ACTION_LABEL=卸载
  stage '1/3 检查环境与输入' input
  if [ ! -d "$ENV_ROOT" ]; then
    echo "KK Studio 环境未安装受管服务。配置、凭据与用户数据均已保留。"
    return 0
  fi
  check_managed_paths
  local has_service=false
  if [ -e "$SERVICE_PATH" ] || { [ "$HOST_OS" = Darwin ] && launchd_loaded; }; then
    has_service=true
  fi
  # 只有配置/数据而没有服务定义或程序时无事可做；服务已被移除但 lib 仍残留时仍要完成清理。
  if [ "$has_service" = false ] && [ ! -d "$INSTALLED_LIB" ]; then
    echo "KK Studio 环境未安装受管服务。配置、凭据与用户数据均已保留。"
    return 0
  fi
  if [ "$has_service" = true ]; then require_ownership; fi
  [ -d "$INSTALLED_LIB" ] || fail "找不到当前安装程序，无法取得独立管理副本；未删除任何文件。"
  resolve_release_platform
  bootstrap_directory
  prepare_management_copy "$INSTALLED_LIB"
  hold_install_lock '2/3 取得操作锁'
  stop_service_and_wait '3/3 停止并移除服务'
  PHASE_CODE=publish
  rm -f "$SERVICE_PATH" || fail "无法移除受管服务定义。"
  rm -rf "$INSTALLED_LIB" || fail "无法移除受管程序目录。"
  if [ "$HOST_OS" = Linux ]; then systemctl --user daemon-reload >/dev/null 2>&1 || true; fi
  PHASE_CODE=done
  echo "KK Studio 环境已卸载，受管服务和程序已移除。"
  echo
  echo "配置、凭据、技能与用户数据已保留。"
}

main() {
  local action=${1:-help}
  [ $# -gt 0 ] && shift
  case "$action" in
    help | --help | -h)
      [ $# -eq 0 ] || fail "help accepts no options"
      usage
      return 0 ;;
    install | update)
      if [ $# -eq 1 ] && { [ "$1" = --help ] || [ "$1" = -h ]; }; then usage; return 0; fi ;;
    status | uninstall) ;;
    *) fail "unknown command (value not echoed)" ;;
  esac
  [ -n "${HOME:-}" ] || fail "HOME must be set"
  while [ "$HOME" != / ] && [ "${HOME%/}" != "$HOME" ]; do HOME=${HOME%/}; done
  absolute_path "$HOME" HOME
  HOST_OS=$(uname -s)
  case "$HOST_OS" in Linux | Darwin) ;; *) fail "unsupported operating system: $HOST_OS" ;; esac
  local value
  for value in "$VERIFY_TIMEOUT_SECONDS" "$VERIFY_STABLE_SECONDS"; do
    case "$value" in '' | *[!0-9]*) fail "verification windows must be non-negative decimal integers" ;; esac
  done
  VERIFY_TIMEOUT_SECONDS=$((10#$VERIFY_TIMEOUT_SECONDS))
  VERIFY_STABLE_SECONDS=$((10#$VERIFY_STABLE_SECONDS))
  case "$action" in
    install)
      ACTION=install
      cmd_install_arguments "$@" ;;
    update)
      ACTION=update
      cmd_update_arguments "$@" ;;
    status | uninstall)
      [ $# -eq 2 ] && [ "$1" = --environment-id ] || fail "$action requires exactly --environment-id <UUID>"
      validate_environment_id_value "$2"
      ACTION=$action ;;
  esac
  INSTALL_ROOT="$HOME/.kk-studio"
  ENV_ROOT="$INSTALL_ROOT/environments/$ENV_ID"
  INSTALLED_LIB="$ENV_ROOT/lib"
  INSTALLED_CONFIG="$ENV_ROOT/daemon.json"
  INSTALLED_TOKEN="$ENV_ROOT/daemon.token"
  WORK_DIR="$ENV_ROOT/.work"
  LAST_OPERATION="$ENV_ROOT/last-operation.json"
  STDOUT_LOG="$ENV_ROOT/daemon.stdout.log"
  STDERR_LOG="$ENV_ROOT/daemon.stderr.log"
  OPERATION_ID=$(new_operation_id)
  # 在线更新作业传入自己的 operationId，使固定 last-operation.json 能与发起它的控制命令对应；
  # 非法值忽略，人工命令始终使用本地生成的身份。
  if [ -n "${KK_STUDIO_UPDATE_OPERATION_ID:-}" ] &&
    [[ $KK_STUDIO_UPDATE_OPERATION_ID =~ $UUID_PATTERN ]]; then
    OPERATION_ID=$KK_STUDIO_UPDATE_OPERATION_ID
  fi
  if [ -n "$VERSION" ]; then VERSION_JSON=$(printf '"%s"' "$VERSION"); fi
  case "$HOST_OS" in
    Linux)
      SERVICE_NAME="$SERVICE_PREFIX-$ENV_ID.service"
      SERVICE_PATH="$HOME/.config/systemd/user/$SERVICE_NAME"
      SERVICE_MARKER=$UNIT_MARKER ;;
    Darwin)
      LAUNCHD_LABEL="$LAUNCHD_PREFIX.$ENV_ID"
      LAUNCHD_DOMAIN="gui/$(id -u)"
      LAUNCHD_TARGET="$LAUNCHD_DOMAIN/$LAUNCHD_LABEL"
      SERVICE_PATH="$HOME/Library/LaunchAgents/$LAUNCHD_LABEL.plist"
      SERVICE_MARKER=$PLIST_MARKER ;;
  esac
  case "$ACTION" in
    install | update) cmd_install_or_update "$ACTION" ;;
    status) cmd_status ;;
    uninstall) cmd_uninstall ;;
  esac
}
main "$@"
