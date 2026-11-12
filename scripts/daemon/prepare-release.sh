#!/usr/bin/env bash
# Build and stage an official Go Daemon release archive for one supported platform.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
if [ -n "${KK_STUDIO_REPO_ROOT:-}" ]; then
  REPO_ROOT=$(cd "$KK_STUDIO_REPO_ROOT" && pwd -P)
else
  REPO_ROOT=$SCRIPT_DIR
  while [ "$REPO_ROOT" != "/" ] && [ ! -e "$REPO_ROOT/.git" ]; do REPO_ROOT=$(dirname "$REPO_ROOT"); done
  [ -e "$REPO_ROOT/.git" ] || { echo 'ERROR: cannot locate repository root' >&2; exit 1; }
fi
GO_DIR="$REPO_ROOT/daemon-go"
MANIFEST="$GO_DIR/runtime-manifest.json"
EXTENSION="$GO_DIR/ide-extension"
RELEASE_ROOT="$REPO_ROOT/daemon-go/release"
THIRD_PARTY_NOTICES="$GO_DIR/THIRD_PARTY_NOTICES"
GO_BIN=${GO_BIN:-/usr/local/opt/go/bin/go}
NODE_BIN=${NODE_BIN:-node}
WORK_DIR=
STAGING_DIR=
trap 'if [ -n "$WORK_DIR" ]; then rm -rf -- "$WORK_DIR"; fi; if [ -n "$STAGING_DIR" ]; then rm -rf -- "$STAGING_DIR"; fi' EXIT

usage() {
  echo 'Usage: scripts/daemon/prepare-release.sh <tag> <os> <arch>'
  echo 'Stages a Go Daemon tar.gz and its SHA256 and metadata under daemon-go/release/<os>-<arch>.'
}
die() { echo "ERROR: $*" >&2; exit 1; }
sha256_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

main() {
  if [ "$#" -eq 1 ] && { [ "$1" = -h ] || [ "$1" = --help ]; }; then usage; return 0; fi
  [ "$#" -eq 3 ] || { usage >&2; return 2; }
  local tag=$1 os=$2 arch=$3 goos release_key version binary_name platform
  [[ $tag =~ ^v[0-9A-Za-z._-]+$ ]] || die 'invalid release tag'
  [[ $os =~ ^(linux|macos|windows)$ && $arch =~ ^(amd64|arm64)$ ]] || die 'unsupported platform'
  if [ "$os" = windows ] && [ "$arch" = arm64 ]; then die 'windows-arm64 has no bundled code-server runtime'; fi
  case "$os" in linux) goos=linux ;; macos) goos=darwin ;; windows) goos=windows ;; esac
  release_key="$os-$arch"
  [ -x "$GO_BIN" ] || command -v "$GO_BIN" >/dev/null 2>&1 || die "missing Go compiler: $GO_BIN"
  [ -f "$MANIFEST" ] || die "runtime manifest missing: $MANIFEST"
  [ -d "$EXTENSION" ] || die "IDE extension missing: $EXTENSION"
  [ -s "$REPO_ROOT/LICENSE" ] || die 'LICENSE is missing'
  [ -s "$THIRD_PARTY_NOTICES" ] || die 'THIRD_PARTY_NOTICES is missing'
  version=$(sed -n 's/^var DaemonVersion = "\([^"]*\)"$/\1/p' "$GO_DIR/internal/config/version.go")
  [ -n "$version" ] || die 'cannot read daemon-go version'
  [ "$tag" = "v$version" ] || die "release tag must equal Go daemon version v$version"
  platform=$($NODE_BIN -e 'const m=require(process.argv[1]);const k=process.argv[2];const a=m.codeServer?.[k];if(!a)process.exit(2);process.stdout.write(JSON.stringify(a))' "$MANIFEST" "$release_key") || die "runtime manifest has no asset for $release_key"
  local runtime_url runtime_sha server_version
  runtime_url=$(printf '%s' "$platform" | $NODE_BIN -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(JSON.parse(s).url))')
  runtime_sha=$(printf '%s' "$platform" | $NODE_BIN -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(JSON.parse(s).sha256))')
  server_version=$("$NODE_BIN" -e 'process.stdout.write(String(JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8")).codeServerVersion))' "$MANIFEST")
  [[ $runtime_url =~ ^https://github\.com/fengwk/code-server/releases/download/ ]] || die 'runtime URL is not an approved code-server release asset'
  [[ $runtime_sha =~ ^[0-9a-f]{64}$ ]] || die 'runtime SHA256 is not pinned'
  local commit epoch artifact checksum metadata bin_name
  commit=$(git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{commit}') || die 'cannot resolve Git HEAD'
  epoch=$(git -C "$REPO_ROOT" show -s --format=%ct "$commit") || die 'cannot resolve commit timestamp'
  artifact="kk-studio-daemon-${tag}-${os}-${arch}.tar.gz"
  checksum="$artifact.sha256"
  metadata="kk-studio-daemon-${tag}-${os}-${arch}.json"
  if [ "$os" = windows ]; then bin_name=kk-studio-daemon.exe; else bin_name=kk-studio-daemon; fi
  WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/kk-studio-daemon-release.XXXXXX")
  STAGING_DIR=$(mktemp -d "$(dirname "$RELEASE_ROOT")/.kk-studio-daemon-stage.XXXXXX")
  mkdir -p "$STAGING_DIR/runtime/code-server" "$STAGING_DIR/ide-extension"
  (cd "$GO_DIR" && GOOS="$goos" GOARCH="$arch" CGO_ENABLED=0 "$GO_BIN" build -trimpath -ldflags "-X github.com/fengwk/kk-studio/daemon/internal/config.DaemonVersion=$version" -o "$WORK_DIR/$bin_name" ./cmd/kk-studio-daemon)
  [ -x "$WORK_DIR/$bin_name" ] || die 'Go daemon build is not executable'
  if [ "$goos" = "linux" ] && [ "$arch" = "$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')" ]; then
    [ "$("$WORK_DIR/$bin_name" --version)" = "kk-studio-daemon $version" ] || die '--version mismatch'
  fi
  local runtime_archive
  runtime_archive="$WORK_DIR/code-server.tar.gz"
  curl -q --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --output "$runtime_archive" "$runtime_url"
  [ "$(sha256_of "$runtime_archive")" = "$runtime_sha" ] || die 'code-server SHA256 mismatch'
  tar -xzf "$runtime_archive" -C "$STAGING_DIR/runtime/code-server" --strip-components=1
  # The Go daemon replaces the bundled search binary; keep code-server's own Node runtime but omit rg.
  find "$STAGING_DIR/runtime/code-server" -type d -name 'ripgrep*' -prune -exec rm -rf -- {} +
  find "$STAGING_DIR/runtime/code-server" -type f \( -name rg -o -name rg.exe \) -delete
  cp "$WORK_DIR/$bin_name" "$STAGING_DIR/$bin_name"
  cp -R "$EXTENSION/." "$STAGING_DIR/ide-extension/"
  if [ -d "$GO_DIR/resources" ]; then cp -R "$GO_DIR/resources" "$STAGING_DIR/resources"; fi
  cp "$REPO_ROOT/LICENSE" "$STAGING_DIR/LICENSE"
  cp "$THIRD_PARTY_NOTICES" "$STAGING_DIR/THIRD_PARTY_NOTICES"
  "$NODE_BIN" -e 'const fs=require("node:fs");const manifest=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));const key=process.argv[2];fs.writeFileSync(process.argv[3],JSON.stringify({daemonVersion:process.argv[4],codeServerVersion:manifest.codeServerVersion,codeServer:{[key]:manifest.codeServer[key]}},null,2)+"\n")' "$MANIFEST" "$release_key" "$STAGING_DIR/runtime-manifest.json" "$version"
  (cd "$STAGING_DIR" && tar --sort=name --mtime="@$epoch" --owner=0 --group=0 --numeric-owner -czf "$WORK_DIR/$artifact" .)
  local digest
  digest=$(sha256_of "$WORK_DIR/$artifact")
  printf '%s  %s\n' "$digest" "$artifact" >"$WORK_DIR/$checksum"
  cat >"$WORK_DIR/$metadata" <<EOF
{
  "tag": "$tag",
  "commit": "$commit",
  "sourceDateEpoch": $epoch,
  "projectVersion": "$version",
  "os": "$os",
  "arch": "$arch",
  "entrypoint": "$bin_name",
  "codeServerVersion": "$server_version",
  "artifact": "$artifact",
  "artifactSha256": "$digest"
}
EOF
  local output="$RELEASE_ROOT/$release_key"
  rm -rf -- "$output"
  mkdir -p "$output"
  cp "$WORK_DIR/$artifact" "$WORK_DIR/$checksum" "$WORK_DIR/$metadata" "$output/"
  cp "$REPO_ROOT/LICENSE" "$output/LICENSE"
  cp "$THIRD_PARTY_NOTICES" "$output/THIRD_PARTY_NOTICES"
  echo "staged Go daemon release $tag for $release_key (commit $commit)"
}
main "$@"
