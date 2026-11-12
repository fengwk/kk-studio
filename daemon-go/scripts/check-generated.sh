#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"
GENERATED=daemon-go/internal/protocol/control.pb.go
BEFORE=$(sha256sum "$GENERATED" | cut -d ' ' -f 1)
"$ROOT/daemon-go/scripts/generate-protocol.sh"
AFTER=$(sha256sum "$GENERATED" | cut -d ' ' -f 1)
if [ "$BEFORE" != "$AFTER" ]; then
  echo "generated protocol code is stale; run daemon-go/scripts/generate-protocol.sh" >&2
  exit 1
fi
