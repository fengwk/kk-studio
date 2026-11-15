#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT HUP INT TERM
"$ROOT/daemon/scripts/generate-protocol.sh" "$TEMP"
GENERATED=daemon/internal/protocol/control.pb.go
CANDIDATE=$TEMP/internal/protocol/control.pb.go
if ! cmp -s "$GENERATED" "$CANDIDATE"; then
  echo "generated protocol code is stale; run daemon/scripts/generate-protocol.sh" >&2
  exit 1
fi
