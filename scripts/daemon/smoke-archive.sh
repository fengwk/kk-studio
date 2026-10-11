#!/usr/bin/env bash
# scripts/daemon/smoke-archive.sh — host-arch acceptance smoke for one staged Node
# Daemon release archive.
#
# 单一职责：在 Linux 或 macOS 主机上把 `prepare-release.sh` 暂存的、与本机匹配的归档解开，
# 用归档自带的冻结运行时 (`runtime/code-server/lib/node`) 运行发行入口 `main.js`，在**受控
# PATH（不含用户 Node / Java）** 下证明归档自足：
#   * `--version` / `--help` / `--check-config` 的发行契约行为；真实 schema 是裸 daemon
#     对象，被外层 `daemon` 包裹的假结构必须被真实入口拒绝；
#   * 冻结 code-server 布局含 `lib/node` 与 `out/node/entry.js`，且归档内 code-server 的
#     原生模块能被这份 Node 加载；
#   * 目标平台 `@koromix/koffi-<linux|darwin>-*` 原生预编译模块确实可加载：私有数据目录
#     0700、`daemon.lock` 0600 与内核锁独占；
#   * 真实入口 `main.js --config` 能启动、持锁并被第二个实例按锁冲突拒绝，且在失败、
#     超时或 SIGINT/SIGTERM 时被终止并等待真实退出后再删除临时目录。
#
# Windows 归档不能在 Unix 主机上执行，显式失败而不是跳过。它不下载、不发布、不注册服务，
# 也不触碰用户目录；它启动的唯一长驻进程是被本脚本收尾的受测 daemon 实例。
#
# 用法：
#   scripts/daemon/smoke-archive.sh <archive.tar.gz>
#
# <archive.tar.gz> 必须形如 `kk-studio-daemon-v<version>-<linux|macos>-<arch>.tar.gz` 且与
# 本机匹配；windows 归档或跨平台归档显式失败而不是整体跳过。

set -euo pipefail
umask 077

usage() {
  cat <<'EOF'
Usage: scripts/daemon/smoke-archive.sh <archive.tar.gz>

Acceptance smoke for one staged Node Daemon release archive on Linux or macOS. Extracts
the archive and runs it with its own frozen runtime under a PATH that contains no user
Node or Java: --version / --help / --check-config (including rejecting a wrapped "daemon"
object the real schema forbids), the frozen code-server entry and its native modules, the
target-platform koffi prebuilt (private directory + kernel lock), and a real main.js
--config launch that is terminated and reaped on every exit path. Only a linux or macos
archive for this host can run; Windows and cross-platform archives fail instead of being
skipped. Nothing is downloaded, uploaded or registered.

The archive must already exist: run
`scripts/daemon/prepare-release.sh <tag> <linux|macos> <arch>` first.
EOF
}

# 统一的收尾：终止本脚本启动的 daemon、等待真实退出，再删除临时目录。失败、超时与
# SIGINT/SIGTERM 都经此路径，绝不只删目录而留下进程。
tmp=
daemon_pid=
# 回收结果：daemon_forced=1 表示需要 SIGKILL；daemon_exit_status 保存 wait 的真实退出码。
# 失败路径不回写原始退出码；成功路径据此断言 daemon 是收到 SIGTERM 后优雅退出。
daemon_forced=0
daemon_exit_status=
terminate_daemon() {
  [ -n "$daemon_pid" ] || return 0
  local pid=$daemon_pid attempt
  daemon_pid=
  if kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null || true
    for ((attempt=0; attempt<100; attempt++)); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.1
    done
    if kill -0 "$pid" 2>/dev/null; then
      daemon_forced=1
      kill -KILL "$pid" 2>/dev/null || true
    fi
  fi
  # 只有 wait 成功返回才说明该进程已被真实回收；随后才能安全删除临时目录。
  daemon_exit_status=0
  wait "$pid" 2>/dev/null || daemon_exit_status=$?
  return 0
}
cleanup() {
  local code=$?
  trap - EXIT INT TERM
  terminate_daemon
  if [ -n "$tmp" ] && [ -d "$tmp" ]; then rm -rf -- "$tmp"; fi
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  echo "archive smoke failed: $*" >&2
  exit 1
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi
[ $# -eq 1 ] || { usage >&2; exit 2; }
ARCHIVE=$1
[ -f "$ARCHIVE" ] && [ -s "$ARCHIVE" ] || fail "archive is not a readable non-empty file: $ARCHIVE"
[ ! -L "$ARCHIVE" ] || fail "archive must not be a symbolic link: $ARCHIVE"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}
for tool in tar uname mktemp cp chmod ln rm; do require_cmd "$tool"; done

# 归档名即平台契约：只运行与本机匹配的 linux/macos 归档，否则显式失败而不是跳过。
archive_name=${ARCHIVE##*/}
name_pattern='^kk-studio-daemon-v[0-9A-Za-z._-]+-(linux|macos)-(amd64|arm64)\.tar\.gz$'
[[ $archive_name =~ $name_pattern ]] ||
  fail "unsupported archive name; this smoke only runs a linux or macos archive named kk-studio-daemon-v<version>-<os>-<arch>.tar.gz: $archive_name"
archive_os=${BASH_REMATCH[1]}
archive_arch=${BASH_REMATCH[2]}

host_kernel=$(uname -s)
case "$host_kernel" in
  Linux) host_os=linux ;;
  Darwin) host_os=macos ;;
  *) fail "this archive smoke only runs on Linux or macOS; Windows launches are out of its scope" ;;
esac
host_machine=$(uname -m)
case "$host_machine" in
  x86_64 | amd64) expected_arch=amd64 ;;
  arm64 | aarch64) expected_arch=arm64 ;;
  *) fail "unsupported host architecture: $host_machine" ;;
esac
if [ "$archive_os" != "$host_os" ] || [ "$archive_arch" != "$expected_arch" ]; then
  fail "archive targets ${archive_os}-${archive_arch} but this host is ${host_os}-${expected_arch}; cross-platform archives cannot run here"
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/kk-studio-daemon-smoke.XXXXXXXX")
mkdir -p "$tmp/home"

extract="$tmp/extract"
mkdir -p "$extract"
tar -xzf "$ARCHIVE" -C "$extract" || fail "cannot extract the archive"

node_bin="$extract/runtime/code-server/lib/node"
code_server_entry="$extract/runtime/code-server/out/node/entry.js"
main_js="$extract/main.js"
[ -x "$node_bin" ] || fail "archive is missing its frozen runtime: runtime/code-server/lib/node"
[ -f "$code_server_entry" ] || fail "archive is missing the frozen code-server entry: runtime/code-server/out/node/entry.js"
[ -f "$main_js" ] || fail "archive is missing its entrypoint: main.js"
[ -f "$extract/package.json" ] || fail "archive is missing package.json"
[ -f "$extract/runtime-manifest.json" ] || fail "archive is missing runtime-manifest.json"
[ -f "$extract/LICENSE" ] || fail "archive is missing LICENSE"
[ -f "$extract/THIRD_PARTY_NOTICES" ] || fail "archive is missing THIRD_PARTY_NOTICES"
[ -d "$extract/ide-extension" ] || fail "archive is missing the IDE extension"

case "$archive_os-$archive_arch" in
  linux-amd64) koffi=@koromix/koffi-linux-x64 ;;
  linux-arm64) koffi=@koromix/koffi-linux-arm64 ;;
  macos-amd64) koffi=@koromix/koffi-darwin-x64 ;;
  macos-arm64) koffi=@koromix/koffi-darwin-arm64 ;;
  *) fail "no koffi prebuilt mapping for $archive_os-$archive_arch" ;;
esac
[ -e "$extract/node_modules/$koffi" ] ||
  fail "archive is missing the target-platform koffi prebuilt: node_modules/$koffi"

# 冻结 code-server 4.141.0 自带 Node 24；发行 `engines` 也要求 24.x，两者必须一致。
frozen_node_version=$("$node_bin" --version 2>&1) || fail "frozen runtime cannot report its version"
case "$frozen_node_version" in
  v24.*) ;;
  *) fail "frozen runtime must be Node 24.x, found: $frozen_node_version" ;;
esac

version=$(
  "$node_bin" -e 'process.stdout.write(String(require(process.argv[1]).version))' \
    "$extract/package.json" 2>/dev/null
) || fail "cannot read the archive package.json version"
version_pattern='^[0-9A-Za-z._+-]{1,64}$'
[[ $version =~ $version_pattern ]] || fail "archive package.json has an unsafe version"

# 归档内的冻结清单必须与入口版本一致：受管更新下载后会据此核对版本。
manifest_daemon_version=$(
  "$node_bin" -e '
    const fs = require("fs");
    const m = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    process.stdout.write(String(m.daemonVersion ?? ""));
  ' "$extract/runtime-manifest.json" 2>/dev/null
) || fail "cannot read the archive runtime-manifest.json"
[ "$manifest_daemon_version" = "$version" ] ||
  fail "runtime-manifest daemonVersion '$manifest_daemon_version' disagrees with package version '$version'"

# 受控 PATH：只暴露 sh/bash（配置的 bashExecutable 默认为 bash），不含用户 Node / Java。
sandbox="$tmp/sandbox"
mkdir -p "$sandbox"
for tool in sh bash; do
  resolved=$(command -v "$tool") || fail "missing $tool on the host"
  ln -s "$resolved" "$sandbox/$tool"
done
run_isolated() {
  env -i PATH="$sandbox" HOME="$tmp/home" LANG=C.UTF-8 LC_ALL=C.UTF-8 "$@"
}
if run_isolated sh -c 'command -v node >/dev/null 2>&1 || command -v java >/dev/null 2>&1'; then
  fail "the controlled PATH must not contain a user Node or Java"
fi

# 1) 信息命令只用冻结运行时即可回答，不读取配置、不建目录。
actual_version=$(run_isolated "$node_bin" "$main_js" --version 2>&1) ||
  fail "--version failed: $actual_version"
[ "$actual_version" = "kk-studio-daemon $version" ] ||
  fail "--version mismatch: expected 'kk-studio-daemon $version', found '$actual_version'"

help=$(run_isolated "$node_bin" "$main_js" --help 2>&1) || fail "--help failed: $help"
case "$help" in
  *"Usage: kk-studio-daemon"*) ;;
  *) fail "--help does not describe the daemon CLI" ;;
esac
case "$help" in
  *"--check-config"*) ;;
  *) fail "--help is missing the --check-config contract" ;;
esac

# 真实 daemon.json 就是裸配置对象本身；被外层 `daemon` 包裹的假结构必须被真实入口拒绝。
write_input() {
  local directory=$1 document=$2
  mkdir -p "$directory"
  printf '%s' "$document" >"$directory/daemon.json"
  printf '%s' 'smoke-token' >"$directory/daemon.token"
  chmod 0600 "$directory/daemon.json" "$directory/daemon.token"
}

# 2) --check-config 读取配置与同目录 token，必须是发行真实 schema。
valid_dir="$tmp/config"
write_input "$valid_dir" '{"studioUrl":"http://127.0.0.1:59999","note":"archive smoke"}'
valid=$(run_isolated "$node_bin" "$main_js" --check-config "$valid_dir/daemon.json" 2>&1) ||
  fail "--check-config rejected a valid configuration: $valid"
[ "$valid" = "Daemon configuration is valid" ] ||
  fail "unexpected --check-config output: '$valid'"
[ ! -e "$valid_dir/daemon.lock" ] ||
  fail "--check-config must not create a data lock"

invalid_dir="$tmp/invalid"
write_input "$invalid_dir" '{"studioUrl":"not-a-url"}'
if invalid=$(run_isolated "$node_bin" "$main_js" --check-config "$invalid_dir/daemon.json" 2>&1); then
  fail "an invalid configuration must be rejected: $invalid"
fi
case "$invalid" in
  "Invalid daemon configuration:"*) ;;
  *) fail "invalid configuration must fail with the stable marker: $invalid" ;;
esac

# 真实 schema 是裸对象；外层 `daemon` 包裹的假结构不能通过真实预检（防止 fixture 假绿）。
wrapped_dir="$tmp/wrapped"
write_input "$wrapped_dir" '{"daemon":{"studioUrl":"http://127.0.0.1:59999"}}'
if wrapped=$(run_isolated "$node_bin" "$main_js" --check-config "$wrapped_dir/daemon.json" 2>&1); then
  fail "a wrapped daemon object must be rejected by the real schema: $wrapped"
fi
case "$wrapped" in
  "Invalid daemon configuration:"*) ;;
  *) fail "the wrapped structure must fail with the stable marker: $wrapped" ;;
esac

# 3) 目标平台 koffi 原生模块：私有数据目录 0700、锁文件 0600、内核 flock 独占。
probe="$tmp/probe-native.mjs"
cat >"$probe" <<'PROBE'
import { statSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const [root, dataDir] = process.argv.slice(2)
const native = await import(pathToFileURL(join(root, 'native', 'index.js')).href)
await native.ensurePrivateDirectory(dataDir)
const dirMode = (statSync(dataDir).mode & 0o777).toString(8)
if (dirMode !== '700') {
  console.error(`unexpected data directory mode ${dirMode}`)
  process.exit(3)
}
const lock = await native.acquireDirectoryLock(dataDir)
const lockMode = (statSync(join(dataDir, 'daemon.lock')).mode & 0o777).toString(8)
if (lockMode !== '600') {
  console.error(`unexpected lock file mode ${lockMode}`)
  process.exit(4)
}
let held = false
try {
  const second = await native.acquireDirectoryLock(dataDir)
  await second.close()
} catch {
  held = true
}
if (!held) {
  console.error('a second directory lock unexpectedly succeeded')
  process.exit(5)
}
await lock.close()
process.stdout.write('koffi directory lock ok\n')
PROBE
data_dir="$tmp/data"
probe_out=$(run_isolated "$node_bin" "$probe" "$extract" "$data_dir" 2>&1) ||
  fail "koffi directory lock probe failed: $probe_out"
[ "$probe_out" = "koffi directory lock ok" ] || fail "unexpected probe output: '$probe_out'"

# 冻结 code-server 自带的原生模块必须能被同一份 Node 加载。没有 .node 即失败，不把空目录当成通过。
native_probe="$tmp/probe-code-server-native.mjs"
cat >"$native_probe" <<'PROBE'
import { readdirSync, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { isAbsolute, join, relative, resolve } from 'node:path'

const root = resolve(process.argv[2])
const host = process.argv[3]
if (!isAbsolute(root)) {
  console.error('code-server root must be absolute')
  process.exit(2)
}
const require = createRequire(join(root, 'package.json'))
const found = []
const foreignMarkers = {
  'linux-amd64': ['darwin-arm64', 'darwin-x64', 'win32-arm64', 'win32-x64', 'linux-arm64'],
  'linux-arm64': ['darwin-arm64', 'darwin-x64', 'win32-arm64', 'win32-x64', 'linux-x64'],
  'macos-amd64': ['linux-arm64', 'linux-x64', 'win32-arm64', 'win32-x64', 'darwin-arm64'],
  'macos-arm64': ['linux-arm64', 'linux-x64', 'win32-arm64', 'win32-x64', 'darwin-x64'],
}[host] ?? []
function walk(directory) {
  if (!isAbsolute(directory) || relative(root, directory).startsWith('..')) {
    console.error('native walk escaped the code-server root')
    process.exit(4)
  }
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const target = join(directory, entry.name)
    if (entry.isSymbolicLink()) {
      continue
    }
    if (entry.isDirectory()) {
      walk(target)
    } else if (entry.isFile() && entry.name.endsWith('.node')) {
      const folded = target.toLowerCase()
      if (foreignMarkers.some((marker) => folded.includes(marker))) {
        console.error(`code-server native module is not for ${host}`)
        process.exit(5)
      }
      found.push(target)
    }
  }
}
walk(root)
if (found.length === 0) {
  console.error('code-server native modules are missing')
  process.exit(2)
}
for (const modulePath of found) {
  require(modulePath)
  if (!statSync(modulePath).isFile()) {
    process.exit(3)
  }
}
process.stdout.write(`code-server native modules ok ${found.length}\n`)
PROBE
native_out=$(run_isolated "$node_bin" "$native_probe" "$extract/runtime/code-server" "$archive_os-$archive_arch" 2>&1) ||
  fail "frozen code-server native modules failed to load: $native_out"
case "$native_out" in
  "code-server native modules ok "*) ;;
  *) fail "unexpected code-server native probe output: '$native_out'" ;;
esac

# 4) 真实入口：陪伴进程持锁后，第二个实例必须因锁冲突失败，并优雅退出。
run_dir="$tmp/run"
write_input "$run_dir" '{"studioUrl":"http://127.0.0.1:59999"}'
# 直接后台启动，不经 shell 函数：这样 $! 就是 env→node 的真实 daemon 进程，收尾时能精确终止并回收。
env -i PATH="$sandbox" HOME="$tmp/home" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
  "$node_bin" "$main_js" --config "$run_dir/daemon.json" \
  >"$tmp/daemon.out" 2>"$tmp/daemon.err" &
daemon_pid=$!

lock_ready=false
for ((attempt=0; attempt<100; attempt++)); do
  if [ -f "$run_dir/daemon.lock" ]; then lock_ready=true; break; fi
  if ! kill -0 "$daemon_pid" 2>/dev/null; then break; fi
  sleep 0.1
done
[ "$lock_ready" = true ] ||
  fail "the daemon did not acquire its data lock in time; stderr: $(cat "$tmp/daemon.err")"
kill -0 "$daemon_pid" 2>/dev/null || fail "the daemon exited right after acquiring its lock"

if second=$(run_isolated "$node_bin" "$main_js" --config "$run_dir/daemon.json" 2>&1); then
  fail "a second daemon instance must fail on the held lock: $second"
fi
# 第二实例失败后第一实例仍须持锁存活：证明拒绝来自锁冲突，而不是同一配置本身就无效。
kill -0 "$daemon_pid" 2>/dev/null || fail "the first daemon exited before the lock-conflict check"
[ -f "$run_dir/daemon.lock" ] || fail "the held lock disappeared during the lock-conflict check"

terminate_daemon
# 成功烟测要求真实 daemon 在 SIGTERM 后自行优雅退出；需要 SIGKILL 或非 0 退出都不算通过。
[ "$daemon_forced" = 0 ] || fail "the daemon required SIGKILL; it must exit gracefully on SIGTERM"
[ "$daemon_exit_status" = 0 ] ||
  fail "the daemon exited with status ${daemon_exit_status} instead of 0 after SIGTERM"

echo "archive smoke ok: ${archive_name} (kk-studio-daemon ${version}, ${frozen_node_version}, ${koffi})"
