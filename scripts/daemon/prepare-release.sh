#!/usr/bin/env bash
# scripts/daemon/prepare-release.sh — stage one Node Daemon release archive.
#
# 单一职责：把一个已经编译好的 Node Daemon 与**目标平台**的生产依赖、冻结的 code-server
# 运行时打包为单个跨平台发行归档。脚本不上传任何东西、不打印任何凭证，也不触碰专用发布
# 目录以外的路径。
#
# 用法：
#   scripts/daemon/prepare-release.sh <release-tag> <os> <arch>
#
#   <release-tag>  形如 `v1.2.3` 的 tag：`v` 之后只允许字母、数字、`.`、`_`、`-`。
#   <os>           linux | macos | windows。
#   <arch>         amd64 | arm64（windows 仅 amd64）。
#
# 前置条件：先编译 Node Daemon：
#   npm --prefix daemon ci && npm --prefix daemon run build
# 使 `daemon/dist/main.js`（编译产物）存在。生产依赖由本脚本按目标平台重新安装。
#
# 跨平台正确性：脚本用 `npm ci --omit=dev --os=<npmOs> --cpu=<npmCpu>` 为目标平台安装依赖，
# 因此只会装入对应平台的 optional 预编译产物（例如 koffi 的 `@koromix/koffi-<os>-<arch>`），
# 而不是把 Linux 上装出的 node_modules 原样打包成“假跨平台”。缺失对应预编译产物即失败，
# 不静默降级。
#
# 暂存资产（输出目录每次整体重建，因此每次恰好只包含下列五个文件）：
#   kk-studio-daemon-<tag>-<os>-<arch>.tar.gz          可运行发行归档
#   kk-studio-daemon-<tag>-<os>-<arch>.tar.gz.sha256   上者的 `sha256sum -c` 校验文件
#   kk-studio-daemon-<tag>-<os>-<arch>.json            schemaVersion 1 的确定性元数据
#   LICENSE                                            Apache License 2.0
#   THIRD_PARTY_NOTICES                                Daemon 的第三方组件来源与许可
#
# 安全边界：输出固定为仓库内的 `daemon/release/<os>-<arch>`，不接收调用方路径。新内容
# 先写入 `daemon/release` 下的临时目录，全部写好后再整体替换，因此递归删除不能越出固定发布目录。

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
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

DAEMON_DIR="$REPO_ROOT/daemon"
DIST_DIR="$DAEMON_DIR/dist"
NODE_MODULES="$DAEMON_DIR/node_modules"
IDE_EXTENSION_DIR="$DAEMON_DIR/ide-extension"
RUNTIME_MANIFEST="$DAEMON_DIR/runtime-manifest.json"
LICENSE_FILE="$REPO_ROOT/LICENSE"
THIRD_PARTY_NOTICES_FILE="$DAEMON_DIR/THIRD_PARTY_NOTICES"
RELEASE_ROOT="$DAEMON_DIR/release"

METADATA_SCHEMA_VERSION=1
ENTRYPOINT=main.js
# 与 .github/workflows/daemon-release.yml 的 tag 校验必须保持一致。
RELEASE_TAG_PATTERN='^v[0-9A-Za-z._-]+$'
PROJECT_VERSION_PATTERN='^[0-9A-Za-z._+-]+$'
COMMIT_PATTERN='^([0-9a-f]{40}|[0-9a-f]{64})$'
EPOCH_PATTERN='^[0-9]+$'
OS_PATTERN='^(linux|macos|windows)$'
ARCH_PATTERN='^(amd64|arm64)$'

WORK_DIR=
STAGING_DIR=
RELEASE_DIR=

usage() {
  cat <<'EOF'
Usage: scripts/daemon/prepare-release.sh <release-tag> <os> <arch>

Stage one Node Daemon release archive for a single platform. Nothing is uploaded;
every asset is written into a freshly rebuilt output directory.

  <release-tag>  Tag of the form v1.2.3: after "v" only letters, digits, ".",
                 "_" and "-" are accepted.
  <os>           linux | macos | windows
  <arch>         amd64 | arm64 (windows supports amd64 only)

Staged assets:
  kk-studio-daemon-<tag>-<os>-<arch>.tar.gz          runnable distribution archive
  kk-studio-daemon-<tag>-<os>-<arch>.tar.gz.sha256   sha256sum -c checksum
  kk-studio-daemon-<tag>-<os>-<arch>.json            deterministic metadata (schemaVersion 1)
  LICENSE                                            Apache License 2.0
  THIRD_PARTY_NOTICES                                third-party component notices

Requires a compiled daemon (`npm --prefix daemon ci && npm --prefix daemon run build`), a Git
checkout, curl, tar, a SHA256 tool, npm and Node on PATH (or NPM_BIN / NODE_BIN). Production
dependencies are reinstalled for the target platform via `npm ci --os/--cpu`, so each archive
carries only the target's prebuilt native modules. The tag must equal
v<daemon/package.json version>.

Assets are always staged under daemon/release/<os>-<arch>. Each run first removes and
rebuilds that directory, deleting any other files in it.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

cleanup() {
  if [ -n "$STAGING_DIR" ] && [ -d "$STAGING_DIR" ]; then
    rm -rf -- "$STAGING_DIR"
  fi
  if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
    rm -rf -- "$WORK_DIR"
  fi
}

trap cleanup EXIT

require_input() {
  if [ ! -f "$1" ] || [ ! -s "$1" ]; then
    die "$2"
  fi
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    command -v shasum >/dev/null 2>&1 || die "missing command: sha256sum or shasum"
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# 本机 uname 风格 os/arch 到 npm optional 依赖选择器的映射（npm 的 os 用 darwin/win32）。
npm_target_platform() {
  case "$1" in
    linux-amd64) echo 'linux x64' ;;
    linux-arm64) echo 'linux arm64' ;;
    macos-amd64) echo 'darwin x64' ;;
    macos-arm64) echo 'darwin arm64' ;;
    windows-amd64) echo 'win32 x64' ;;
    *) die "no npm platform mapping for $1" ;;
  esac
}

# koffi 的 optional 预编译包名（@koromix/koffi-<os>-<arch>）；缺失即说明未装到目标平台产物。
koffi_optional_name() {
  local npm_os=$1 npm_cpu=$2
  echo "@koromix/koffi-${npm_os}-${npm_cpu}"
}

manifest_field() {
  local key=$1 field=$2
  "$NODE_BIN" -e '
    const fs = require("fs");
    const manifest = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    const asset = manifest.codeServer[process.argv[2]];
    if (!asset) { process.exit(2); }
    process.stdout.write(String(asset[process.argv[3]] ?? ""));
  ' "$RUNTIME_MANIFEST" "$key" "$field" 2>/dev/null || die "runtime manifest has no asset for $key"
}

daemon_version() {
  # package.json 的 version 是唯一打包版本来源。
  "$NODE_BIN" -e 'process.stdout.write(String(require(process.argv[1]).version))' \
    "$DAEMON_DIR/package.json" 2>/dev/null || die "cannot read daemon/package.json version"
}

install_platform_dependencies() {
  local npm_os=$1 npm_cpu=$2 koffi_optional
  # 目标平台的生产依赖：--omit=dev 去掉构建期依赖，--os/--cpu 只选目标平台的 optional 预编译产物。
  "$NPM_BIN" --prefix "$DAEMON_DIR" ci --omit=dev --no-audit --no-fund \
    --os="$npm_os" --cpu="$npm_cpu" ||
    die "cannot install production dependencies for $npm_os-$npm_cpu"
  koffi_optional=$(koffi_optional_name "$npm_os" "$npm_cpu")
  [ -d "$NODE_MODULES/$koffi_optional" ] ||
    die "target platform prebuilt is missing: $koffi_optional (refusing a fake cross-platform archive)"
}

main() {
  if [ $# -eq 1 ] && { [ "$1" = "-h" ] || [ "$1" = "--help" ]; }; then
    usage
    return 0
  fi
  if [ $# -ne 3 ]; then
    usage >&2
    return 2
  fi

  local release_tag=$1 os=$2 arch=$3
  [[ $release_tag =~ $RELEASE_TAG_PATTERN ]] ||
    die "release tag must match ${RELEASE_TAG_PATTERN}: $release_tag"
  [[ $os =~ $OS_PATTERN ]] || die "unsupported os: $os"
  [[ $arch =~ $ARCH_PATTERN ]] || die "unsupported arch: $arch"
  if [ "$os" = windows ] && [ "$arch" != amd64 ]; then
    die "windows supports amd64 only"
  fi

  NODE_BIN=${NODE_BIN:-node}
  NPM_BIN=${NPM_BIN:-npm}
  command -v "$NODE_BIN" >/dev/null 2>&1 || die "missing command: $NODE_BIN (set NODE_BIN)"
  command -v "$NPM_BIN" >/dev/null 2>&1 || die "missing command: $NPM_BIN (set NPM_BIN)"
  command -v curl >/dev/null 2>&1 || die "missing command: curl"
  command -v tar >/dev/null 2>&1 || die "missing command: tar"
  command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1 ||
    die "missing command: sha256sum or shasum"

  require_input "$DIST_DIR/$ENTRYPOINT" \
    "daemon build not found: $DIST_DIR/$ENTRYPOINT (run: npm --prefix daemon ci && npm --prefix daemon run build)"
  require_input "$RUNTIME_MANIFEST" "runtime manifest not found: $RUNTIME_MANIFEST"
  require_input "$LICENSE_FILE" "license file not found or empty: $LICENSE_FILE"
  require_input "$THIRD_PARTY_NOTICES_FILE" \
    "third-party notices not found or empty: $THIRD_PARTY_NOTICES_FILE"

  local version
  version=$(daemon_version)
  [[ $version =~ $PROJECT_VERSION_PATTERN ]] ||
    die "daemon version is missing or unsafe (found '${version:-<missing>}')"
  if [ "$release_tag" != "v$version" ]; then
    die "release tag must equal the daemon version: expected v$version, got $release_tag"
  fi

  # 归档入口必须可执行且版本一致：只有真正启动一次才能证明入口与内嵌依赖可用。
  local version_output
  if ! version_output=$("$NODE_BIN" "$DIST_DIR/$ENTRYPOINT" --version 2>&1); then
    die "daemon build is not executable: '$NODE_BIN $DIST_DIR/$ENTRYPOINT --version' failed: $version_output"
  fi
  if [ "$version_output" != "kk-studio-daemon $version" ]; then
    die "daemon '--version' output must be 'kk-studio-daemon $version' (found: $version_output)"
  fi

  local npm_platform npm_os npm_cpu
  npm_platform=$(npm_target_platform "$os-$arch")
  npm_os=${npm_platform% *}
  npm_cpu=${npm_platform#* }
  install_platform_dependencies "$npm_os" "$npm_cpu"

  local code_server_url code_server_sha
  code_server_url=$(manifest_field "$os-$arch" url)
  code_server_sha=$(manifest_field "$os-$arch" sha256)
  [[ $code_server_sha =~ ^[0-9a-f]{64}$ ]] ||
    die "runtime manifest sha256 for $os-$arch is not 64 hex characters"

  local commit source_date_epoch
  if ! commit=$(git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{commit}' 2>/dev/null); then
    die "cannot resolve HEAD: run this script inside a Git checkout"
  fi
  [[ $commit =~ $COMMIT_PATTERN ]] || die "unexpected commit id: $commit"
  source_date_epoch=$(git -C "$REPO_ROOT" show -s --format=%ct "$commit")
  [[ $source_date_epoch =~ $EPOCH_PATTERN ]] || die "cannot resolve commit timestamp for release metadata"

  local artifact="kk-studio-daemon-${release_tag}-${os}-${arch}.tar.gz"
  local checksum="$artifact.sha256"
  local metadata="kk-studio-daemon-${release_tag}-${os}-${arch}.json"
  RELEASE_DIR="$RELEASE_ROOT/${os}-${arch}"

  WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/kk-studio-daemon-release.XXXXXX")
  mkdir -p "$RELEASE_ROOT"
  local code_server_tar="$WORK_DIR/code-server.tar.gz"
  curl -q --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
    --output "$code_server_tar" "$code_server_url" ||
    die "cannot download the frozen code-server runtime for $os-$arch"
  local actual_sha
  actual_sha=$(sha256_of "$code_server_tar")
  [ "$actual_sha" = "$code_server_sha" ] ||
    die "code-server runtime SHA256 does not match the frozen manifest for $os-$arch"

  STAGING_DIR=$(mktemp -d "$(dirname "$RELEASE_DIR")/.kk-studio-daemon-release.XXXXXX")
  mkdir -p "$STAGING_DIR/node_modules" "$STAGING_DIR/runtime/code-server"
  # 编译产物位于归档根，入口固定为 main.js。
  cp -R "$DIST_DIR/." "$STAGING_DIR/"
  cp -R "$NODE_MODULES/." "$STAGING_DIR/node_modules/"
  tar -xzf "$code_server_tar" -C "$STAGING_DIR/runtime/code-server" --strip-components=1
  [ -x "$STAGING_DIR/runtime/code-server/lib/node" ] ||
    die "code-server runtime does not provide lib/node for $os-$arch"
  # IDE 扩展源码随发行包一起复制；缺失时跳过（扩展尚未提供的平台/阶段不伪造）。
  if [ -d "$IDE_EXTENSION_DIR" ]; then
    mkdir -p "$STAGING_DIR/ide-extension"
    cp -R "$IDE_EXTENSION_DIR/." "$STAGING_DIR/ide-extension/"
  fi
  cp -- "$LICENSE_FILE" "$STAGING_DIR/LICENSE"
  cp -- "$THIRD_PARTY_NOTICES_FILE" "$STAGING_DIR/THIRD_PARTY_NOTICES"
  # 归档根需要一个 ESM 标记的 package.json，否则冻结运行时会按 CommonJS 解析 main.js。
  cat >"$STAGING_DIR/package.json" <<EOF
{
  "name": "@kk-studio/daemon",
  "version": "$version",
  "private": true,
  "type": "module",
  "main": "$ENTRYPOINT"
}
EOF

  # 归档内的冻结清单带上 daemonVersion，供受管更新在下载后核对版本。
  "$NODE_BIN" -e '
    const fs = require("fs");
    const manifest = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    const key = process.argv[2];
    const out = {
      schemaVersion: manifest.schemaVersion,
      daemonVersion: process.argv[3],
      codeServerVersion: manifest.codeServerVersion,
      codeServer: { [key]: manifest.codeServer[key] },
    };
    fs.writeFileSync(process.argv[4], JSON.stringify(out, null, 2) + "\n");
  ' "$RUNTIME_MANIFEST" "$os-$arch" "$version" "$STAGING_DIR/runtime-manifest.json"

  (cd "$STAGING_DIR" && tar -czf "$WORK_DIR/$artifact" .)
  (cd "$WORK_DIR" && sha256sum "$artifact" >"$checksum" && sha256sum -c --status "$checksum")
  local artifact_sha
  artifact_sha=$(cut -d' ' -f1 <"$WORK_DIR/$checksum")

  cat >"$WORK_DIR/$metadata" <<EOF
{
  "schemaVersion": $METADATA_SCHEMA_VERSION,
  "tag": "$release_tag",
  "commit": "$commit",
  "sourceDateEpoch": $source_date_epoch,
  "projectVersion": "$version",
  "os": "$os",
  "arch": "$arch",
  "entrypoint": "$ENTRYPOINT",
  "codeServerVersion": "$("$NODE_BIN" -e 'process.stdout.write(String(require(process.argv[1]).codeServerVersion))' "$RUNTIME_MANIFEST")",
  "artifact": "$artifact",
  "artifactSha256": "$artifact_sha"
}
EOF

  mkdir -p "$RELEASE_ROOT"
  rm -rf -- "$RELEASE_DIR"
  mkdir -p "$RELEASE_DIR"
  cp -- "$WORK_DIR/$artifact" "$WORK_DIR/$checksum" "$WORK_DIR/$metadata" "$RELEASE_DIR/"
  cp -- "$LICENSE_FILE" "$RELEASE_DIR/LICENSE"
  cp -- "$THIRD_PARTY_NOTICES_FILE" "$RELEASE_DIR/THIRD_PARTY_NOTICES"

  echo "staged daemon release ${release_tag} for ${os}-${arch} (commit ${commit}, version ${version})"
  echo "output: ${RELEASE_DIR}"
  local output_file
  for output_file in "$RELEASE_DIR"/*; do
    printf '  %s\n' "${output_file##*/}"
  done
}

main "$@"
