#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
PROTO=platform/daemon-server/src/main/protocol/control.proto
cd "$ROOT"
protoc \
  --proto_path=. \
  --go_out=daemon-go \
  --go_opt=module=github.com/fengwk/kk-studio/daemon \
  --go_opt=Mplatform/daemon-server/src/main/protocol/control.proto=github.com/fengwk/kk-studio/daemon/internal/protocol \
  "$PROTO"
