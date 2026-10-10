/**
 * 受管更新脚本（随编译产物一起发行，由 update.ts 在准备阶段写入 operation 私有目录）。
 *
 * 它们只负责在旧 Daemon 停止后把已验证好的暂存发行目录原子替换为受管目录并重启服务；
 * 不读取凭据、不接受任意路径、不自动回滚，也不提权。固定使用绝对运行时路径，凭据不进入 argv 或日志。
 */

/** Unix 更新脚本（Linux systemd --user / macOS GUI LaunchAgent）。 */
export const UNIX_UPDATE_SCRIPT = `#!/usr/bin/env bash
# Managed by the kk-studio managed Node Daemon update flow.
# Applies one verified distribution handoff and restarts the managed user service.
# Launched as an independent short-lived OS job so it outlives the old daemon.
# It replaces only the program distribution; config, token and data are untouched.
# Fails closed: an unknown or unowned service definition leaves the old distribution untouched.
# Usage: kk-studio-daemon-update.sh <operation-dir> <staged-dir> <installed-dir>
set -euo pipefail
umask 077

UNIT_MARKER='# Managed by scripts/daemon/install.sh'
PLIST_MARKER='<!-- Managed by scripts/daemon/install.sh -->'
SERVICE_NAME=kk-studio-daemon.service
LAUNCHD_LABEL=fun.fengwk.kkstudio.environment-daemon
STOP_WAIT_SECONDS=30

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <operation-dir> <staged-dir> <installed-dir>" >&2
  exit 2
fi
update_dir=$1
staged_dir=$2
installed_dir=$3
operation_id=$(basename "$update_dir")
backup_dir="$update_dir/backup"

write_result() {
  printf '{"operationId":"%s","phase":"%s","message":"%s"}\\n' "$operation_id" "$1" "$2" >"$update_dir/update-result.json"
}
fail_closed() {
  write_result FAILED "$1"
  echo "update $operation_id failed: $1" >&2
  exit 1
}

[ -f "$staged_dir/main.js" ] || fail_closed "staged distribution is missing"
[ -d "$installed_dir" ] || fail_closed "installed distribution is missing"

# Give the old daemon a moment to flush its PREPARED receipt before the service is stopped.
sleep 3

os=$(uname -s)
if [ "$os" = "Darwin" ]; then
  plist="$HOME/Library/LaunchAgents/$LAUNCHD_LABEL.plist"
  domain="gui/$(id -u)"
  [ -f "$plist" ] || fail_closed "managed launch agent definition is missing"
  [ "$(sed -n '2p' "$plist")" = "$PLIST_MARKER" ] || fail_closed "launch agent is not owned by the installer"
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
  [ "$(head -n 1 "$unit")" = "$UNIT_MARKER" ] || fail_closed "systemd unit is not owned by the installer"
  systemctl --user stop "$SERVICE_NAME" || fail_closed "managed service did not stop"
  waited=0
  while systemctl --user is-active --quiet "$SERVICE_NAME"; do
    waited=$((waited + 1))
    [ "$waited" -lt "$STOP_WAIT_SECONDS" ] || fail_closed "managed service did not stop"
    sleep 1
  done
fi

mkdir -p "$backup_dir"
if [ -e "$backup_dir/previous" ]; then rm -rf "$backup_dir/previous"; fi
mv "$installed_dir" "$backup_dir/previous" || fail_closed "could not back up the installed distribution"
if ! mv "$staged_dir" "$installed_dir"; then
  mv "$backup_dir/previous" "$installed_dir" 2>/dev/null || true
  fail_closed "could not replace the installed distribution"
fi

if [ "$os" = "Darwin" ]; then
  launchctl bootstrap "$domain" "$plist" || fail_closed "could not restart the managed launch agent"
else
  systemctl --user start "$SERVICE_NAME" || fail_closed "could not restart the managed service"
fi

write_result PREPARED "distribution replaced and service restarted"
echo "update $operation_id applied"
`

/** Windows 更新脚本（一次性计划任务，运行于受管 AtLogOn 任务之外）。 */
export const WINDOWS_UPDATE_SCRIPT = `# Managed by the kk-studio managed Node Daemon update flow.
# Applies one verified distribution handoff and restarts the managed AtLogOn task.
param(
    [Parameter(Mandatory = $true)][string]$OperationDir,
    [Parameter(Mandatory = $true)][string]$StagedDir,
    [Parameter(Mandatory = $true)][string]$InstalledDir
)
$ErrorActionPreference = 'Stop'
$TaskName = 'kk-studio-environment-daemon'
$OperationId = Split-Path -Leaf $OperationDir
$ResultPath = Join-Path $OperationDir 'update-result.json'

function Write-Result([string]$Phase, [string]$Message) {
    $payload = [ordered]@{ operationId = $OperationId; phase = $Phase; message = $Message }
    $payload | ConvertTo-Json -Compress | Set-Content -NoNewline -Encoding utf8 -LiteralPath $ResultPath
}
function Fail-Closed([string]$Message) {
    Write-Result -Phase 'FAILED' -Message $Message
    Write-Error ("update " + $OperationId + " failed: " + $Message)
    exit 1
}

if (-not (Test-Path -LiteralPath (Join-Path $StagedDir 'main.js'))) { Fail-Closed 'staged distribution is missing' }
if (-not (Test-Path -LiteralPath $InstalledDir)) { Fail-Closed 'installed distribution is missing' }
Start-Sleep -Seconds 3

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }
Stop-ScheduledTask -TaskName $TaskName

$backup = Join-Path $OperationDir 'backup'
New-Item -ItemType Directory -Force -Path $backup | Out-Null
$previous = Join-Path $backup 'previous'
if (Test-Path -LiteralPath $previous) { Remove-Item -LiteralPath $previous -Recurse -Force }
Move-Item -LiteralPath $InstalledDir -Destination $previous
try {
    Move-Item -LiteralPath $StagedDir -Destination $InstalledDir
} catch {
    Move-Item -LiteralPath $previous -Destination $InstalledDir
    Fail-Closed 'could not replace the installed distribution'
}
Start-ScheduledTask -TaskName $TaskName
Write-Result -Phase 'PREPARED' -Message 'distribution replaced and service restarted'
`
