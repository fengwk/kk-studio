#!/usr/bin/env bash
# scripts/daemon/install.sh — 从当前源码 checkout 安装/升级 Environment Daemon
#
# 单一职责：把 `harness/daemon` 构建出的 shaded JAR 与一个由本脚本拥有的用户服务
# 安装到当前用户，并提供状态查询与卸载。Linux 使用 `systemd --user`；macOS 使用
# 当前用户 GUI 域的 LaunchAgent。它不下载发布物、不解析 token 内容、不生成
# `~/.local/bin` wrapper，也不写任何 daemon 环境变量文件：服务定义是配置的唯一载体，
# 凭证只以 owner-only 文件路径出现在 `--registration-token-file`。
#
# 用法：
#   scripts/daemon/install.sh install --gateway-uri <ws://...|wss://...> \
#     --registration-token-file <absolute-path> [options]
#   scripts/daemon/install.sh upgrade
#   scripts/daemon/install.sh status
#   scripts/daemon/install.sh uninstall
#   scripts/daemon/install.sh --help
#
# 前置条件：Linux + 可用的 `systemctl --user`，或 macOS + 可用的 `gui/$(id -u)`
# LaunchAgent 域；PATH 上的 Maven、JDK 21。其它系统在触碰受管路径之前失败。
#
# 安全边界：所有取值先校验再构建；未知/重复选项、控制字符、非 ws/wss scheme、相对路径
# 与不合规 token 文件都在触碰任何安装路径之前失败。token 文件只被 stat 校验，内容既不
# 读取也不打印，也不进入 argv 或环境变量。

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
# 仓库根解析：KK_STUDIO_REPO_ROOT 优先；否则从脚本位置向上寻找 worktree 根（.git 文件或目录），
# 不依赖本脚本在仓库中的深度。
if [ -n "${KK_STUDIO_REPO_ROOT:-}" ]; then
  REPO_ROOT=$(cd "$KK_STUDIO_REPO_ROOT" && pwd)
else
  REPO_ROOT=$SCRIPT_DIR
  while [ "$REPO_ROOT" != "/" ] && [ ! -e "$REPO_ROOT/.git" ]; do
    REPO_ROOT=$(dirname "$REPO_ROOT")
  done
  if [ ! -e "$REPO_ROOT/.git" ]; then
    echo "ERROR: cannot locate the kk-studio repository root; set KK_STUDIO_REPO_ROOT" >&2
    exit 1
  fi
fi

BUILT_JAR="$REPO_ROOT/harness/daemon/target/kk-studio-daemon.jar"

# HOME 参与所有受管路径的推导：必须先证明它是绝对路径且不含控制字符，才允许它进入 unit。
if [ -z "${HOME:-}" ]; then
  echo "ERROR: HOME must be set to resolve the managed installation paths" >&2
  exit 1
fi
case "$HOME" in
  *[[:cntrl:]]*) echo "ERROR: HOME must not contain control characters" >&2; exit 1 ;;
esac
case "$HOME" in
  /*) ;;
  *) echo "ERROR: HOME must be an absolute path: $HOME" >&2; exit 1 ;;
esac
case "$REPO_ROOT" in
  *[[:cntrl:]]*) echo "ERROR: repository root must not contain control characters" >&2; exit 1 ;;
esac

SERVICE_NAME=kk-studio-daemon.service
# 单元标记同时是所有权凭证：卸载只删除带该标记的单元，缺失时一律拒绝。
UNIT_MARKER='# Managed by scripts/daemon/install.sh'
UNIT_DIR="$HOME/.config/systemd/user"
UNIT_PATH="$UNIT_DIR/$SERVICE_NAME"
INSTALL_ROOT="$HOME/.local/lib/kk-studio"
INSTALLED_JAR="$INSTALL_ROOT/kk-studio-daemon.jar"

# macOS 与 Linux 共用 JAR，但服务定义是当前用户的 LaunchAgent。标记必须是 XML 注释，
# 并且紧跟 XML 声明，这样所有权检查不需要解析 plist 正文。
LAUNCHD_LABEL=fun.fengwk.kkstudio.environment-daemon
PLIST_MARKER='<!-- Managed by scripts/daemon/install.sh -->'
PLIST_DIR="$HOME/Library/LaunchAgents"
PLIST_PATH="$PLIST_DIR/$LAUNCHD_LABEL.plist"
LAUNCHD_LOG_DIR="$HOME/Library/Logs/kk-studio"
LAUNCHD_STDOUT_LOG="$LAUNCHD_LOG_DIR/environment-daemon.stdout.log"
LAUNCHD_STDERR_LOG="$LAUNCHD_LOG_DIR/environment-daemon.stderr.log"
HOST_OS=$(uname -s)
LAUNCHD_DOMAIN=

JOURNAL_TAIL_LINES=20
# 重启后的验证窗口（秒）：先在这个窗口内等到 active，再要求服务连续保持 active 稳定窗口。
# 0 表示不做等待/不要求稳定窗口，只做一次立即检查，供不等待的自动化使用。
VERIFY_TIMEOUT_SECONDS=${DAEMON_VERIFY_TIMEOUT_SECONDS:-30}
VERIFY_STABLE_SECONDS=${DAEMON_VERIFY_STABLE_SECONDS:-3}

# 顶层选项取值；空字符串表示未给出。
GATEWAY_URI=
TOKEN_FILE=
OPT_JAVA_HOME=
DATA_DIR=
NOTE=
BASH_EXECUTABLE=
LSP_BRIDGE_COMMAND=
JAVAP_EXECUTABLE=
SEEN_OPTIONS=

TEMP_PATHS=()

usage() {
  cat <<'EOF'
Usage: scripts/daemon/install.sh <command> [options]

Install, upgrade, inspect or remove the kk-studio Environment Daemon as a
per-user service built from this source checkout. Linux uses `systemd --user`;
macOS uses a LaunchAgent in the current user's GUI domain.

Commands:
  install    Build this checkout, install/update the managed JAR and service
             definition, start it and verify the service stays running.
  upgrade    Require an existing managed service, rebuild this checkout and
             replace only the JAR, then restart and verify. Configuration
             already stored in the service is reused; no daemon option is
             accepted and nothing has to be repeated.
  status     Print non-interactive service status and a short log tail.
             Exit 0 when running, 1 when not installed, 3 when not running.
  uninstall  Stop the service, remove only the managed service definition and
             the installed JAR. The registration token file, the data directory
             and macOS launchd logs are preserved.
  --help     Print this help and exit. `install --help` prints it only when it
             is the complete install argument list; other combinations fail.

Install options:
  --gateway-uri <uri>                 Required. Environment server WebSocket
                                      gateway; scheme must be ws or wss.
  --registration-token-file <path>    Required. Absolute owner-only file that
                                      holds the registration token. Its content
                                      is never read or printed by this script.
  --java-home <dir>                   Optional. Absolute JDK 21 home used for
                                      the build and the unit. Default order:
                                      JAVA_HOME_21, JAVA_HOME, then PATH.
  --data-dir <path>                   Optional. Absolute daemon data directory
                                      (default: $HOME/.kk-studio).
  --note <text>                       Optional. Single-line trusted note, at
                                      most 512 characters.
  --bash-executable <value>           Optional. bash for process.exec
                                      (default: the resolved bash path).
  --lsp-bridge-command <value>        Optional. LSP bridge command; omitted by
                                      default, which disables LSP queries.
  --javap-executable <value>          Optional. javap for class decompilation
                                      (default: <selected JDK>/bin/javap).

Unknown or duplicated options fail closed. Values must not contain control
characters; tokens, gateway values and notes never reach the Maven build.

Environment:
  DAEMON_VERIFY_TIMEOUT_SECONDS=30   Seconds to wait for the restarted service
                                     to report active (0 = one immediate check).
  DAEMON_VERIFY_STABLE_SECONDS=3     Seconds the service must then stay
                                     continuously active (0 = one immediate
                                     check). Both must be non-negative integers.

Managed layout:
  $HOME/.local/lib/kk-studio/kk-studio-daemon.jar     mode 0644
  Linux:  $HOME/.config/systemd/user/kk-studio-daemon.service mode 0644
  macOS:  $HOME/Library/LaunchAgents/fun.fengwk.kkstudio.environment-daemon.plist
          mode 0644
          logs: $HOME/Library/Logs/kk-studio/environment-daemon.{stdout,stderr}.log

No $HOME/.local/bin wrapper and no daemon environment/config file is created.
EOF
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

step() {
  echo "==> $*"
}

cleanup() {
  local path
  for path in ${TEMP_PATHS[@]+"${TEMP_PATHS[@]}"}; do
    rm -f "$path"
  done
}

# 只有 Linux 与 Darwin 有受管安装路径。探测必须发生在任何 mkdir/替换之前，否则不受支持的
# 系统会留下一个永远不会被本脚本启动的 JAR。
require_supported_host() {
  case "$HOST_OS" in
    Linux | Darwin) ;;
    *) fail "unsupported operating system: $HOST_OS (only Linux and macOS are supported)" ;;
  esac
}

trap cleanup EXIT

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    fail "missing command: $1"
  fi
}

# systemd 只用于用户实例：不可用的 `systemctl --user` 必须在改动任何路径之前失败，否则
# 会留下一个永远不会被 reload 的单元文件。
require_systemd_user() {
  require_cmd systemctl
  if ! systemctl --user --no-pager show-environment >/dev/null 2>&1; then
    fail "systemctl --user is unavailable or unusable; this installer requires a user systemd instance"
  fi
}

# macOS 只操作当前用户的 GUI 域。没有该域时 bootstrap 无法把服务交给用户会话，因此必须在
# 写 plist 或 JAR 之前失败。不调用 `launchctl enable`：用户持久化的 disabled 状态要保留。
require_launchd_gui_domain() {
  require_cmd launchctl
  LAUNCHD_DOMAIN="gui/$(id -u)"
  if ! launchctl print "$LAUNCHD_DOMAIN" >/dev/null 2>&1; then
    fail "launchctl GUI domain $LAUNCHD_DOMAIN is unavailable; this installer requires a per-user macOS GUI session"
  fi
}

launchd_target() {
  printf '%s/%s' "$LAUNCHD_DOMAIN" "$LAUNCHD_LABEL"
}

# `launchctl print` 的文本格式不是稳定契约，因此只把它的退出状态当作 loaded/unloaded。
launchd_service_is_loaded() {
  launchctl print "$(launchd_target)" >/dev/null 2>&1
}

# 属主与权限查询只返回单个数值；失败时由调用方按「无法读取」处理，不回显文件内容。
file_owner_uid() {
  stat -c '%u' "$1" 2>/dev/null || stat -f '%u' "$1" 2>/dev/null
}

file_mode() {
  stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1" 2>/dev/null
}

# 控制字符（含换行）在 unit 文件与 argv 里都没有安全表达，越早拒绝越好。
reject_control_characters() {
  local value=$1 name=$2
  case "$value" in
    *[[:cntrl:]]*) fail "$name must not contain control characters" ;;
  esac
}

# 选项取值最终都会成为 Daemon 的 argv；Daemon 拒绝空值与前缀 `--` 的值，因此在这里先失败，
# 不把必然启动失败的配置写进单元。
require_option_value() {
  local value=$1 name=$2
  if [ -z "$value" ]; then
    fail "missing value for $name"
  fi
  case "$value" in
    --*) fail "missing value for $name" ;;
  esac
  reject_control_characters "$value" "$name"
}

require_absolute_path() {
  local value=$1 name=$2
  case "$value" in
    /*) ;;
    *) fail "$name must be an absolute path" ;;
  esac
}

# 验证窗口直接进入 bash 算术表达式：非十进制非负整数会让算术求值错误，必须在开始工作前拒绝。
require_nonnegative_integer() {
  local value=$1 name=$2
  case "$value" in
    '' | *[!0-9]*) fail "$name must be a non-negative decimal integer: $value" ;;
  esac
}

resolve_verification_windows() {
  require_nonnegative_integer "$VERIFY_TIMEOUT_SECONDS" DAEMON_VERIFY_TIMEOUT_SECONDS
  require_nonnegative_integer "$VERIFY_STABLE_SECONDS" DAEMON_VERIFY_STABLE_SECONDS
}

option_seen() {
  case "$SEEN_OPTIONS" in
    *"|$1|"*) return 0 ;;
  esac
  return 1
}

mark_option_seen() {
  SEEN_OPTIONS="$SEEN_OPTIONS|$1|"
}

parse_install_options() {
  local option value
  while [ $# -gt 0 ]; do
    option=$1
    case "$option" in
      --gateway-uri | --registration-token-file | --java-home | --data-dir | --note | \
        --bash-executable | --lsp-bridge-command | --javap-executable) ;;
      *) fail "unknown option: $option" ;;
    esac
    if option_seen "$option"; then
      fail "duplicate option: $option"
    fi
    mark_option_seen "$option"
    if [ $# -lt 2 ]; then
      fail "missing value for $option"
    fi
    value=$2
    shift 2
    require_option_value "$value" "$option"
    case "$option" in
      --gateway-uri) GATEWAY_URI=$value ;;
      --registration-token-file) TOKEN_FILE=$value ;;
      --java-home) OPT_JAVA_HOME=$value ;;
      --data-dir) DATA_DIR=$value ;;
      --note) NOTE=$value ;;
      --bash-executable) BASH_EXECUTABLE=$value ;;
      --lsp-bridge-command) LSP_BRIDGE_COMMAND=$value ;;
      --javap-executable) JAVAP_EXECUTABLE=$value ;;
    esac
  done
}

validate_gateway_uri() {
  local name=--gateway-uri
  case "$GATEWAY_URI" in
    ws://* | wss://*) ;;
    *) fail "$name must use the ws or wss scheme: $GATEWAY_URI" ;;
  esac
  if [[ ! $GATEWAY_URI =~ ^wss?://[^[:space:]/?#]+([/?#][^[:space:]]*)?$ ]]; then
    fail "$name must be an absolute ws/wss URL with a host: $GATEWAY_URI"
  fi
}

# token 文件是凭证的唯一载体：这里只做 stat 级校验，绝不读取或输出其内容。
validate_token_file() {
  local name=--registration-token-file owner mode
  require_absolute_path "$TOKEN_FILE" "$name"
  if [ -L "$TOKEN_FILE" ]; then
    fail "$name must not be a symbolic link"
  fi
  if [ ! -f "$TOKEN_FILE" ]; then
    fail "$name must be an existing regular file"
  fi
  if [ ! -s "$TOKEN_FILE" ]; then
    fail "$name must not be empty"
  fi
  owner=$(file_owner_uid "$TOKEN_FILE") || fail "cannot read the $name owner"
  if [ "$owner" != "$(id -u)" ]; then
    fail "$name must be owned by the current user"
  fi
  mode=$(file_mode "$TOKEN_FILE") || fail "cannot read the $name permissions"
  case "$mode" in
    *[!0-7]*) fail "cannot interpret the $name permissions" ;;
  esac
  if (( (8#$mode & 8#077) != 0 )); then
    fail "$name must not grant group or other permissions"
  fi
  if (( (8#$mode & 8#400) == 0 )); then
    fail "$name must be readable by its owner"
  fi
}

validate_note() {
  if [ -z "$NOTE" ]; then
    fail "--note must not be blank"
  fi
  case "$NOTE" in
    ' '* | *' ') fail "--note must not have surrounding whitespace" ;;
  esac
  if (( ${#NOTE} > 512 )); then
    fail "--note must not exceed 512 characters"
  fi
}

resolve_data_dir() {
  if [ -z "$DATA_DIR" ]; then
    DATA_DIR="$HOME/.kk-studio"
  fi
  require_absolute_path "$DATA_DIR" --data-dir
}

resolve_bash_executable() {
  if [ -z "$BASH_EXECUTABLE" ]; then
    BASH_EXECUTABLE=$(command -v bash 2>/dev/null || true)
    if [ -z "$BASH_EXECUTABLE" ]; then
      fail "cannot resolve the bash executable; pass --bash-executable"
    fi
  fi
}

# 发布物以 JDK 21 为目标：更低版本的 JDK 会让 Daemon 以 UnsupportedClassVersionError 退出，
# 所以构建与单元都必须落在同一个 21 上。构建还需要 javac，因此这里同时要求它就是 JDK。
assert_jdk21() {
  local java_home=$1 version
  if [ ! -x "$java_home/bin/java" ]; then
    fail "JDK 21 home must contain an executable bin/java: $java_home"
  fi
  if [ ! -x "$java_home/bin/javac" ]; then
    fail "JDK 21 home must contain an executable bin/javac: $java_home"
  fi
  version=$(env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS \
    "$java_home/bin/java" -version 2>&1) || fail "cannot execute $java_home/bin/java"
  version=${version%%$'\n'*}
  case "$version" in
    *'version "21"'* | *'version "21.'*) ;;
    *) fail "JDK 21 is required (found: $version)" ;;
  esac
}

resolve_java_home() {
  local candidate
  if [ -n "$OPT_JAVA_HOME" ]; then
    require_absolute_path "$OPT_JAVA_HOME" --java-home
    assert_jdk21 "$OPT_JAVA_HOME"
    printf '%s\n' "$OPT_JAVA_HOME"
    return 0
  fi
  # 环境变量提供的 JDK 路径同样要进入单元，因此和显式选项一样必须是绝对路径且无控制字符。
  for candidate in "${JAVA_HOME_21:-}" "${JAVA_HOME:-}"; do
    if [ -n "$candidate" ]; then
      reject_control_characters "$candidate" "JAVA_HOME_21/JAVA_HOME"
      require_absolute_path "$candidate" "JAVA_HOME_21/JAVA_HOME"
      assert_jdk21 "$candidate"
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  local java_path
  java_path=$(command -v java 2>/dev/null || true)
  if [ -n "$java_path" ]; then
    candidate=$(cd "$(dirname "$java_path")/.." && pwd -P)
    assert_jdk21 "$candidate"
    printf '%s\n' "$candidate"
    return 0
  fi
  fail "JDK 21 not found: set JAVA_HOME_21 or JAVA_HOME, pass --java-home, or put java on PATH"
}

resolve_javap_executable() {
  if [ -n "$JAVAP_EXECUTABLE" ]; then
    return 0
  fi
  if [ ! -x "$SELECTED_JAVA_HOME/bin/javap" ]; then
    fail "the selected JDK has no executable bin/javap: $SELECTED_JAVA_HOME"
  fi
  JAVAP_EXECUTABLE="$SELECTED_JAVA_HOME/bin/javap"
}

# 构建：显式 JAVA_HOME 走 Maven，其余一切（gateway、token 路径、note）都不进入构建，
# 因此 Maven 既看不到数据面配置，也不进入 Daemon 的连接参数。
build_daemon() {
  require_cmd mvn
  step "Cleaning and packaging harness/daemon (JAVA_HOME=$SELECTED_JAVA_HOME)"
  (
    cd "$REPO_ROOT"
    env JAVA_HOME="$SELECTED_JAVA_HOME" mvn -B -ntp -pl harness/daemon -am clean package
  )
}

# 安装前必须证明新产物真的可执行：`java -jar <jar> --version` 成功是唯一能同时证明入口类与
# 内嵌依赖都可用的检查。
verify_built_jar() {
  local output
  if [ ! -f "$BUILT_JAR" ] || [ ! -s "$BUILT_JAR" ]; then
    fail "built daemon JAR not found or empty: $BUILT_JAR"
  fi
  if ! output=$(env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS \
    "$SELECTED_JAVA_HOME/bin/java" -jar "$BUILT_JAR" --version 2>&1); then
    fail "built daemon JAR is not executable: 'java -jar $BUILT_JAR --version' failed: $output"
  fi
  case "$output" in
    'kk-studio-daemon '*) ;;
    *) fail "unexpected daemon --version output: $output" ;;
  esac
}

# 同目录临时文件 + rename：替换必须与目标处于同一文件系统，中途失败也不留半份产物。
# 路径必须在当前 shell 登记。命令替换会丢弃数组变更，退出清理就看不到这次暂存。
register_temp_path() {
  local directory=$1 name=$2
  REGISTERED_TEMP_PATH="$directory/.$name.tmp.$$"
  TEMP_PATHS+=("$REGISTERED_TEMP_PATH")
}

stage_jar() {
  mkdir -p "$INSTALL_ROOT"
  register_temp_path "$INSTALL_ROOT" kk-studio-daemon.jar
  STAGED_JAR=$REGISTERED_TEMP_PATH
  # 源路径由仓库根推导，不是用户选项；不用 GNU 专用的 `--` 操作数，BSD cp 不接受它。
  cp "$BUILT_JAR" "$STAGED_JAR"
  chmod 0644 "$STAGED_JAR"
}

publish_staged_jar() {
  mv -f "$STAGED_JAR" "$INSTALLED_JAR"
}

# systemd 会先做 `%` 说明符与 `$` 变量展开，再做引号解析；这里把每个 argv 都包成带引号的
# 单词，并对反斜杠、双引号、`%`、`$` 逐层转义，因此空格与这些字符都不会被二次解释。
# 换行与控制字符在解析选项时已经拒绝。
escape_exec_argument() {
  local value=$1 percent_escape='%%' dollar_escape='$$'
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//%/$percent_escape}
  value=${value//\$/$dollar_escape}
  printf '"%s"' "$value"
}

build_exec_start() {
  local arguments=("$SELECTED_JAVA_HOME/bin/java" -jar "$INSTALLED_JAR")
  arguments+=(--gateway-uri "$GATEWAY_URI")
  arguments+=(--registration-token-file "$TOKEN_FILE")
  arguments+=(--data-dir "$DATA_DIR")
  if [ -n "$NOTE" ]; then
    arguments+=(--note "$NOTE")
  fi
  arguments+=(--bash-executable "$BASH_EXECUTABLE")
  if [ -n "$LSP_BRIDGE_COMMAND" ]; then
    arguments+=(--lsp-bridge-command "$LSP_BRIDGE_COMMAND")
  fi
  arguments+=(--javap-executable "$JAVAP_EXECUTABLE")

  local argument line=
  for argument in "${arguments[@]}"; do
    line="$line $(escape_exec_argument "$argument")"
  done
  printf '%s\n' "${line# }"
}

# 加固与生命周期设置沿用宿主既有单元：network-online 排序、start limit、home 工作目录、
# 失败重启、停止超时、mixed kill、owner-only umask、禁止提权与 default target。
write_unit_file() {
  local temp exec_start
  mkdir -p "$UNIT_DIR"
  register_temp_path "$UNIT_DIR" "$SERVICE_NAME"
  temp=$REGISTERED_TEMP_PATH
  exec_start=$(build_exec_start)
  cat >"$temp" <<EOF
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
ExecStart=$exec_start
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
  chmod 0644 "$temp"
  mv -f "$temp" "$UNIT_PATH"
}

# 只有带标记的单元才归本脚本所有；缺失标记说明它是人工或别处写入的配置。
require_managed_unit() {
  local first_line
  if [ ! -f "$UNIT_PATH" ]; then
    fail "no managed installation found at $UNIT_PATH"
  fi
  # `head -n 1` 在 GNU 与 BSD 上都可用；`--` 在 BSD head 上会被当成文件名。
  first_line=$(head -n 1 "$UNIT_PATH")
  if [ "$first_line" != "$UNIT_MARKER" ]; then
    fail "refusing to touch unmanaged unit $UNIT_PATH (missing marker: $UNIT_MARKER)"
  fi
}

# XML 文本节点的五个预定义实体都要转义。属性不用，因此这里只处理元素文本。
# 必须一次扫描：先替换 `&` 再替换 `<` 会把刚写出的 `&lt;` 再转成 `&amp;lt;`。
xml_escape() {
  local value=$1 index=0 escaped= character
  while [ "$index" -lt "${#value}" ]; do
    character=${value:index:1}
    case "$character" in
      '&') escaped="${escaped}&amp;" ;;
      '<') escaped="${escaped}&lt;" ;;
      '>') escaped="${escaped}&gt;" ;;
      "'") escaped="${escaped}&apos;" ;;
      '"') escaped="${escaped}&quot;" ;;
      *) escaped="${escaped}${character}" ;;
    esac
    index=$((index + 1))
  done
  printf '%s' "$escaped"
}

append_xml_string() {
  printf '  <string>%s</string>\n' "$(xml_escape "$1")" >>"$2"
}

append_program_arguments() {
  local destination=$1
  local arguments=("$SELECTED_JAVA_HOME/bin/java" -jar "$INSTALLED_JAR")
  arguments+=(--gateway-uri "$GATEWAY_URI")
  arguments+=(--registration-token-file "$TOKEN_FILE")
  arguments+=(--data-dir "$DATA_DIR")
  if [ -n "$NOTE" ]; then
    arguments+=(--note "$NOTE")
  fi
  arguments+=(--bash-executable "$BASH_EXECUTABLE")
  if [ -n "$LSP_BRIDGE_COMMAND" ]; then
    arguments+=(--lsp-bridge-command "$LSP_BRIDGE_COMMAND")
  fi
  arguments+=(--javap-executable "$JAVAP_EXECUTABLE")

  local argument
  printf '  <array>\n' >>"$destination"
  for argument in "${arguments[@]}"; do
    printf '    <string>%s</string>\n' "$(xml_escape "$argument")" >>"$destination"
  done
  printf '  </array>\n' >>"$destination"
}

# plist 直接执行绝对路径的 java，不经过 shell，也不读取环境变量。生命周期键固定：登录即启动、
# 非成功退出才重启、10 秒节流、owner-only umask、工作目录为 HOME。逐行写入，避免把带换行的
# 命令替换嵌进 heredoc 后破坏 XML。
# 只写并校验临时 plist。通过之前不得改名覆盖现有受管文件。
stage_plist_file() {
  local temp
  mkdir -p "$PLIST_DIR" "$LAUNCHD_LOG_DIR"
  register_temp_path "$PLIST_DIR" "$LAUNCHD_LABEL.plist"
  temp=$REGISTERED_TEMP_PATH
  STAGED_PLIST=$temp
  cat >"$temp" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
$PLIST_MARKER
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
EOF
  append_xml_string "$LAUNCHD_LABEL" "$temp"
  cat >>"$temp" <<'EOF'
  <key>ProgramArguments</key>
EOF
  append_program_arguments "$temp"
  cat >>"$temp" <<'EOF'
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <dict>
    <key>SuccessfulExit</key>
    <false/>
  </dict>
  <key>ThrottleInterval</key>
  <integer>10</integer>
  <key>Umask</key>
  <integer>63</integer>
  <key>WorkingDirectory</key>
EOF
  append_xml_string "$HOME" "$temp"
  printf '  <key>StandardOutPath</key>\n' >>"$temp"
  append_xml_string "$LAUNCHD_STDOUT_LOG" "$temp"
  printf '  <key>StandardErrorPath</key>\n' >>"$temp"
  append_xml_string "$LAUNCHD_STDERR_LOG" "$temp"
  printf '</dict>\n</plist>\n' >>"$temp"
  # 非法 XML 不得替换已有受管 plist：先 lint 临时文件，通过后才原子改名。
  require_cmd plutil
  if ! plutil -lint "$temp" >/dev/null; then
    fail "generated LaunchAgent plist failed validation; the existing configuration was not replaced"
  fi
  chmod 0644 "$temp"
}

publish_staged_plist() {
  mv -f "$STAGED_PLIST" "$PLIST_PATH"
}

# 所有权只看声明之后的那一行精确注释。缺少标记的 plist 可能是用户自己的 LaunchAgent。
require_managed_plist() {
  local marker_line
  if [ ! -f "$PLIST_PATH" ]; then
    fail "no managed installation found at $PLIST_PATH"
  fi
  marker_line=$(sed -n '2p' "$PLIST_PATH")
  if [ "$marker_line" != "$PLIST_MARKER" ]; then
    fail "refusing to touch unmanaged plist $PLIST_PATH (missing marker: $PLIST_MARKER)"
  fi
}

# bootout 返回后任务仍可能短暂处于 loaded。只看 print 的退出状态，不解析它的文本；
# 在它变为 unloaded 之前不得替换 plist 或再次 bootstrap。
wait_until_launchd_unloaded() {
  local attempt
  for (( attempt = 0; attempt <= VERIFY_TIMEOUT_SECONDS; attempt++ )); do
    if ! launchd_service_is_loaded; then
      return 0
    fi
    if (( attempt < VERIFY_TIMEOUT_SECONDS )); then
      sleep 1
    fi
  done
  fail "$LAUNCHD_LABEL is still loaded after bootout; the existing configuration was not replaced"
}

# 替换已加载服务的 plist 之前必须先 bootout，否则 launchd 继续使用旧定义。
bootout_loaded_service() {
  if launchd_service_is_loaded; then
    if ! launchctl bootout "$(launchd_target)"; then
      fail "cannot bootout $LAUNCHD_LABEL; the existing configuration was not replaced"
    fi
    wait_until_launchd_unloaded
  fi
}

# 启动成功只由进程存活性证明：`kickstart -p` 给出 PID，稳定窗口内 `kill -0` 必须一直成功。
# 不解析 `launchctl print` 的文本，缺失或畸形 PID 一律失败。
verify_launchd_process() {
  local attempt stable pid
  for (( attempt = 0; attempt <= VERIFY_TIMEOUT_SECONDS; attempt++ )); do
    if pid=$(launchctl kickstart -p "$(launchd_target)" 2>/dev/null); then
      # kickstart 按行输出 PID；尾部换行与空白都不是进程号的一部分。
      pid=${pid//[[:space:]]/}
      case "$pid" in
        '' | *[!0-9]*)
          fail "$LAUNCHD_LABEL did not report a numeric process id after start"
          ;;
      esac
      if kill -0 "$pid" 2>/dev/null; then
        for (( stable = 0; stable < VERIFY_STABLE_SECONDS; stable++ )); do
          sleep 1
          if ! kill -0 "$pid" 2>/dev/null; then
            fail "$LAUNCHD_LABEL pid $pid did not stay alive for ${VERIFY_STABLE_SECONDS}s after start"
          fi
        done
        return 0
      fi
    fi
    if (( attempt < VERIFY_TIMEOUT_SECONDS )); then
      sleep 1
    fi
  done
  fail "$LAUNCHD_LABEL is not running after start; inspect: launchctl print $(launchd_target)"
}

service_is_active() {
  systemctl --user is-active --quiet "$SERVICE_NAME"
}

# 重启成功不等于服务可用：单元可能在数秒内因注册失败或配置错误退出。先等到 systemd 报告
# active，再要求服务在整个稳定窗口内持续 active，因此一次短暂的 active 结果不足以判定成功。
verify_service_active() {
  local attempt stable
  for (( attempt = 0; attempt <= VERIFY_TIMEOUT_SECONDS; attempt++ )); do
    if service_is_active; then
      for (( stable = 0; stable < VERIFY_STABLE_SECONDS; stable++ )); do
        sleep 1
        if ! service_is_active; then
          fail "$SERVICE_NAME did not stay active for ${VERIFY_STABLE_SECONDS}s after restart; inspect: systemctl --user status $SERVICE_NAME"
        fi
      done
      return 0
    fi
    if (( attempt < VERIFY_TIMEOUT_SECONDS )); then
      sleep 1
    fi
  done
  fail "$SERVICE_NAME is not active after restart; inspect: systemctl --user status $SERVICE_NAME"
}

# 只报告本次调用真正知道的取值：升级不接收任何配置选项，因此不会伪造一个空的数据目录。
print_installed_summary() {
  if [ "$HOST_OS" = Darwin ]; then
    echo "Service:   $LAUNCHD_LABEL"
    echo "JAR:       $INSTALLED_JAR"
    echo "Plist:     $PLIST_PATH"
  else
    echo "Service:   $SERVICE_NAME"
    echo "JAR:       $INSTALLED_JAR"
    echo "Unit:      $UNIT_PATH"
  fi
  if [ -n "$DATA_DIR" ]; then
    echo "Data dir:  $DATA_DIR"
  fi
  if [ "$HOST_OS" = Darwin ]; then
    echo "Next:      launchctl print $(launchd_target)"
  else
    echo "Next:      systemctl --user status $SERVICE_NAME"
  fi
}

prepare_install_inputs() {
  parse_install_options "$@"
  if [ -z "$GATEWAY_URI" ]; then
    fail "missing required option: --gateway-uri"
  fi
  if [ -z "$TOKEN_FILE" ]; then
    fail "missing required option: --registration-token-file"
  fi
  validate_gateway_uri
  validate_token_file
  if [ -n "$NOTE" ]; then
    validate_note
  fi
  resolve_data_dir
  resolve_bash_executable
  resolve_verification_windows

  # 所有输入（含 JDK）先校验完，再探测宿主服务管理器与受管定义，最后才构建。
  # 不受支持的系统也在这里失败：选项错误优先于宿主错误，且此时还没有受管路径被创建。
  require_supported_host
  SELECTED_JAVA_HOME=$(resolve_java_home)
  resolve_javap_executable
}

prepare_validated_jar() {
  build_daemon
  verify_built_jar
  stage_jar
}

cmd_install_linux() {
  require_systemd_user
  # 已存在的单元必须先确认归属，避免覆盖人工维护的单元。
  if [ -e "$UNIT_PATH" ]; then
    require_managed_unit
  fi

  prepare_validated_jar
  publish_staged_jar
  step "Installed $INSTALLED_JAR"
  write_unit_file
  step "Wrote $UNIT_PATH"

  systemctl --user daemon-reload
  systemctl --user enable "$SERVICE_NAME"
  systemctl --user restart "$SERVICE_NAME"
  verify_service_active
  step "$SERVICE_NAME is active"
  print_installed_summary
}

cmd_install_darwin() {
  require_launchd_gui_domain
  # 已存在的 plist 必须先确认归属。构建、JAR 校验和 plist lint 都发生在 bootout 之前，
  # 因此这些失败不会卸下、也不会改写仍在运行的安装。
  if [ -e "$PLIST_PATH" ]; then
    require_managed_plist
  fi

  prepare_validated_jar
  stage_plist_file
  if [ -e "$PLIST_PATH" ]; then
    bootout_loaded_service
  fi
  publish_staged_jar
  step "Installed $INSTALLED_JAR"
  publish_staged_plist
  step "Wrote $PLIST_PATH"

  launchctl bootstrap "$LAUNCHD_DOMAIN" "$PLIST_PATH"
  verify_launchd_process
  step "$LAUNCHD_LABEL is running"
  print_installed_summary
}

cmd_install() {
  prepare_install_inputs "$@"
  case "$HOST_OS" in
    Linux) cmd_install_linux ;;
    Darwin) cmd_install_darwin ;;
  esac
}

# 升级不重复任何配置：服务定义保留安装时的取值，只替换由本仓库重新构建出来的 JAR。
cmd_upgrade_linux() {
  require_systemd_user
  require_managed_unit

  SELECTED_JAVA_HOME=$(resolve_java_home)
  prepare_validated_jar
  publish_staged_jar
  step "Replaced $INSTALLED_JAR"

  systemctl --user daemon-reload
  systemctl --user restart "$SERVICE_NAME"
  verify_service_active
  step "$SERVICE_NAME is active"
  print_installed_summary
}

cmd_upgrade_darwin() {
  require_launchd_gui_domain
  require_managed_plist

  SELECTED_JAVA_HOME=$(resolve_java_home)
  prepare_validated_jar
  publish_staged_jar
  step "Replaced $INSTALLED_JAR"

  # 升级不改写 plist。`-k` 先停再起，让新 JAR 被已经加载的服务重新执行。
  launchctl kickstart -k "$(launchd_target)"
  verify_launchd_process
  step "$LAUNCHD_LABEL is running"
  print_installed_summary
}

cmd_upgrade() {
  if [ $# -ne 0 ]; then
    fail "upgrade accepts no options"
  fi
  resolve_verification_windows
  case "$HOST_OS" in
    Linux) cmd_upgrade_linux ;;
    Darwin) cmd_upgrade_darwin ;;
  esac
}

tail_log_file() {
  local path=$1 label=$2
  if [ -f "$path" ]; then
    echo
    echo "==== $label (last $JOURNAL_TAIL_LINES lines) ===="
    tail -n "$JOURNAL_TAIL_LINES" "$path" || true
  fi
}

cmd_status_linux() {
  require_systemd_user
  if [ ! -e "$UNIT_PATH" ]; then
    echo "kk-studio daemon is not installed: no unit at $UNIT_PATH" >&2
    return 1
  fi
  # 无标记的单元不属于本脚本：不能把它报告成受管安装。
  require_managed_unit

  # status 是只读命令：systemd 对 inactive 单元返回非零，这里只作为显示内容。
  systemctl --user --no-pager status "$SERVICE_NAME" || true

  if command -v journalctl >/dev/null 2>&1; then
    echo
    echo "==== journal (last $JOURNAL_TAIL_LINES lines) ===="
    journalctl --user --no-pager -n "$JOURNAL_TAIL_LINES" -u "$SERVICE_NAME" || true
  else
    echo "journalctl is not available; cannot show the service journal" >&2
  fi

  if service_is_active; then
    return 0
  fi
  echo "$SERVICE_NAME is not active" >&2
  return 3
}

cmd_status_darwin() {
  require_launchd_gui_domain
  if [ ! -e "$PLIST_PATH" ]; then
    echo "kk-studio daemon is not installed: no plist at $PLIST_PATH" >&2
    return 1
  fi
  # 无标记的 plist 不属于本脚本：不能把它报告成受管安装。
  require_managed_plist

  # status 只读：打印 launchctl 状态与已有日志尾部，不用 kickstart，因此不会把服务拉起来。
  launchctl print "$(launchd_target)" || true
  tail_log_file "$LAUNCHD_STDOUT_LOG" stdout
  tail_log_file "$LAUNCHD_STDERR_LOG" stderr

  if launchd_service_is_loaded; then
    return 0
  fi
  echo "$LAUNCHD_LABEL is installed but not loaded" >&2
  return 3
}

cmd_status() {
  if [ $# -ne 0 ]; then
    fail "status accepts no options"
  fi
  case "$HOST_OS" in
    Linux) cmd_status_linux ;;
    Darwin) cmd_status_darwin ;;
  esac
}

uninstall_linux() {
  # 停止并禁用失败时立即中止：先移除 JAR 会让仍在运行的进程失去自己的产物。
  if ! systemctl --user disable --now "$SERVICE_NAME"; then
    fail "cannot stop and disable $SERVICE_NAME; nothing was removed"
  fi
  rm -f "$UNIT_PATH"
  rm -f "$INSTALLED_JAR"
  systemctl --user daemon-reload
  systemctl --user reset-failed "$SERVICE_NAME" 2>/dev/null || true

  echo "Removed:   $UNIT_PATH"
  echo "Removed:   $INSTALLED_JAR"
  echo "Preserved: the registration token file and the daemon data directory (default \$HOME/.kk-studio)"
  echo "Preserved: nothing else under \$HOME/.config/kk-studio or \$HOME/.kk-studio was touched"
}

uninstall_darwin() {
  # bootout 失败时什么都不删：仍在运行的进程必须继续能找到自己的 JAR 与 plist。
  if launchd_service_is_loaded; then
    if ! launchctl bootout "$(launchd_target)"; then
      fail "cannot bootout $LAUNCHD_LABEL; nothing was removed"
    fi
  fi
  rm -f "$PLIST_PATH"
  rm -f "$INSTALLED_JAR"

  echo "Removed:   $PLIST_PATH"
  echo "Removed:   $INSTALLED_JAR"
  echo "Preserved: the registration token file and the daemon data directory (default \$HOME/.kk-studio)"
  echo "Preserved: $LAUNCHD_STDOUT_LOG"
  echo "Preserved: $LAUNCHD_STDERR_LOG"
}

cmd_uninstall() {
  if [ $# -ne 0 ]; then
    fail "uninstall accepts no options"
  fi
  case "$HOST_OS" in
    Linux)
      require_systemd_user
      require_managed_unit
      uninstall_linux
      ;;
    Darwin)
      require_launchd_gui_domain
      require_managed_plist
      uninstall_darwin
      ;;
  esac
}

usage_error() {
  usage >&2
  exit 2
}

main() {
  if [ $# -eq 0 ]; then
    usage_error
  fi
  # 帮助不依赖宿主。其它命令在解析选项或触碰受管路径之前就拒绝不受支持的系统。
  case "$1" in
    -h | --help | install) ;;
    *) require_supported_host ;;
  esac
  case "$1" in
    -h | --help)
      usage
      ;;
    install)
      shift
      # `install --help` 只在它是完整的 install 参数列表时才是信息命令；混入其它参数一律
      # 交给选项解析失败，未知/重复选项不会被帮助掩盖。
      if [ $# -eq 1 ] && { [ "$1" = "-h" ] || [ "$1" = "--help" ]; }; then
        usage
        return 0
      fi
      cmd_install "$@"
      ;;
    upgrade)
      shift
      cmd_upgrade "$@"
      ;;
    status)
      shift
      cmd_status "$@"
      ;;
    uninstall)
      shift
      cmd_uninstall "$@"
      ;;
    *)
      echo "ERROR: unknown command: $1" >&2
      usage_error
      ;;
  esac
}

main "$@"
