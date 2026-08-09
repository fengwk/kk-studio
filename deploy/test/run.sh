#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
COMPOSE_FILE="$SCRIPT_DIR/compose.yaml"
COMPOSE=(docker compose -f "$COMPOSE_FILE")
WITH_APP=false

usage() {
  cat <<'EOF'
Usage: deploy/test/run.sh [--with-app]

Builds the current application image, starts the isolated test dependencies,
waits for health checks, verifies ffmpeg/ffprobe and dependency reachability,
then always removes containers, networks, and volumes.

Options:
  --with-app  Also start the application service and wait for its healthcheck.
  -h, --help  Show this help.
EOF
}

step() {
  printf '\n==> %s\n' "$*"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

while (($#)); do
  case "$1" in
    --with-app)
      WITH_APP=true
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown argument: $1"
      ;;
  esac
  shift
done

command -v docker >/dev/null 2>&1 || die "docker is required"
docker info >/dev/null 2>&1 || die "Docker daemon is not available"
docker compose version >/dev/null 2>&1 || die "docker compose is required"
if [[ "$WITH_APP" == "true" ]]; then
  command -v python3 >/dev/null 2>&1 || die "python3 is required for the Canvas API smoke"
fi

cleanup() {
  local status=$?
  if ((status != 0)); then
    step "Failure diagnostics"
    "${COMPOSE[@]}" ps -a || true
    "${COMPOSE[@]}" logs --no-color || true
  fi

  step "Cleaning isolated test stack"
  "${COMPOSE[@]}" --profile app down --volumes --remove-orphans || true
}
trap cleanup EXIT

step "Validating Compose configuration"
"${COMPOSE[@]}" config --quiet
"${COMPOSE[@]}" --profile app config --quiet

step "Building the current application Dockerfile"
"${COMPOSE[@]}" --profile app build app

step "Starting isolated dependencies and waiting for health checks"
if [[ "$WITH_APP" == "true" ]]; then
  "${COMPOSE[@]}" --profile app up -d --wait
else
  "${COMPOSE[@]}" up -d --wait
fi

step "Checking ffmpeg, ffprobe, and the non-root runtime user"
"${COMPOSE[@]}" --profile app run --rm --no-deps --entrypoint /bin/sh app -ec '
  test "$(id -un)" = "kkstudio"
  command -v ffmpeg
  command -v ffprobe
  ffmpeg -version | head -n 1
  ffprobe -version | head -n 1
'

step "Checking PostgreSQL"
postgres_result=$(
  "${COMPOSE[@]}" exec -T postgres \
    psql -U canvas_test -d canvas_test -v ON_ERROR_STOP=1 -Atqc "SELECT 1"
)
[[ "$postgres_result" == "1" ]] || die "PostgreSQL SELECT 1 returned '$postgres_result'"

step "Checking MinIO and initialized bucket"
"${COMPOSE[@]}" exec -T minio-init \
  /bin/sh -ec 'mc ready local >/dev/null && mc stat "local/$MINIO_BUCKET" >/dev/null'

step "Checking HTTP mock"
"${COMPOSE[@]}" exec -T http-mock python -c '
import json
import urllib.request

with urllib.request.urlopen("http://127.0.0.1:8080/health", timeout=2) as response:
    assert response.status == 200
    assert json.load(response) == {"status": "ok"}
'

if [[ "$WITH_APP" == "true" ]]; then
  step "Checking Canvas Resource reserve, direct PUT, finalize, and preview GET"
  CANVAS_TEST_APP_URL="http://127.0.0.1:${CANVAS_TEST_APP_PORT:-18088}" \
  CANVAS_TEST_IMAGE_FIXTURE="$SCRIPT_DIR/../../core/src/test/resources/fun/fengwk/kkstudio/core/studio/resource/tiny.png" \
    python3 - <<'PY'
import json
import os
import urllib.request
from pathlib import Path

base_url = os.environ["CANVAS_TEST_APP_URL"]
image = Path(os.environ["CANVAS_TEST_IMAGE_FIXTURE"]).read_bytes()


def json_call(method: str, path: str, body: dict | None = None) -> dict:
    data = None if body is None else json.dumps(body).encode()
    headers = {} if data is None else {"Content-Type": "application/json"}
    request = urllib.request.Request(
        base_url + path, data=data, headers=headers, method=method
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        assert 200 <= response.status < 300
        return json.load(response)["data"]


canvas = json_call("POST", "/api/canvases", {"title": "container-resource-smoke"})
reservation = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/uploads",
    {
        "kind": "IMAGE",
        "filename": "tiny.png",
        "mediaType": "image/png",
        "size": str(len(image)),
    },
)
assert "bucket" not in reservation and "key" not in reservation
put = urllib.request.Request(
    reservation["url"],
    data=image,
    headers=reservation.get("headers") or {},
    method=reservation["method"],
)
with urllib.request.urlopen(put, timeout=30) as response:
    assert response.status == 200

resource = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/uploads/{reservation['uploadId']}/complete",
)
assert resource["id"] == reservation["uploadId"]
assert resource["mediaType"] == "image/png"
metadata = json.loads(resource["metadataJson"])
assert metadata["width"] > 0 and metadata["height"] > 0

preview = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/resources/{resource['id']}/preview-url",
)
assert "bucket" not in preview and "key" not in preview
with urllib.request.urlopen(preview["url"], timeout=30) as response:
    body = response.read()
    assert response.status == 200
    assert body.startswith(b"RIFF") and b"WEBP" in body[:16]
PY
fi

step "All isolated container checks passed"
