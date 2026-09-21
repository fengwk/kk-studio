#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  RUN_REAL_SEEDANCE_PREPARE_SMOKE=1 \
  SEEDANCE_WORKSPACE_ID=... \
  OPENCLI_HUB_BASE_URL=... \
  scripts/dev/verify/smoke/seedance-prepare.sh --confirm-prepare-only

This script calls OpenCLI Hub directly. It is hard-coded to:
  model_version=seedance2.0fast
  duration=4
  submit=0

It never starts a Canvas FunctionRun and never imports a generated video.
EOF
}

if [[ "${1:-}" != "--confirm-prepare-only" || $# -ne 1 ]]; then
  usage >&2
  exit 2
fi
if [[ "${RUN_REAL_SEEDANCE_PREPARE_SMOKE:-}" != "1" ]]; then
  echo "RUN_REAL_SEEDANCE_PREPARE_SMOKE=1 is required" >&2
  exit 2
fi
if [[ -z "${SEEDANCE_WORKSPACE_ID:-}" ]]; then
  echo "SEEDANCE_WORKSPACE_ID is required" >&2
  exit 2
fi
if [[ -z "${OPENCLI_HUB_BASE_URL:-}" ]]; then
  echo "OPENCLI_HUB_BASE_URL is required; set it to the OpenCLI Hub HTTP(S) origin" >&2
  exit 2
fi

if ! HUB_URL=$(
  OPENCLI_HUB_BASE_URL="$OPENCLI_HUB_BASE_URL" python3 - <<'PY'
import os
import sys
from urllib.parse import urlsplit, urlunsplit

value = os.environ["OPENCLI_HUB_BASE_URL"]
try:
    parsed = urlsplit(value)
    hostname = parsed.hostname
    parsed.port
except ValueError:
    print("OPENCLI_HUB_BASE_URL must be an HTTP(S) origin", file=sys.stderr)
    raise SystemExit(2)

if (
    parsed.scheme.lower() not in {"http", "https"}
    or hostname is None
    or parsed.username is not None
    or parsed.password is not None
    or "?" in value
    or "#" in value
    or parsed.path not in ("", "/")
    or parsed.netloc.endswith(":")
    or any(character.isspace() for character in parsed.netloc)
):
    print("OPENCLI_HUB_BASE_URL must be an HTTP(S) origin", file=sys.stderr)
    raise SystemExit(2)

print(urlunsplit((parsed.scheme.lower(), parsed.netloc, "", "", "")))
PY
); then
  exit 2
fi
PROMPT=${SEEDANCE_PREPARE_SMOKE_PROMPT:-Prepare-only smoke. Do not submit generation.}

request_body=$(
  HUB_INSTANCE_ID="${OPENCLI_HUB_INSTANCE_ID:-}" \
  WORKSPACE_ID="$SEEDANCE_WORKSPACE_ID" \
  PROMPT="$PROMPT" \
    python3 - <<'PY'
import json
import os

body = {
    "argv": [
        "jimeng-agent",
        "video",
        "--workspace",
        os.environ["WORKSPACE_ID"],
        "--ratio",
        "16:9",
        "--model_version",
        "seedance2.0fast",
        "--duration",
        "4",
        "--prompt",
        os.environ["PROMPT"],
        "--submit",
        "0",
        "--retry",
        "0",
    ],
    "timeoutMillis": 600000,
}
if os.environ["HUB_INSTANCE_ID"]:
    body["instanceId"] = os.environ["HUB_INSTANCE_ID"]
print(json.dumps(body, separators=(",", ":")))
PY
)

execution_id=$(
  curl -fsS --connect-timeout 10 --max-time 620 \
    -H 'Content-Type: application/json' \
    --data-binary "$request_body" \
    "$HUB_URL/api/opencli/execute" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])'
)

for _ in $(seq 1 80); do
  response=$(
    curl -fsS --connect-timeout 10 --max-time 20 \
      "$HUB_URL/api/executions/$execution_id?waitSeconds=10"
  )
  status=$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["status"])')
  case "$status" in
    PENDING | RUNNING) ;;
    SUCCEEDED)
      RESPONSE="$response" python3 - <<'PY'
import json
import os

execution = json.loads(os.environ["RESPONSE"])["data"]
stdout = json.loads(execution["stdout"])
row = stdout[0] if isinstance(stdout, list) else stdout
assert row["status"] == "prepared", row
assert row["submitted"] is False, row
print("Seedance prepare-only smoke passed: prepared=true, submitted=false")
PY
      exit 0
      ;;
    *)
      echo "Seedance prepare-only Hub execution ended as $status" >&2
      exit 1
      ;;
  esac
done

echo "Seedance prepare-only smoke timed out" >&2
exit 1
