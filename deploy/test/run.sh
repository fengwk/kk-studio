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

step "All isolated container checks passed"
