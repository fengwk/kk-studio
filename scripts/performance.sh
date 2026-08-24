#!/usr/bin/env bash

# scripts/performance.sh — free, machine-local performance baseline.
#
# The Docker lifecycle deliberately stays here so the Node runner remains a
# dependency-free HTTP benchmark that can also be exercised against a local fake.

set -Eeuo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
COMPOSE_FILE="$REPO_ROOT/deploy/test/compose.yaml"
APP_IMAGE=${CANVAS_TEST_APP_IMAGE:-kk-studio-app:performance-baseline}
DURATION_SECONDS=10
REPORT_ROOT="$REPO_ROOT/reports/performance"
SKIP_BUILD=false
RUNNER_PID=

usage() {
  node "$SCRIPT_DIR/performance/runner.mjs" --help
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

require_value() {
  local option=$1
  local value=${2-}
  [[ -n "$value" && "$value" != --* ]] || die "$option requires a value"
  printf '%s' "$value"
}

validate_duration() {
  local value=$1
  [[ "$value" =~ ^(0|[1-9][0-9]*)$ ]] || die "--duration-seconds must be an integer between 1 and 120: $value"
  ((value >= 1 && value <= 120)) || die "--duration-seconds must be between 1 and 120: $value"
}

validate_report_root() {
  node --input-type=module - "$REPORT_ROOT" "$REPO_ROOT" >/dev/null <<'NODE'
import { validateReportRoot } from './scripts/performance/runner.mjs'

try {
  validateReportRoot(process.argv[2], { repoRoot: process.argv[3] })
} catch (error) {
  console.error(`ERROR: ${error.message}`)
  process.exit(2)
}
NODE
}

proxy_host() {
  local proxy=$1
  local authority

  [[ -n "$proxy" ]] || return 1
  authority=${proxy#*://}
  authority=${authority%%/*}
  authority=${authority##*@}
  if [[ "$authority" == \[* ]]; then
    authority=${authority#\[}
    printf '%s' "${authority%%\]*}"
  else
    printf '%s' "${authority%%:*}"
  fi
}

is_loopback_proxy() {
  local host
  host=$(proxy_host "$1") || return 1
  case "${host,,}" in
    127.0.0.1 | localhost | ::1)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

while (($#)); do
  case "$1" in
    --duration-seconds)
      DURATION_SECONDS=$(require_value "$1" "${2-}")
      validate_duration "$DURATION_SECONDS"
      shift 2
      ;;
    --report-root)
      REPORT_ROOT=$(require_value "$1" "${2-}")
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

command -v node >/dev/null 2>&1 || die "node is required"
command -v docker >/dev/null 2>&1 || die "docker is required"
docker info >/dev/null 2>&1 || die "Docker daemon is not available"
docker compose version >/dev/null 2>&1 || die "docker compose is required"

cd "$REPO_ROOT"
validate_report_root

# Keep the compose ports and the application URL frozen for comparable runs.
export CANVAS_TEST_APP_IMAGE="$APP_IMAGE"
export CANVAS_TEST_APP_PORT=18088
export CANVAS_TEST_PG_PORT=15432
export CANVAS_TEST_MINIO_PORT=19000
export CANVAS_TEST_MOCK_PORT=18089
export PERFORMANCE_IMAGE="$APP_IMAGE"
export PERFORMANCE_SKIP_BUILD="$SKIP_BUILD"

COMPOSE=(docker compose -f "$COMPOSE_FILE")

cleanup() {
  local status=$?
  if ((status != 0)); then
    printf '\n==> Performance baseline failed; collecting compose diagnostics\n' >&2
    "${COMPOSE[@]}" --profile app ps -a >&2 || true
    "${COMPOSE[@]}" --profile app logs --no-color >&2 || true
  fi
  printf '\n==> Cleaning isolated performance stack\n' >&2
  "${COMPOSE[@]}" --profile app down --volumes --remove-orphans >/dev/null 2>&1 || true
}

on_signal() {
  if [[ -n "$RUNNER_PID" ]] && kill -0 "$RUNNER_PID" 2>/dev/null; then
    kill -TERM "$RUNNER_PID" 2>/dev/null || true
    wait "$RUNNER_PID" 2>/dev/null || true
    RUNNER_PID=
  fi
  exit 130
}

trap cleanup EXIT
trap on_signal INT TERM

printf '\n==> Validating isolated compose configuration\n'
"${COMPOSE[@]}" --profile app config --quiet

printf '\n==> Resetting isolated performance stack\n'
"${COMPOSE[@]}" --profile app down --volumes --remove-orphans

if [[ "$SKIP_BUILD" != "true" ]]; then
  BUILD_HTTP_PROXY=${CANVAS_TEST_BUILD_HTTP_PROXY:-${HTTP_PROXY:-${http_proxy:-}}}
  BUILD_HTTPS_PROXY=${CANVAS_TEST_BUILD_HTTPS_PROXY:-${HTTPS_PROXY:-${https_proxy:-}}}
  BUILD_NO_PROXY=${CANVAS_TEST_BUILD_NO_PROXY:-${NO_PROXY:-${no_proxy:-}}}
  BUILD_NETWORK=${CANVAS_TEST_BUILD_NETWORK:-default}
  if [[ -z "${CANVAS_TEST_BUILD_NETWORK+x}" ]] && (
    is_loopback_proxy "$BUILD_HTTP_PROXY" ||
      is_loopback_proxy "$BUILD_HTTPS_PROXY"
  ); then
    BUILD_NETWORK=host
  fi

  printf '\n==> Building current deploy/local/Dockerfile image\n'
  docker build \
    --file "$REPO_ROOT/deploy/local/Dockerfile" \
    --tag "$APP_IMAGE" \
    --quiet \
    --network "$BUILD_NETWORK" \
    --build-arg "KK_STUDIO_BUILD_HTTP_PROXY=$BUILD_HTTP_PROXY" \
    --build-arg "KK_STUDIO_BUILD_HTTPS_PROXY=$BUILD_HTTPS_PROXY" \
    --build-arg "KK_STUDIO_BUILD_NO_PROXY=$BUILD_NO_PROXY" \
    --build-arg "KK_STUDIO_MAVEN_BUILD_OPTS=${CANVAS_TEST_BUILD_MAVEN_OPTS:-}" \
    "$REPO_ROOT"
else
  printf '\n==> Reusing application image %s\n' "$APP_IMAGE"
  docker image inspect "$APP_IMAGE" >/dev/null 2>&1 || die "application image not found for --skip-build: $APP_IMAGE"
fi

printf '\n==> Starting isolated PostgreSQL/MinIO/mock/app stack\n'
"${COMPOSE[@]}" --profile app up -d --wait --no-build

printf '\n==> Running Node performance baseline\n'
RUNNER_ARGS=(
  --duration-seconds "$DURATION_SECONDS"
  --report-root "$REPORT_ROOT"
)
if [[ "$SKIP_BUILD" == "true" ]]; then
  RUNNER_ARGS+=(--skip-build)
fi

set +e
node "$SCRIPT_DIR/performance/runner.mjs" "${RUNNER_ARGS[@]}" &
RUNNER_PID=$!
wait "$RUNNER_PID"
RUNNER_STATUS=$?
RUNNER_PID=
set -e

exit "$RUNNER_STATUS"
