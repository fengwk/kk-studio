#!/usr/bin/env bash
# scripts/release/prepare-daemon-release.sh — stage the Environment Daemon release assets
#
# 单一职责：把一个已经构建好的 shaded Daemon JAR 与其法律文件暂存为一组可直接上传的
# 资产。脚本不上传任何东西、不打印任何凭证，也不触碰专用发布目录以外的路径。
#
# 用法：
#   scripts/release/prepare-daemon-release.sh <release-tag>
#
#   <release-tag>  形如 `v1.2.3` 的 tag：`v` 之后只允许字母、数字、`.`、`_`、`-`。
#
# 暂存资产（输出目录每次整体重建，因此每次恰好只包含下列五个文件）：
#   kk-studio-daemon-<tag>.jar          可执行 Daemon
#   kk-studio-daemon-<tag>.jar.sha256   上者的 `sha256sum -c` 校验文件
#   kk-studio-daemon-<tag>.json         schemaVersion 1 的确定性元数据（不含时钟字段）
#   LICENSE                             Apache License 2.0
#   THIRD_PARTY_NOTICES                 Daemon 的第三方组件来源与许可
#
# 前置条件：先用 `mvn -pl harness/daemon -am clean package` 构建
# `harness/daemon/target/kk-studio-daemon.jar`，并提供 JDK 21（`JAVA_HOME_21`、
# `JAVA_HOME`，或 PATH 上同一个 JDK 的 `java` 与 `jar`）。脚本用 JDK 工具校验产物，
# 因此绝不会把畸形、缺依赖或不可执行的 JAR 当作发布物。
#
# 安全边界：输出固定为仓库内的 `harness/daemon/target/release`，不接收调用方路径。新内容
# 先写入 `target` 下的临时目录，全部写好后再整体替换，因此递归删除不能越出固定发布目录。

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd -P)

DAEMON_JAR="$REPO_ROOT/harness/daemon/target/kk-studio-daemon.jar"
LICENSE_FILE="$REPO_ROOT/LICENSE"
THIRD_PARTY_NOTICES_FILE="$REPO_ROOT/harness/daemon/THIRD_PARTY_NOTICES"
RELEASE_DIR="$REPO_ROOT/harness/daemon/target/release"

DAEMON_MAIN_CLASS=fun.fengwk.kkstudio.harness.daemon.DaemonMain
METADATA_SCHEMA_VERSION=1
MINIMUM_JAVA=21
# 与 .github/workflows/daemon-release.yml 的 tag 校验必须保持一致：文件名与目录名都直接
# 来自 tag，所以字符集之外的一切输入都必须失败。
RELEASE_TAG_PATTERN='^v[0-9A-Za-z._-]+$'
# 项目版本进入 JSON 文本并与 release tag 绑定：只接受无需 JSON 转义的字符。
PROJECT_VERSION_PATTERN='^[0-9A-Za-z._+-]+$'
COMMIT_PATTERN='^([0-9a-f]{40}|[0-9a-f]{64})$'
EPOCH_PATTERN='^[0-9]+$'

# 顶层入口与三个直接依赖类：thin JAR、未 shaded JAR 或 Spring Boot 重打包 JAR 都会在这
# 四个条目上失败。它们必须位于归档根（而不是 BOOT-INF/lib 之下）。
REQUIRED_ENTRIES=(
  "fun/fengwk/kkstudio/harness/daemon/DaemonMain.class"
  "okhttp3/OkHttpClient.class"
  "org/eclipse/jgit/ignore/FastIgnoreRule.class"
  "com/fasterxml/jackson/databind/ObjectMapper.class"
  "META-INF/THIRD_PARTY_NOTICES"
)

WORK_DIR=
STAGING_DIR=

usage() {
  cat <<'EOF'
Usage: scripts/release/prepare-daemon-release.sh <release-tag>

Stage the Environment Daemon release assets for one version tag. Nothing is
uploaded; every asset is written into a freshly rebuilt output directory.

  <release-tag>  Tag of the form v1.2.3: after "v" only letters, digits, ".",
                 "_" and "-" are accepted.
Staged assets:
  kk-studio-daemon-<tag>.jar          executable daemon
  kk-studio-daemon-<tag>.jar.sha256   sha256sum -c checksum for the above
  kk-studio-daemon-<tag>.json         deterministic metadata (schemaVersion 1)
  LICENSE                             Apache License 2.0
  THIRD_PARTY_NOTICES                 third-party component notices

Requires a JDK 21 (JAVA_HOME_21, JAVA_HOME, or one JDK's java/jar on PATH) and a
daemon JAR already built by `mvn -pl harness/daemon -am clean package`.

Assets are always staged under harness/daemon/target/release.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

# 只清理本脚本创建的两个临时目录；专用发布目录不在这里，它的删除是显式的。
cleanup() {
  if [ -n "$STAGING_DIR" ] && [ -d "$STAGING_DIR" ]; then
    rm -rf -- "$STAGING_DIR"
  fi
  if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
    rm -rf -- "$WORK_DIR"
  fi
}

trap cleanup EXIT

# 解析 JDK：优先仓库约定的 JAVA_HOME_21，其次 JAVA_HOME，最后同一个 JDK 的 PATH 工具。
resolve_java_home() {
  local candidate
  for candidate in "${JAVA_HOME_21:-}" "${JAVA_HOME:-}"; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] && [ -x "$candidate/bin/jar" ]; then
      assert_jdk21 "$candidate"
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  local java_path
  java_path=$(command -v java 2>/dev/null || true)
  if [ -n "$java_path" ]; then
    candidate=$(cd "$(dirname "$java_path")/.." && pwd -P)
    if [ -x "$candidate/bin/jar" ]; then
      assert_jdk21 "$candidate"
      printf '%s\n' "$candidate"
      return 0
    fi
  fi
  die "JDK 21 not found: set JAVA_HOME_21 or JAVA_HOME, or put 'java' and 'jar' from one JDK on PATH"
}

# 发布物以 JDK 21 为目标，所以校验也必须由 JDK 21 完成：更低版本的 JDK 要么无法读取
# 归档，要么会给出与真实原因无关的错误信息。
assert_jdk21() {
  local version
  version=$(env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS \
    "$1/bin/java" -version 2>&1 | head -1) ||
    die "cannot execute $1/bin/java"
  case "$version" in
    *'version "21"'* | *'version "21.'*) ;;
    *) die "JDK 21 is required (found: $version)" ;;
  esac
}

# manifest 属性值在 72 字节处折行，续行以单个空格开头；先展开再逐属性匹配。
manifest_flat() {
  sed -e ':a' -e 'N' -e '$!ba' -e 's/\n //g' "$1" | tr -d '\r'
}

manifest_attribute() {
  printf '%s\n' "$2" | sed -n "s/^$1:[[:space:]]*//p"
}

require_input() {
  if [ ! -f "$1" ] || [ ! -s "$1" ]; then
    die "$2"
  fi
}

# 校验待发布 JAR，并输出其 manifest 中的打包版本。
verify_daemon_jar() {
  local java_home=$1
  local jar_bin="$java_home/bin/jar"
  local java_bin="$java_home/bin/java"
  local jar="$DAEMON_JAR"
  local entry_list="$WORK_DIR/jar-entries.txt"
  local manifest_dir="$WORK_DIR/manifest"
  local entry manifest_text main_class project_version version_output

  require_input "$jar" \
    "daemon JAR not found or empty: $jar (build it first: mvn -pl harness/daemon -am clean package)"

  # 1) 归档必须可读：畸形或截断的 JAR 在这里失败。
  if ! "$jar_bin" --list --file "$jar" >"$entry_list" 2>/dev/null; then
    die "daemon JAR is not a readable archive: $jar"
  fi

  # 2) manifest 必须声明可执行入口与打包版本。
  mkdir -p "$manifest_dir"
  if ! (cd "$manifest_dir" && "$jar_bin" --extract --file "$jar" META-INF/MANIFEST.MF) >/dev/null 2>&1; then
    die "daemon JAR has no extractable META-INF/MANIFEST.MF: $jar"
  fi
  manifest_text=$(manifest_flat "$manifest_dir/META-INF/MANIFEST.MF")
  main_class=$(manifest_attribute Main-Class "$manifest_text")
  project_version=$(manifest_attribute Implementation-Version "$manifest_text")
  if [ "$main_class" != "$DAEMON_MAIN_CLASS" ]; then
    die "daemon JAR Main-Class must be $DAEMON_MAIN_CLASS (found '${main_class:-<missing>}')"
  fi
  if [[ ! $project_version =~ $PROJECT_VERSION_PATTERN ]]; then
    die "daemon JAR Implementation-Version is missing or unsafe (found '${project_version:-<missing>}')"
  fi

  # 3) 必须可执行：只有真正启动一次才能证明入口与内嵌依赖都可用，且报告的版本与
  #    manifest 中的打包版本一致。
  if ! version_output=$(env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS \
    "$java_bin" -jar "$jar" --version 2>&1); then
    die "daemon JAR is not executable: 'java -jar $jar --version' failed: $version_output"
  fi
  if [ "$version_output" != "kk-studio-daemon $project_version" ]; then
    die "daemon JAR '--version' output must be 'kk-studio-daemon $project_version' (found: $version_output)"
  fi

  # 4) 顶层依赖类必须内嵌：thin JAR 或未 shaded 的 JAR 在这里失败。
  for entry in "${REQUIRED_ENTRIES[@]}"; do
    if ! grep -Fxq -- "$entry" "$entry_list"; then
      die "daemon JAR is missing a top-level entry (not a shaded standalone JAR): $entry"
    fi
  done

  printf '%s\n' "$project_version"
}

main() {
  if [ $# -eq 1 ] && { [ "$1" = "-h" ] || [ "$1" = "--help" ]; }; then
    usage
    return 0
  fi
  if [ $# -ne 1 ]; then
    usage >&2
    return 2
  fi

  local release_tag=$1
  if [[ ! $release_tag =~ $RELEASE_TAG_PATTERN ]]; then
    die "release tag must match ${RELEASE_TAG_PATTERN}: $release_tag"
  fi

  local java_home
  java_home=$(resolve_java_home)
  if ! command -v sha256sum >/dev/null 2>&1; then
    die "missing command: sha256sum"
  fi
  require_input "$LICENSE_FILE" "license file not found or empty: $LICENSE_FILE"
  require_input "$THIRD_PARTY_NOTICES_FILE" \
    "third-party notices not found or empty: $THIRD_PARTY_NOTICES_FILE"

  WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/kk-studio-daemon-release.XXXXXX")

  # commit SHA 进入元数据，因此必须在真实 Git checkout 中运行。
  local commit source_date_epoch
  if ! commit=$(git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{commit}' 2>/dev/null); then
    die "cannot resolve HEAD: run this script inside a Git checkout"
  fi
  if [[ ! $commit =~ $COMMIT_PATTERN ]]; then
    die "unexpected commit id: $commit"
  fi
  source_date_epoch=$(git -C "$REPO_ROOT" show -s --format=%ct "$commit")
  if [[ ! $source_date_epoch =~ $EPOCH_PATTERN ]]; then
    die "cannot resolve commit timestamp for release metadata"
  fi

  local project_version
  project_version=$(verify_daemon_jar "$java_home")
  if [ "$release_tag" != "v$project_version" ]; then
    die "release tag must equal the packaged version: expected v$project_version, got $release_tag"
  fi

  local artifact="kk-studio-daemon-${release_tag}.jar"
  local checksum="$artifact.sha256"
  local metadata="kk-studio-daemon-${release_tag}.json"

  # 资产先进入 target 下的临时目录；全部写完并自校验后才整体替换固定发布目录，因此成功
  # 之前始终保留旧内容，中途失败也不会留下半套资产。
  STAGING_DIR=$(mktemp -d "$(dirname "$RELEASE_DIR")/.kk-studio-daemon-release.XXXXXX")

  local artifact_sha
  cp -- "$DAEMON_JAR" "$STAGING_DIR/$artifact"
  if ! (
    cd "$STAGING_DIR"
    sha256sum "$artifact" >"$checksum"
    sha256sum -c --status "$checksum"
  ); then
    die "staged artifact failed its own SHA-256 verification"
  fi
  artifact_sha=$(cut -d' ' -f1 <"$STAGING_DIR/$checksum")
  cp -- "$LICENSE_FILE" "$STAGING_DIR/LICENSE"
  cp -- "$THIRD_PARTY_NOTICES_FILE" "$STAGING_DIR/THIRD_PARTY_NOTICES"

  # 确定性元数据：字段与顺序固定，不含当前时钟，不随调用环境变化。所有值都来自受约束的
  # 字符集，因此这里生成的 JSON 不需要转义。
  cat >"$STAGING_DIR/$metadata" <<EOF
{
  "schemaVersion": $METADATA_SCHEMA_VERSION,
  "tag": "$release_tag",
  "commit": "$commit",
  "sourceDateEpoch": $source_date_epoch,
  "projectVersion": "$project_version",
  "minimumJava": $MINIMUM_JAVA,
  "mainClass": "$DAEMON_MAIN_CLASS",
  "artifact": "$artifact",
  "artifactSha256": "$artifact_sha"
}
EOF

  # RELEASE_DIR 是脚本内固定常量，不接收外部路径。
  rm -rf -- "$RELEASE_DIR"
  mv -- "$STAGING_DIR" "$RELEASE_DIR"
  STAGING_DIR=

  echo "staged daemon release ${release_tag} (commit ${commit}, project version ${project_version})"
  echo "output: ${RELEASE_DIR}"
  # 只列目录自身的内容（脚本刚创建，且恰好包含上面五个文件）。
  ls -1 -- "$RELEASE_DIR" | sed 's/^/  /'
}

main "$@"
