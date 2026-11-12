#!/usr/bin/env bash
# Validate and execute a Go daemon release archive on its native host.
set -euo pipefail

fail() { echo "ERROR: $*" >&2; exit 1; }
[ "$#" -eq 1 ] || fail 'usage: smoke-archive.sh <archive.tar.gz>'
archive=$1
[ -s "$archive" ] || fail "archive is missing or empty: $archive"
name=${archive##*/}
[[ $name =~ ^kk-studio-daemon-v[0-9A-Za-z._+-]+-(linux|macos)-(amd64|arm64)\.tar\.gz$ ]] ||
  fail "unexpected archive name: $name"
platform=${BASH_REMATCH[1]}-${BASH_REMATCH[2]}
case "$(uname -s)" in Linux) host=linux ;; Darwin) host=macos ;; *) fail 'unsupported smoke host' ;; esac
case "$(uname -m)" in x86_64|amd64) arch=amd64 ;; aarch64|arm64) arch=arm64 ;; *) fail 'unsupported smoke architecture' ;; esac
[ "$platform" = "$host-$arch" ] || fail "archive $platform does not match host $host-$arch"
paths=$(tar -tzf "$archive") || fail 'cannot list archive'
while IFS= read -r path; do
  case "$path" in /*|../*|..|*/../*|*/..|*\\*|*:) fail "unsafe archive path: $path" ;; esac
done <<<"$paths"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/kk-studio-daemon-smoke.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT
tar -xzf "$archive" -C "$tmp"
binary="$tmp/kk-studio-daemon"
[ -x "$binary" ] || fail 'archive is missing executable kk-studio-daemon'
[ -f "$tmp/runtime/code-server/out/node/entry.js" ] || fail 'archive is missing bundled code-server'
[ -f "$tmp/ide-extension/extension.cjs" ] || fail 'archive is missing IDE extension'
[ -f "$tmp/runtime-manifest.json" ] || fail 'archive is missing runtime manifest'
[ -f "$tmp/LICENSE" ] && [ -f "$tmp/THIRD_PARTY_NOTICES" ] || fail 'archive is missing legal notices'
for forbidden in main.js package.json node_modules native/lock-helper.js delay.js rg; do
  [ ! -e "$tmp/$forbidden" ] || fail "Go archive unexpectedly contains $forbidden"
done
version=$($binary --version 2>&1) || fail 'daemon --version failed'
[[ $version =~ ^kk-studio-daemon\ [0-9A-Za-z._+-]+$ ]] || fail "unexpected daemon version output: $version"
if [ "$platform" = linux-amd64 ] || [ "$platform" = linux-arm64 ]; then
  config_dir="$tmp/config"
  mkdir -m 700 "$config_dir"
  printf '%s\n' 'not a valid token' >"$config_dir/daemon.token"
  chmod 600 "$config_dir/daemon.token"
  printf '%s\n' '{"environmentId":"00000000-0000-4000-8000-000000000001","version":"1","daemonConfig":{"studioUrl":"https://example.com"}}' >"$config_dir/daemon.json"
  chmod 600 "$config_dir/daemon.json"
  $binary --check-config "$config_dir/daemon.json" >/dev/null || fail 'daemon --check-config failed'
fi
echo "archive smoke ok: $name ($version)"
