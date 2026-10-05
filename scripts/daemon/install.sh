#!/usr/bin/env bash
# Official-release-only, per-user installer. Bash 3.2 / GNU and BSD utilities.
# JSON and token semantics belong exclusively to the downloaded JAR preflight.
set -euo pipefail
umask 077

RELEASE_BASE=https://github.com/fengwk/kk-studio/releases
SERVICE_NAME=kk-studio-daemon.service
UNIT_MARKER='# Managed by scripts/daemon/install.sh'
LAUNCHD_LABEL=fun.fengwk.kkstudio.environment-daemon
PLIST_MARKER='<!-- Managed by scripts/daemon/install.sh -->'
CONFIG_FILE=
TOKEN_FILE=
OPT_JAVA_HOME=
SEEN_OPTIONS=
DOWNLOAD_DIR=
BACKUP_DIR=
PUBLISHED=false
TEMP_PATHS=()
VERIFY_TIMEOUT_SECONDS=${DAEMON_VERIFY_TIMEOUT_SECONDS:-30}
VERIFY_STABLE_SECONDS=${DAEMON_VERIFY_STABLE_SECONDS:-3}

usage() {
  cat <<'EOF'
Usage: bash install.sh install --config-file <absolute daemon.json> --token-file <absolute daemon.token> [--java-home <absolute JDK home>]
       bash install.sh status | uninstall | help

Install downloads the latest official GitHub release, validates private sibling
inputs with that JAR, replaces program/config/token and restarts the user service.
Requires JDK 21 (java), curl and a SHA256 tool.
Java discovery order: --java-home, JAVA_HOME_21, JAVA_HOME, then PATH.
Inputs must be named daemon.json and daemon.token in the same private directory.
The release --check-config preflight validates the configuration, its sibling
token and the configured Bash before any installation change.
No interactive prompts. Unknown and duplicate options fail closed.

Fixed layout: ~/.kk-studio (0700), daemon.json and daemon.token (0600),
lib/kk-studio-daemon.jar, logs/ (macOS), backups/ (private unique backups).
Uninstall removes only the owned service and program; config/token/data remain.
After install, return to Studio and confirm the environment reports READY.
No automatic rollback: after publication failures, use the reported backup and
one status or log command for manual recovery, or retry install with corrected inputs.

Linux uses systemd --user; macOS uses the current user's GUI LaunchAgent.
Status exits 0 when the unit is active / LaunchAgent is loaded, 1 if absent,
3 if installed but inactive / not loaded. Loaded is not a running-process check.
DAEMON_VERIFY_TIMEOUT_SECONDS=30 and DAEMON_VERIFY_STABLE_SECONDS=3 control
the startup and stability windows (non-negative integers; 0 checks immediately).
EOF
}

fail() {
  local action=安装 detail=$*
  if [ "$1" = "--uninstall" ]; then
    action=卸载
    shift
    detail=$*
  fi
  echo "${action}失败：${detail}" >&2
  if [ "$action" = 卸载 ]; then
    echo "下一步：处理上述问题后，重新复制并执行卸载命令。" >&2
  else
    echo "下一步：修正上述问题后，重新复制并执行安装命令。" >&2
  fi
  exit 1
}
require_cmd() { command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"; }
absolute_path() {
  case "$1" in /*) ;; *) fail "$2 must be an absolute path" ;; esac
  case "$1" in *[[:cntrl:]]*) fail "$2 must not contain control characters" ;; esac
  # Require an absolute path without dot or empty segments.
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

# Validate only this private directory; shared ancestors are the user's trusted
# layout, not directories for the installer to inspect or harden.
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
  if [ "$private" = true ]; then
    check_metadata "$path" 8#077
  else
    check_metadata "$path" 8#022
  fi
  local mode
  mode=$(file_mode "$path")
  (( (8#$mode & 8#400) != 0 )) || fail "must be readable by its owner: $path"
}
check_input() {
  local path=$1 maximum=$2 size
  check_private_directory "${path%/*}"
  check_file "$path" true
  size=$(wc -c <"$path")
  (( size > 0 && size <= maximum )) || fail "input is empty or exceeds size limit: $path"
}
check_managed_paths() {
  local path
  check_private_directory "$INSTALL_ROOT"
  for path in "$INSTALL_ROOT/lib" "$INSTALL_ROOT/logs" "$INSTALL_ROOT/backups"; do
    check_private_directory "$path"
  done
  for path in "$INSTALLED_CONFIG" "$INSTALLED_TOKEN"; do
    if [ -e "$path" ] || [ -L "$path" ]; then check_file "$path" true; fi
  done
  if [ -e "$INSTALLED_JAR" ] || [ -L "$INSTALLED_JAR" ]; then check_file "$INSTALLED_JAR" false; fi
  for path in "$STDOUT_LOG" "$STDERR_LOG"; do
    if [ -e "$path" ] || [ -L "$path" ]; then check_file "$path" true; fi
  done
}

cleanup() {
  local code=$? path
  trap - EXIT
  for path in ${TEMP_PATHS[@]+"${TEMP_PATHS[@]}"}; do rm -f "$path"; done
  if [ -n "$DOWNLOAD_DIR" ]; then rm -rf "$DOWNLOAD_DIR"; fi
  if [ "$code" -ne 0 ] && [ "$PUBLISHED" = true ]; then
    echo "安装失败：发布后启动或注册未完成。未自动回滚，配置和数据未自动恢复。" >&2
    echo "备份：${BACKUP_DIR:-无（首次安装）}" >&2
    if [ "$HOST_OS" = Linux ]; then
      echo "下一步：运行 journalctl --user -u $SERVICE_NAME -n 50 --no-pager 查看原因。" >&2
    else
      echo "下一步：查看日志 $STDERR_LOG。" >&2
    fi
  fi
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

ownership_conflict() {
  local reason=$1 actual_path=${2:-$SERVICE_PATH}
  echo "安装失败：拒绝操作非受管服务（$reason）。未自动回滚，未知文件未变更。" >&2
  echo "服务定义：$actual_path" >&2
  if [ "$HOST_OS" = Linux ]; then
    echo "下一步：运行 systemctl --user status $SERVICE_NAME 确认归属后再处理。不要伪造所有权标记，不要改动未知文件。" >&2
  else
    echo "下一步：运行 launchctl print $LAUNCHD_TARGET 确认归属后再处理。不要伪造所有权标记，不要改动未知文件。" >&2
  fi
  exit 1
}
require_ownership() {
  if [ "$HOST_OS" = Linux ]; then
    local fragment
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
    local marker
    if [ "$HOST_OS" = Linux ]; then marker=$(head -n 1 "$SERVICE_PATH");
    else marker=$(sed -n '2p' "$SERVICE_PATH"); fi
    [ "$marker" = "$SERVICE_MARKER" ] || ownership_conflict "missing ownership marker"
    local owner mode
    owner=$(file_owner_uid "$SERVICE_PATH") || ownership_conflict "cannot inspect definition owner"
    [ "$owner" = "$(id -u)" ] || ownership_conflict "definition belongs to another user"
    mode=$(file_mode "$SERVICE_PATH") || ownership_conflict "cannot inspect definition permissions"
    case "$mode" in '' | *[!0-7]*) ownership_conflict "cannot interpret definition permissions" ;; esac
    (( (8#$mode & 8#022) == 0 )) || ownership_conflict "definition is group/other writable"
    check_file "$SERVICE_PATH" false
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
stop_launchd() {
  local attempt
  if launchd_loaded; then
    launchctl bootout "$LAUNCHD_TARGET" ||
      fail ${1:+"$1"} "无法停止服务，未替换或删除任何文件。"
    for ((attempt=0; attempt<=VERIFY_TIMEOUT_SECONDS; attempt++)); do
      if ! launchd_loaded; then return 0; fi
      if (( attempt < VERIFY_TIMEOUT_SECONDS )); then sleep 1; fi
    done
    fail ${1:+"$1"} "服务停止后仍处于加载状态，未替换或删除任何文件。"
  fi
}
resolve_java_home() {
  local candidate version java_path
  candidate=$OPT_JAVA_HOME
  if [ -z "$candidate" ]; then candidate=${JAVA_HOME_21:-${JAVA_HOME:-}}; fi
  if [ -z "$candidate" ]; then
    java_path=$(command -v java) || fail "未找到 JDK 21。请设置 JAVA_HOME_21 或 --java-home，或将 java 加入 PATH。"
    candidate=$(cd "$(dirname "$java_path")/.." && pwd -P)
  fi
  absolute_path "$candidate" "Java home"
  # Only the runtime is needed: the released JAR is never compiled here.
  [ -x "$candidate/bin/java" ] || fail "所选 JDK 21 缺少可执行的 bin/java：$candidate/bin/java"
  version=$(env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS "$candidate/bin/java" -version 2>&1) ||
    fail "无法执行所选 Java：$candidate/bin/java"
  case "${version%%$'\n'*}" in *'version "21"'* | *'version "21.'*) ;; *) fail "需要 JDK 21（当前 Java 不是 JDK 21）" ;; esac
  SELECTED_JAVA_HOME=$candidate
}
release_curl() {
  curl -q --fail --silent --show-error --location --proto '=https' --proto-redir '=https' "$@"
}
download_daemon() {
  local effective_url asset checksum expected actual pattern
  require_cmd curl
  effective_url=$(release_curl --output /dev/null --write-out '%{url_effective}' "$RELEASE_BASE/latest") ||
    fail "无法下载：不能解析最新正式版本。请检查网络后重试。"
  case "$effective_url" in "$RELEASE_BASE/tag/"*) RELEASE_TAG=${effective_url#"$RELEASE_BASE/tag/"} ;;
    *) fail "latest release did not resolve to an official immutable release tag" ;; esac
  [[ $RELEASE_TAG =~ ^v[0-9A-Za-z._-]+$ ]] || fail "invalid official release tag"
  DOWNLOAD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/kk-studio-daemon.XXXXXXXX")
  chmod 0700 "$DOWNLOAD_DIR"
  asset="kk-studio-daemon-$RELEASE_TAG.jar"
  DOWNLOADED_JAR="$DOWNLOAD_DIR/$asset"
  checksum="$DOWNLOADED_JAR.sha256"
  release_curl --output "$DOWNLOADED_JAR" "$RELEASE_BASE/download/$RELEASE_TAG/$asset" ||
    fail "无法下载：正式版本 JAR 获取失败。请检查网络后重试。"
  release_curl --output "$checksum" "$RELEASE_BASE/download/$RELEASE_TAG/$asset.sha256" ||
    fail "无法下载：正式版本 SHA256 获取失败。请检查网络后重试。"
  expected=$(cat "$checksum")
  pattern="^([0-9a-fA-F]{64})[[:blank:]]+\\*?${asset//./\\.}$"
  [[ $expected =~ $pattern ]] || fail "校验失败：SHA256 文件格式无效。"
  expected=${BASH_REMATCH[1]}
  if command -v sha256sum >/dev/null 2>&1; then actual=$(sha256sum "$DOWNLOADED_JAR");
  else require_cmd shasum; actual=$(shasum -a 256 "$DOWNLOADED_JAR"); fi
  expected=$(printf '%s' "$expected" | tr 'A-F' 'a-f')
  [ "${actual%% *}" = "$expected" ] || fail "校验失败：SHA256 与正式版本不一致。"
}
# Do not echo arbitrary JAR diagnostics. The release may print one stable, value-free
# `Invalid daemon configuration:` line naming the field path and rule; surface only that
# bounded line so JVM stack traces and raw input values can never be echoed.
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
  done <"$DOWNLOAD_DIR/preflight.log"
  return 1
}

# The staging directory is user-controlled: re-validate regular/private/sibling right
# before the snapshot so a symlink or permission swap during the network download is
# never followed into the published configuration.
recheck_inputs() {
  check_input "$CONFIG_FILE" 1048576
  check_input "$TOKEN_FILE" 16384
  [ "${CONFIG_FILE%/*}" = "${TOKEN_FILE%/*}" ] ||
    fail "staged inputs must remain sibling daemon.json and daemon.token"
}
preflight() {
  local output detail
  [ -s "$DOWNLOADED_JAR" ] || fail "校验失败：下载的 JAR 为空。"
  output=$(env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS \
    "$SELECTED_JAVA_HOME/bin/java" -jar "$DOWNLOADED_JAR" --version 2>&1) ||
    fail "校验失败：下载的 JAR 无法执行。"
  [ "$output" = "kk-studio-daemon ${RELEASE_TAG#v}" ] || fail "daemon --version does not match resolved release tag"
  recheck_inputs
  # Validate exactly the secure snapshot that will be published, not inputs reread later.
  cp "$CONFIG_FILE" "$DOWNLOAD_DIR/daemon.json"
  cp "$TOKEN_FILE" "$DOWNLOAD_DIR/daemon.token"
  chmod 0600 "$DOWNLOAD_DIR/daemon.json" "$DOWNLOAD_DIR/daemon.token"
  CONFIG_FILE="$DOWNLOAD_DIR/daemon.json"
  TOKEN_FILE="$DOWNLOAD_DIR/daemon.token"
  if ! env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS \
    "$SELECTED_JAVA_HOME/bin/java" -jar "$DOWNLOADED_JAR" --check-config "$CONFIG_FILE" >"$DOWNLOAD_DIR/preflight.log" 2>&1; then
    detail=$(preflight_failure_detail) && fail "配置校验失败：$detail 现有安装未改动。"
    fail "配置校验失败：当前版本拒绝 --check-config 或暂存的配置与令牌。请修正输入，或使用支持 --check-config 的版本后重试。现有安装未改动。"
  fi
  output=$(cat "$DOWNLOAD_DIR/preflight.log")
  [ "$output" = "Daemon configuration is valid" ] ||
    fail "unexpected --check-config result; use a release supporting this installer. Existing installation unchanged"
}
stage_file() {
  local source=$1 directory=$2 name=$3 mode=$4
  REGISTERED_TEMP_PATH=$(mktemp "$directory/.$name.tmp.XXXXXXXX")
  TEMP_PATHS+=("$REGISTERED_TEMP_PATH")
  cp "$source" "$REGISTERED_TEMP_PATH"
  chmod "$mode" "$REGISTERED_TEMP_PATH"
}
escape_exec_argument() {
  local value=$1 percent_escape='%%' dollar_escape='$$'
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//%/$percent_escape}
  value=${value//\$/$dollar_escape}
  printf '"%s"' "$value"
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
stage_service() {
  STAGED_SERVICE="$DOWNLOAD_DIR/service"
  local arguments=("$SELECTED_JAVA_HOME/bin/java" -jar "$INSTALLED_JAR" --config "$INSTALLED_CONFIG") argument line=
  if [ "$HOST_OS" = Linux ]; then
    for argument in "${arguments[@]}"; do line="$line $(escape_exec_argument "$argument")"; done
    cat >"$STAGED_SERVICE" <<EOF
$UNIT_MARKER
[Unit]
Description=kk-studio Environment Daemon
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
SyslogIdentifier=kk-studio-daemon

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
      printf '</array>\n<key>RunAtLoad</key><true/>\n<key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>\n<key>ThrottleInterval</key><integer>10</integer>\n<key>Umask</key><integer>63</integer>\n<key>WorkingDirectory</key>\n'
      xml_string "$HOME"
      printf '<key>StandardOutPath</key>\n'; xml_string "$STDOUT_LOG"
      printf '<key>StandardErrorPath</key>\n'; xml_string "$STDERR_LOG"
      printf '</dict></plist>\n'
    } >"$STAGED_SERVICE"
    require_cmd plutil
    plutil -lint "$STAGED_SERVICE" >/dev/null || fail "generated LaunchAgent failed validation; existing installation unchanged"
  fi
}
backup_current() {
  local path present=false
  for path in "$INSTALLED_JAR" "$INSTALLED_CONFIG" "$INSTALLED_TOKEN" "$SERVICE_PATH"; do
    if [ -f "$path" ]; then present=true; fi
  done
  [ "$present" = true ] || return 0
  mkdir -p "$INSTALL_ROOT/backups"
  BACKUP_DIR=$(mktemp -d "$INSTALL_ROOT/backups/install.XXXXXXXX")
  chmod 0700 "$BACKUP_DIR"
  for path in "$INSTALLED_JAR" "$INSTALLED_CONFIG" "$INSTALLED_TOKEN" "$SERVICE_PATH"; do
    if [ -f "$path" ]; then
      cp "$path" "$BACKUP_DIR/${path##*/}"
      chmod 0600 "$BACKUP_DIR/${path##*/}"
    fi
  done
}
verify_running() {
  local attempt stable pid active
  for ((attempt=0; attempt<=VERIFY_TIMEOUT_SECONDS; attempt++)); do
    active=false
    if [ "$HOST_OS" = Linux ]; then
      if systemctl --user is-active --quiet "$SERVICE_NAME"; then active=true; fi
    elif pid=$(launchctl kickstart -p "$LAUNCHD_TARGET" 2>/dev/null); then
      pid=${pid//[[:space:]]/}
      case "$pid" in '' | *[!0-9]*) fail "服务启动失败：LaunchAgent 没有报告数字进程号。" ;; esac
      if kill -0 "$pid" 2>/dev/null; then active=true; fi
    fi
    if [ "$active" = true ]; then
      for ((stable=0; stable<VERIFY_STABLE_SECONDS; stable++)); do
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
cmd_install() {
  local option value
  while [ $# -gt 0 ]; do
    option=$1
    case "$option" in --config-file | --token-file | --java-home) ;; *) fail "unknown option (value not echoed)" ;; esac
    case "$SEEN_OPTIONS" in *"|$option|"*) fail "duplicate option: $option" ;; esac
    SEEN_OPTIONS="$SEEN_OPTIONS|$option|"
    [ $# -ge 2 ] || fail "missing value for $option"
    value=$2
    [ -n "$value" ] || fail "missing value for $option"
    absolute_path "$value" "$option"
    case "$option" in --config-file) CONFIG_FILE=$value ;; --token-file) TOKEN_FILE=$value ;; --java-home) OPT_JAVA_HOME=$value ;; esac
    shift 2
  done
  [ -n "$CONFIG_FILE" ] && [ -n "$TOKEN_FILE" ] || fail "install requires --config-file and --token-file"
  [ "${CONFIG_FILE##*/}" = daemon.json ] && [ "${TOKEN_FILE##*/}" = daemon.token ] &&
    [ "${CONFIG_FILE%/*}" = "${TOKEN_FILE%/*}" ] || fail "inputs must be sibling daemon.json and daemon.token"
  require_manager
  require_ownership
  check_managed_paths
  check_input "$CONFIG_FILE" 1048576
  check_input "$TOKEN_FILE" 16384
  # The released JAR's --check-config probes the configured Bash; the installer must not
  # look up a Bash on PATH so an explicit off-PATH bashExecutable remains supported.
  resolve_java_home
  download_daemon
  preflight
  stage_service
  # All semantic checks (including macOS plist lint) precede target writes and stop.
  check_managed_paths
  require_ownership
  mkdir -p "$INSTALL_ROOT/lib" "${SERVICE_PATH%/*}"
  if [ "$HOST_OS" = Darwin ]; then mkdir -p "$INSTALL_ROOT/logs"; fi
  backup_current
  stage_file "$DOWNLOADED_JAR" "$INSTALL_ROOT/lib" kk-studio-daemon.jar 0644; STAGED_JAR=$REGISTERED_TEMP_PATH
  stage_file "$CONFIG_FILE" "$INSTALL_ROOT" daemon.json 0600; STAGED_CONFIG=$REGISTERED_TEMP_PATH
  stage_file "$TOKEN_FILE" "$INSTALL_ROOT" daemon.token 0600; STAGED_TOKEN=$REGISTERED_TEMP_PATH
  stage_file "$STAGED_SERVICE" "${SERVICE_PATH%/*}" service 0644; STAGED_SERVICE=$REGISTERED_TEMP_PATH
  if [ -f "$SERVICE_PATH" ]; then
    if [ "$HOST_OS" = Linux ]; then systemctl --user stop "$SERVICE_NAME";
    else stop_launchd; fi
  fi
  # From the first rename onward a failure must report manual recovery, even partial publication.
  PUBLISHED=true
  mv -f "$STAGED_JAR" "$INSTALLED_JAR"
  mv -f "$STAGED_CONFIG" "$INSTALLED_CONFIG"
  mv -f "$STAGED_TOKEN" "$INSTALLED_TOKEN"
  mv -f "$STAGED_SERVICE" "$SERVICE_PATH"
  if [ "$HOST_OS" = Linux ]; then
    systemctl --user daemon-reload
    systemctl --user enable "$SERVICE_NAME"
    systemctl --user restart "$SERVICE_NAME"
  else launchctl bootstrap "$LAUNCHD_DOMAIN" "$SERVICE_PATH"; fi
  verify_running
  echo "KK Studio 环境安装完成，服务已启动。"
  echo
  echo "请返回 Studio，确认环境状态为 READY。"
  if [ -n "$BACKUP_DIR" ]; then echo "原安装已备份：$BACKUP_DIR"; fi
}
cmd_status() {
  require_manager
  require_ownership
  [ -f "$SERVICE_PATH" ] || { echo "未安装：找不到受管服务定义 $SERVICE_PATH" >&2; return 1; }
  check_managed_paths
  if [ "$HOST_OS" = Linux ]; then
    local code=0
    systemctl --user --no-pager status "$SERVICE_NAME" || code=$?
    case "$code" in 0 | 3) ;; *) fail "systemctl status failed ($code)" ;; esac
    if command -v journalctl >/dev/null 2>&1; then journalctl --user --no-pager -n 20 -u "$SERVICE_NAME"; fi
    systemctl --user is-active --quiet "$SERVICE_NAME" || return 3
  else
    local loaded=false path
    if launchctl print "$LAUNCHD_TARGET"; then loaded=true; fi
    for path in "$STDOUT_LOG" "$STDERR_LOG"; do
      if [ -e "$path" ] || [ -L "$path" ]; then check_file "$path" true; tail -n 20 "$path"; fi
    done
    [ "$loaded" = true ] || return 3
  fi
}
cmd_uninstall() {
  require_manager
  require_ownership
  check_managed_paths
  if [ ! -f "$SERVICE_PATH" ]; then
    echo "KK Studio 环境未安装受管服务。配置、数据和备份均已保留。"
    return 0
  fi
  if [ "$HOST_OS" = Linux ]; then
    systemctl --user disable --now "$SERVICE_NAME" || fail --uninstall "无法停止或禁用服务，未删除任何文件"
  else stop_launchd --uninstall; fi
  rm -f "$SERVICE_PATH" "$INSTALLED_JAR"
  if [ "$HOST_OS" = Linux ]; then systemctl --user daemon-reload; fi
  echo "KK Studio 环境已卸载，受管服务和程序已移除。"
  echo
  echo "配置、数据和备份已保留。"
}
main() {
  local action=${1:-help}
  if [ $# -gt 0 ]; then shift; fi
  case "$action" in
    help | --help | -h) [ $# -eq 0 ] || fail "help accepts no options"; usage; return ;;
    install)
      if [ $# -eq 1 ] && { [ "$1" = --help ] || [ "$1" = -h ]; }; then usage; return; fi ;;
    status | uninstall) [ $# -eq 0 ] || fail "$action accepts no options" ;;
    *) fail "unknown command (value not echoed)" ;;
  esac
  [ -n "${HOME:-}" ] || fail "HOME must be set"
  while [ "$HOME" != / ] && [ "${HOME%/}" != "$HOME" ]; do HOME=${HOME%/}; done
  absolute_path "$HOME" HOME
  INSTALL_ROOT="$HOME/.kk-studio"
  INSTALLED_JAR="$INSTALL_ROOT/lib/kk-studio-daemon.jar"
  INSTALLED_CONFIG="$INSTALL_ROOT/daemon.json"
  INSTALLED_TOKEN="$INSTALL_ROOT/daemon.token"
  STDOUT_LOG="$INSTALL_ROOT/logs/environment-daemon.stdout.log"
  STDERR_LOG="$INSTALL_ROOT/logs/environment-daemon.stderr.log"
  HOST_OS=$(uname -s)
  case "$HOST_OS" in
    Linux) SERVICE_PATH="$HOME/.config/systemd/user/$SERVICE_NAME"; SERVICE_MARKER=$UNIT_MARKER ;;
    Darwin)
      LAUNCHD_DOMAIN="gui/$(id -u)"
      LAUNCHD_TARGET="$LAUNCHD_DOMAIN/$LAUNCHD_LABEL"
      SERVICE_PATH="$HOME/Library/LaunchAgents/$LAUNCHD_LABEL.plist"; SERVICE_MARKER=$PLIST_MARKER ;;
    *) fail "unsupported operating system: $HOST_OS" ;;
  esac
  local value
  for value in "$VERIFY_TIMEOUT_SECONDS" "$VERIFY_STABLE_SECONDS"; do
    case "$value" in '' | *[!0-9]*) fail "verification windows must be non-negative decimal integers" ;; esac
  done
  VERIFY_TIMEOUT_SECONDS=$((10#$VERIFY_TIMEOUT_SECONDS))
  VERIFY_STABLE_SECONDS=$((10#$VERIFY_STABLE_SECONDS))
  case "$action" in install) cmd_install "$@" ;; status) cmd_status ;; uninstall) cmd_uninstall ;; esac
}
main "$@"
