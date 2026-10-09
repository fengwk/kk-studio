#!/usr/bin/env bash
# Managed by the kk-studio managed Daemon update flow.
#
# Applies one already verified binary update handoff and restarts the managed service.
# It is launched as an independent short-lived OS job, so it outlives the old daemon.
# It replaces only the binary; configuration, token and data are untouched and it never
# rolls back automatically. It performs no elevation and does not become a resident engine.
#
# It fails closed: if the managed service definition is missing, not owned by the
# installer, or cannot be stopped, it reports failure and leaves the old binary untouched.
#
# Usage: kk-studio-daemon-update.sh <staged-jar> <installed-jar>
set -euo pipefail
umask 077

UNIT_MARKER='# Managed by scripts/daemon/install.sh'
PLIST_MARKER='<!-- Managed by scripts/daemon/install.sh -->'
SERVICE_NAME=kk-studio-daemon.service
LAUNCHD_LABEL=fun.fengwk.kkstudio.environment-daemon
STOP_WAIT_SECONDS=30

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <staged-jar> <installed-jar>" >&2
  exit 2
fi
staged_jar=$1
installed_jar=$2
update_dir=$(dirname "$staged_jar")
operation_id=$(basename "$update_dir")
backup_dir="$update_dir/backup"

# Durable, minimal evidence: written before any destructive action and preserved on failure.
write_result() {
  printf '{"operationId":"%s","phase":"%s","message":"%s"}\n' "$operation_id" "$1" "$2" \
    >"$update_dir/update-result.json"
}

fail_closed() {
  write_result FAILED "$1"
  echo "update $operation_id failed: $1" >&2
  exit 1
}

[ -f "$staged_jar" ] || fail_closed "staged artifact is missing"
[ -f "$installed_jar" ] || fail_closed "installed artifact is missing"

# Give the old daemon a moment to flush its PREPARED receipt before the service is stopped.
sleep "${KK_STUDIO_UPDATE_GRACE_SECONDS:-3}"

os=$(uname -s)
if [ "$os" = "Darwin" ]; then
  plist="$HOME/Library/LaunchAgents/$LAUNCHD_LABEL.plist"
  domain="gui/$(id -u)"
  [ -f "$plist" ] || fail_closed "managed launch agent definition is missing"
  [ "$(sed -n '2p' "$plist")" = "$PLIST_MARKER" ] ||
    fail_closed "launch agent is not owned by the installer"
  launchctl bootout "$domain/$LAUNCHD_LABEL" >/dev/null 2>&1 || true
  waited=0
  while launchctl print "$domain/$LAUNCHD_LABEL" >/dev/null 2>&1; do
    waited=$((waited + 1))
    [ "$waited" -lt "$STOP_WAIT_SECONDS" ] || fail_closed "managed launch agent did not stop"
    sleep 1
  done
else
  unit="$HOME/.config/systemd/user/$SERVICE_NAME"
  [ -f "$unit" ] || fail_closed "managed systemd unit is missing"
  [ "$(head -n 1 "$unit")" = "$UNIT_MARKER" ] ||
    fail_closed "systemd unit is not owned by the installer"
  systemctl --user stop "$SERVICE_NAME" || fail_closed "managed service did not stop"
  waited=0
  while systemctl --user is-active --quiet "$SERVICE_NAME"; do
    waited=$((waited + 1))
    [ "$waited" -lt "$STOP_WAIT_SECONDS" ] || fail_closed "managed service did not stop"
    sleep 1
  done
fi

mkdir -p "$backup_dir"
cp -p "$installed_jar" "$backup_dir/$(basename "$installed_jar")" ||
  fail_closed "could not back up the installed artifact"

if ! mv -f "$staged_jar" "$installed_jar"; then
  fail_closed "could not replace the installed artifact"
fi

if [ "$os" = "Darwin" ]; then
  launchctl bootstrap "$domain" "$plist" || fail_closed "could not restart the managed launch agent"
else
  systemctl --user start "$SERVICE_NAME" || fail_closed "could not restart the managed service"
fi

write_result PREPARED "binary replaced and service restarted"
echo "update $operation_id applied"
