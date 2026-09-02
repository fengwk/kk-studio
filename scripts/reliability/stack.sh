#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
COMPOSE_FILE="$REPO_ROOT/deploy/reliability/compose.yaml"
PROJECT_NAME=kk-studio-reliability
RELIABILITY_APP_PORT=${RELIABILITY_APP_PORT:-18091}
RELIABILITY_ENV_NAME=${RELIABILITY_ENV_NAME:-docker-reliability}
RELIABILITY_REGISTRATION_TOKEN=${RELIABILITY_REGISTRATION_TOKEN:-e2e-token-docker-reliability}
APP_URL="http://127.0.0.1:$RELIABILITY_APP_PORT"
TMP_DIR=

export RELIABILITY_APP_PORT RELIABILITY_ENV_NAME RELIABILITY_REGISTRATION_TOKEN

cleanup() {
  if [ -n "$TMP_DIR" ] && [ -d "$TMP_DIR" ]; then
    rm -rf "$TMP_DIR"
  fi
}
trap cleanup EXIT

step() {
  printf '==> %s\n' "$*"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

compose() {
  docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" "$@"
}

usage() {
  cat <<'EOF'
Usage: scripts/reliability/stack.sh <command> [arguments]

Commands:
  up
      Build and start the isolated stack, wait for app health and daemon READY,
      then synchronize the optional TEST_MINIMAX credential pair from the host.
  snapshot
      Copy clean snapshots from the non-empty PI_ANCHOR and PI_BASE_ANCHOR
      Git worktrees into the named volume.
  case-reset <case-id> <pi|pi-base>
      Replace one writable case clone from the selected volume snapshot.
  case-deps <case-id>
      Run npm ci for one case in an ephemeral egress-enabled helper container.
  inspect
      Fail-closed isolation, runtime-tooling, and Environment READY checks.
  tool-smoke
      Compile and run direct Java find/grep/bash smoke assertions inside Daemon.
  logs [postgres|app|workspace-init|daemon ...]
      Print the last RELIABILITY_LOG_TAIL lines (default 200).
  status
      Print service state and the public Environment status without commands/env.
  down [--volumes]
      Stop this Compose project. Add --volumes for complete project data cleanup.
  help
      Show this help without contacting Docker.

Snapshot environment:
  PI_ANCHOR=/path/to/pi
  PI_BASE_ANCHOR=/path/to/pi-base
EOF
}

assert_safe_config() {
  local config
  config=$(compose config)
  if grep -Eq 'TEST_MINIMAX|MINIMAX_(API_KEY|BASE_URL)|type:[[:space:]]*bind' <<<"$config"; then
    die "expanded reliability Compose config contains a forbidden credential field or bind mount"
  fi
}

daemon_container_id() {
  local container_id
  container_id=$(compose ps --quiet daemon)
  [ -n "$container_id" ] || die "daemon container is not running; run 'up' first"
  printf '%s\n' "$container_id"
}

wait_app() {
  local attempt
  for attempt in $(seq 1 180); do
    if curl -fsS "$APP_URL/actuator/health" >/dev/null 2>&1; then
      step "app healthy at $APP_URL"
      return
    fi
    sleep 1
  done
  die "timed out waiting for app health at $APP_URL"
}

environment_is_ready() {
  curl -fsS "$APP_URL/api/ai/environments" 2>/dev/null \
    | python3 -c '
import json
import sys

name = sys.argv[1]
rows = json.load(sys.stdin).get("data") or []
match = next((row for row in rows if row.get("name") == name), None)
raise SystemExit(0 if match and match.get("status") == "READY" and match.get("ready") is True else 1)
' "$RELIABILITY_ENV_NAME"
}

print_environment_status() {
  curl -fsS "$APP_URL/api/ai/environments" \
    | python3 -c '
import json
import sys

name = sys.argv[1]
rows = json.load(sys.stdin).get("data") or []
match = next((row for row in rows if row.get("name") == name), None)
if match is None:
    raise SystemExit(f"environment not found: {name}")
print("environment={} status={} ready={}".format(
    name, match.get("status"), match.get("ready")))
' "$RELIABILITY_ENV_NAME"
}

wait_environment() {
  local attempt
  for attempt in $(seq 1 180); do
    if environment_is_ready; then
      print_environment_status
      return
    fi
    sleep 1
  done
  die "timed out waiting for Environment READY: $RELIABILITY_ENV_NAME"
}

up_stack() {
  require_cmd docker
  require_cmd curl
  require_cmd python3
  assert_safe_config
  step "building and starting Compose project $PROJECT_NAME"
  compose up --detach --build
  wait_app
  wait_environment
  step "synchronizing optional MiniMax E2E credentials (secret values are never printed)"
  BACKEND_URL="$APP_URL" \
    TEST_MINIMAX_API_KEY="${TEST_MINIMAX_API_KEY-}" \
    TEST_MINIMAX_BASE_URL="${TEST_MINIMAX_BASE_URL-}" \
    python3 "$REPO_ROOT/scripts/e2e/sync_provider_credentials.py" --backend-url "$APP_URL"
}

require_clean_anchor() {
  local source=$1
  local label=$2
  [ -d "$source" ] || die "$label anchor does not exist: $source"
  [ "$(git -C "$source" rev-parse --is-inside-work-tree 2>/dev/null || true)" = "true" ] \
    || die "$label anchor is not a Git worktree: $source"
  if [ -n "$(git -C "$source" status --porcelain)" ]; then
    die "$label anchor is dirty; commit/stash/remove changes before snapshot: $source"
  fi
}

require_snapshot_anchors() {
  [ -n "${PI_ANCHOR-}" ] \
    || die "snapshot requires non-empty PI_ANCHOR (set it to the pi Git worktree path)"
  [ -n "${PI_BASE_ANCHOR-}" ] \
    || die "snapshot requires non-empty PI_BASE_ANCHOR (set it to the pi-base Git worktree path)"
}

remove_remotes() {
  local repository=$1
  local remote
  while IFS= read -r remote; do
    if [ -n "$remote" ]; then
      git -C "$repository" remote remove "$remote"
    fi
  done < <(git -C "$repository" remote)
}

clone_anchor() {
  local source=$1
  local destination=$2
  local expected_sha=$3
  local actual_sha
  git clone --quiet --no-local --no-hardlinks -- "$source" "$destination"
  actual_sha=$(git -C "$destination" rev-parse HEAD)
  [ "$actual_sha" = "$expected_sha" ] \
    || die "snapshot clone HEAD mismatch: expected $expected_sha, got $actual_sha"
  git -C "$destination" checkout --quiet -B reliability-baseline "$expected_sha"
  remove_remotes "$destination"
  [ -z "$(git -C "$destination" remote)" ] || die "failed to remove snapshot remotes: $destination"
  [ -z "$(git -C "$destination" status --porcelain)" ] \
    || die "temporary snapshot clone is unexpectedly dirty: $destination"
}

snapshot_anchors() {
  require_snapshot_anchors
  require_cmd docker
  require_cmd git
  require_cmd mktemp
  require_cmd tar
  daemon_container_id >/dev/null

  local pi_source="$PI_ANCHOR"
  local pi_base_source="$PI_BASE_ANCHOR"
  local pi_sha_before pi_base_sha_before

  require_clean_anchor "$pi_source" pi
  require_clean_anchor "$pi_base_source" pi-base
  pi_sha_before=$(git -C "$pi_source" rev-parse HEAD)
  pi_base_sha_before=$(git -C "$pi_base_source" rev-parse HEAD)

  TMP_DIR=$(mktemp -d)
  clone_anchor "$pi_source" "$TMP_DIR/pi" "$pi_sha_before"
  clone_anchor "$pi_base_source" "$TMP_DIR/pi-base" "$pi_base_sha_before"

  [ "$(git -C "$pi_source" rev-parse HEAD)" = "$pi_sha_before" ] \
    || die "pi anchor HEAD changed while preparing snapshot"
  [ "$(git -C "$pi_base_source" rev-parse HEAD)" = "$pi_base_sha_before" ] \
    || die "pi-base anchor HEAD changed while preparing snapshot"
  require_clean_anchor "$pi_source" pi
  require_clean_anchor "$pi_base_source" pi-base

  step "copying detached Git snapshots into the daemon named volume"
  tar -C "$TMP_DIR" -cf - pi pi-base \
    | compose exec --no-TTY --user 10001:10001 daemon bash -c '
set -euo pipefail
next=/workspace/anchors.next
previous=/workspace/anchors.previous
rm -rf "$next" "$previous"
mkdir -p "$next"
tar -xf - -C "$next"
if [ -e /workspace/anchors ]; then
  mv /workspace/anchors "$previous"
fi
if mv "$next" /workspace/anchors; then
  rm -rf "$previous"
else
  if [ -e "$previous" ]; then
    mv "$previous" /workspace/anchors
  fi
  exit 1
fi
'

  local pi_volume_sha pi_base_volume_sha
  pi_volume_sha=$(compose exec --no-TTY --user 10001:10001 daemon \
    git -C /workspace/anchors/pi rev-parse HEAD)
  pi_base_volume_sha=$(compose exec --no-TTY --user 10001:10001 daemon \
    git -C /workspace/anchors/pi-base rev-parse HEAD)
  [ "$pi_volume_sha" = "$pi_sha_before" ] \
    || die "pi volume snapshot HEAD mismatch: expected $pi_sha_before, got $pi_volume_sha"
  [ "$pi_base_volume_sha" = "$pi_base_sha_before" ] \
    || die "pi-base volume snapshot HEAD mismatch: expected $pi_base_sha_before, got $pi_base_volume_sha"

  require_clean_anchor "$pi_source" pi
  require_clean_anchor "$pi_base_source" pi-base
  [ "$(git -C "$pi_source" rev-parse HEAD)" = "$pi_sha_before" ] \
    || die "pi anchor HEAD changed during snapshot"
  [ "$(git -C "$pi_base_source" rev-parse HEAD)" = "$pi_base_sha_before" ] \
    || die "pi-base anchor HEAD changed during snapshot"

  rm -rf "$TMP_DIR"
  TMP_DIR=
  printf 'anchor=pi baseline_sha=%s path=/workspace/anchors/pi\n' "$pi_sha_before"
  printf 'anchor=pi-base baseline_sha=%s path=/workspace/anchors/pi-base\n' "$pi_base_sha_before"
}

validate_case_id() {
  local case_id=$1
  [[ "$case_id" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] \
    || die "invalid case id '$case_id' (expected ^[a-z0-9][a-z0-9-]{0,63}$)"
}

reset_case() {
  local case_id=${1-}
  local anchor=${2-}
  [ -n "$case_id" ] && [ -n "$anchor" ] \
    || die "case-reset requires <case-id> <pi|pi-base>"
  validate_case_id "$case_id"
  case "$anchor" in
    pi | pi-base) ;;
    *) die "invalid anchor '$anchor' (expected pi or pi-base)" ;;
  esac

  require_cmd docker
  daemon_container_id >/dev/null
  compose exec --no-TTY --user 10001:10001 daemon bash -s -- "$case_id" "$anchor" <<'EOF'
set -euo pipefail
case_id=$1
anchor=$2
source="/workspace/anchors/$anchor"
target="/workspace/cases/$case_id"
next="/workspace/cases/.${case_id}.next"
previous="/workspace/cases/.${case_id}.previous"

git -C "$source" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
  || { echo "ERROR: snapshot anchor is missing: $source" >&2; exit 1; }
mkdir -p /workspace/cases
rm -rf "$next" "$previous"
git clone --quiet --no-local -- "$source" "$next"
while IFS= read -r remote; do
  if [ -n "$remote" ]; then
    git -C "$next" remote remove "$remote"
  fi
done < <(git -C "$next" remote)
[ -z "$(git -C "$next" remote)" ]
[ -z "$(git -C "$next" status --porcelain)" ]
baseline_sha=$(git -C "$next" rev-parse HEAD)
touch "$next/.reliability-write-check"
rm "$next/.reliability-write-check"

if [ -e "$target" ]; then
  mv "$target" "$previous"
fi
if mv "$next" "$target"; then
  rm -rf "$previous"
else
  if [ -e "$previous" ]; then
    mv "$previous" "$target"
  fi
  exit 1
fi
printf 'case_path=%s baseline_sha=%s anchor=%s\n' "$target" "$baseline_sha" "$anchor"
EOF
}

install_case_dependencies() {
  local case_id=${1-}
  [ -n "$case_id" ] || die "case-deps requires <case-id>"
  validate_case_id "$case_id"
  require_cmd docker

  local container_id image_id workspace_volume network_name
  container_id=$(daemon_container_id)
  image_id=$(docker inspect --format '{{.Image}}' "$container_id")
  workspace_volume=$(docker inspect "$container_id" | python3 -c '
import json
import sys

container = json.load(sys.stdin)[0]
matches = [
    mount for mount in container.get("Mounts") or []
    if mount.get("Type") == "volume" and mount.get("Destination") == "/workspace"
]
if len(matches) != 1 or not matches[0].get("Name"):
    raise SystemExit("daemon workspace named volume was not found")
print(matches[0]["Name"])
')
  network_name="${PROJECT_NAME}_app-ingress"
  docker network inspect "$network_name" >/dev/null 2>&1 \
    || die "reliability egress network is missing: $network_name"
  compose exec --no-TTY --user 10001:10001 daemon bash -s -- "$case_id" <<'EOF'
set -euo pipefail
case_id=$1
test -d "/workspace/cases/$case_id" \
  || { echo "ERROR: case is missing: /workspace/cases/$case_id" >&2; exit 1; }
mkdir -p /workspace/.reliability-home /workspace/.npm-cache
EOF

  step "installing dependencies for case=$case_id in an ephemeral helper"
  docker run --rm --init \
    --user 10001:10001 \
    --network "$network_name" \
    --mount "type=volume,src=$workspace_volume,dst=/workspace" \
    --workdir "/workspace/cases/$case_id" \
    --env HOME=/workspace/.reliability-home \
    --env NPM_CONFIG_CACHE=/workspace/.npm-cache \
    --env NPM_CONFIG_AUDIT=false \
    --env NPM_CONFIG_FUND=false \
    --entrypoint /bin/bash \
    "$image_id" \
    -lc 'npm ci'
}

inspect_stack() {
  require_cmd docker
  require_cmd curl
  require_cmd python3
  assert_safe_config

  local container_id uid
  container_id=$(daemon_container_id)
  uid=$(compose exec --no-TTY daemon id -u)
  [[ "$uid" =~ ^[0-9]+$ ]] || die "daemon uid is not numeric"
  [ "$uid" -ne 0 ] || die "daemon is running as root"
  printf 'daemon_uid=%s\n' "$uid"

  docker inspect "$container_id" | python3 -c '
import json
import sys

container = json.load(sys.stdin)[0]
mounts = container.get("Mounts") or []
if any(mount.get("Type") == "bind" for mount in mounts):
    raise SystemExit("daemon has a forbidden bind mount")
if len(mounts) != 1:
    raise SystemExit(f"daemon expected exactly one mount, found {len(mounts)}")
mount = mounts[0]
if mount.get("Type") != "volume" or mount.get("Destination") != "/workspace":
    raise SystemExit(f"unexpected daemon mount: {mount.get('Type')}:{mount.get('Destination')}")
if mount.get("RW") is not True:
    raise SystemExit("daemon workspace volume is not writable")
print("daemon_mount=volume:/workspace writable=true")
'

  compose exec --no-TTY daemon bash -c '
set -euo pipefail
for command in rg fd; do
  if command -v "$command" >/dev/null 2>&1; then
    echo "ERROR: forbidden command is present: $command" >&2
    exit 1
  fi
done
for command in java javap node npm git bash; do
  command -v "$command" >/dev/null 2>&1 \
    || { echo "ERROR: required command is missing: $command" >&2; exit 1; }
done
test -w /workspace
printf "rg=absent fd=absent workspace_writable=true\n"
java -version 2>&1 | head -n 1
javap -version
node --version
npm --version
git --version
bash --version | head -n 1
'
  environment_is_ready || die "public Environment API does not show READY"
  print_environment_status
  step "isolation inspection passed"
}

smoke_tools() {
  require_cmd docker
  local container_id
  container_id=$(daemon_container_id)
  step "running direct Java coding-tool smoke inside the Daemon image"
  docker exec --interactive --user 10001:10001 "$container_id" bash -c '
set -euo pipefail
tmp=$(mktemp -d)
trap '"'"'rm -rf "$tmp"'"'"' EXIT
source_file="$tmp/NativeToolSmoke.java"
cat >"$source_file"
javac -cp "/opt/kk-studio/daemon.jar:/opt/kk-studio/lib/*" -d "$tmp" "$source_file"
java -cp "$tmp:/opt/kk-studio/daemon.jar:/opt/kk-studio/lib/*" \
  fun.fengwk.kkstudio.harness.daemon.coding.NativeToolSmoke
' <"$REPO_ROOT/scripts/reliability/NativeToolSmoke.java"
}

safe_status() {
  compose ps --format json | python3 -c '
import json
import sys

raw = sys.stdin.read().strip()
if not raw:
    print("no reliability containers")
    raise SystemExit(0)
try:
    decoded = json.loads(raw)
    rows = decoded if isinstance(decoded, list) else [decoded]
except json.JSONDecodeError:
    rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
for row in sorted(rows, key=lambda item: item.get("Service", "")):
    print("service={} state={} health={}".format(
        row.get("Service"), row.get("State"), row.get("Health") or "-"))
'
}

status_stack() {
  require_cmd docker
  require_cmd python3
  safe_status
  if command -v curl >/dev/null 2>&1 \
    && curl -fsS "$APP_URL/actuator/health" >/dev/null 2>&1; then
    print_environment_status
  fi
}

logs_stack() {
  require_cmd docker
  local service
  for service in "$@"; do
    case "$service" in
      postgres | app | workspace-init | daemon) ;;
      *) die "invalid service '$service'" ;;
    esac
  done
  compose logs --no-color --tail "${RELIABILITY_LOG_TAIL:-200}" "$@"
}

down_stack() {
  require_cmd docker
  case "${1-}" in
    "")
      compose down --remove-orphans
      ;;
    --volumes)
      compose down --remove-orphans --volumes
      ;;
    *)
      die "down accepts only optional --volumes"
      ;;
  esac
}

command=${1:-help}
if [ "$#" -gt 0 ]; then
  shift
fi

case "$command" in
  up)
    [ "$#" -eq 0 ] || die "up accepts no arguments"
    up_stack
    ;;
  snapshot)
    [ "$#" -eq 0 ] || die "snapshot accepts no arguments"
    snapshot_anchors
    ;;
  case-reset)
    [ "$#" -eq 2 ] || die "case-reset requires <case-id> <pi|pi-base>"
    reset_case "$@"
    ;;
  case-deps)
    [ "$#" -eq 1 ] || die "case-deps requires <case-id>"
    install_case_dependencies "$@"
    ;;
  inspect)
    [ "$#" -eq 0 ] || die "inspect accepts no arguments"
    inspect_stack
    ;;
  tool-smoke)
    [ "$#" -eq 0 ] || die "tool-smoke accepts no arguments"
    smoke_tools
    ;;
  logs)
    logs_stack "$@"
    ;;
  status)
    [ "$#" -eq 0 ] || die "status accepts no arguments"
    status_stack
    ;;
  down)
    [ "$#" -le 1 ] || die "down accepts only optional --volumes"
    down_stack "$@"
    ;;
  help | --help | -h)
    [ "$#" -eq 0 ] || die "help accepts no arguments"
    usage
    ;;
  *)
    usage >&2
    die "unknown command: $command"
    ;;
esac
