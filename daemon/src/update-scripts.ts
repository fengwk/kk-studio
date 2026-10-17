/**
 * 受管更新脚本（随编译产物一起发行，由 update.ts 在准备阶段写入 operation 私有目录）。
 *
 * 它们只负责在旧 Daemon 释放许可后把已验证好的暂存发行目录原子替换为受管目录并重启服务；
 * 不读取凭据、不接受任意路径、不 source 任意文件、不自动回滚，也不提权。凭据不进入 argv 或日志。
 *
 * 契约：**失败保留旧程序与 backup 目录，不自动回滚运行中的服务**；重复运行同一 operation 由 handoff
 * 状态（仅 ACCEPTED 可复用）与唯一 backup 目录共同防止覆盖唯一的旧备份。
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
PERMIT_WAIT_SECONDS=120

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <operation-dir> <staged-dir> <installed-dir>" >&2
  exit 2
fi
update_dir=$1
staged_dir=$2
installed_dir=$3
operation_id=$(basename "$update_dir")
handoff_file="$update_dir/handoff.json"
permit_file="$update_dir/permit"

write_result() {
  printf '{"operationId":"%s","phase":"%s","message":"%s"}\\n' "$operation_id" "$1" "$2" >"$update_dir/update-result.json"
}
fail_closed() {
  write_result FAILED "$1"
  echo "update $operation_id failed: $1" >&2
  exit 1
}

# Handoff 元数据必须存在、是普通文件（非符号链接）且与本 operation 严格一致；绝不 source 或 eval 任意文件。
[ -f "$handoff_file" ] && [ ! -L "$handoff_file" ] || fail_closed "handoff metadata is missing"
grep -qF "\\"operationId\\": \\"$operation_id\\"" "$handoff_file" || fail_closed "handoff metadata does not match the operation"
grep -qF '"state": "ACCEPTED"' "$handoff_file" || fail_closed "handoff metadata is not accepted"

[ -f "$staged_dir/main.js" ] || fail_closed "staged distribution is missing"
[ -d "$installed_dir" ] || fail_closed "installed distribution is missing"

# 只有旧 Daemon 释放许可后才可替换发行目录：许可缺失即 fail closed，绝不猜测时序。
waited=0
while [ ! -f "$permit_file" ]; do
  waited=$((waited + 1))
  [ "$waited" -lt "$PERMIT_WAIT_SECONDS" ] || fail_closed "the daemon did not release the update permit in time"
  sleep 1
done
[ ! -L "$permit_file" ] || fail_closed "the update permit is not a regular file"

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

# 唯一 backup 目录：绝不覆盖上一次留下的唯一旧备份。
backup_dir=$(mktemp -d "$update_dir/backup.XXXXXXXX") || fail_closed "could not create the backup directory"
mv "$installed_dir" "$backup_dir/previous" || fail_closed "could not back up the installed distribution"
# 替换失败不自动回滚：旧发行保留在 backup 目录，失败证据保留，服务保持停止。
mv "$staged_dir" "$installed_dir" || fail_closed "could not replace the installed distribution"

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
$OperationId = Split-Path -Leaf $OperationDir
$ResultPath = Join-Path $OperationDir 'update-result.json'
$HandoffPath = Join-Path $OperationDir 'handoff.json'
$PermitPath = Join-Path $OperationDir 'permit'
$PermitWaitSeconds = 120
$StopWaitSeconds = 30
$Sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$TaskName = "kk-studio-environment-daemon-$Sid"
$TaskDescription = "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$Sid"
$UpdateTaskName = "kk-studio-daemon-update-$OperationId"

function Write-Result([string]$Phase, [string]$Message) {
    $payload = [ordered]@{ operationId = $OperationId; phase = $Phase; message = $Message }
    $payload | ConvertTo-Json -Compress | Set-Content -NoNewline -Encoding utf8 -LiteralPath $ResultPath
}
function Unregister-UpdateTask {
    # 一次性更新任务自清理，避免同名任务累积。
    Unregister-ScheduledTask -TaskName $UpdateTaskName -Confirm:$false -ErrorAction SilentlyContinue
}
function Complete-Update([string]$Phase, [string]$Message) {
    Write-Result -Phase $Phase -Message $Message
    Unregister-UpdateTask
}
function Fail-Closed([string]$Message) {
    Complete-Update -Phase 'FAILED' -Message $Message
    Write-Error ("update " + $OperationId + " failed: " + $Message)
    exit 1
}

# Handoff 元数据必须存在、是普通文件且与本 operation 严格一致；绝不 source 或执行任意路径。
if (-not (Test-Path -LiteralPath $HandoffPath -PathType Leaf)) { Fail-Closed 'handoff metadata is missing' }
try {
    $handoff = Get-Content -LiteralPath $HandoffPath -Raw -Encoding utf8 | ConvertFrom-Json -ErrorAction Stop
} catch {
    Fail-Closed 'handoff metadata is malformed'
}
if ($handoff.operationId -ne $OperationId) { Fail-Closed 'handoff metadata does not match the operation' }
if ($handoff.state -ne 'ACCEPTED') { Fail-Closed 'handoff metadata is not accepted' }

if (-not (Test-Path -LiteralPath (Join-Path $StagedDir 'main.js') -PathType Leaf)) { Fail-Closed 'staged distribution is missing' }
if (-not (Test-Path -LiteralPath $InstalledDir)) { Fail-Closed 'installed distribution is missing' }

# 只有旧 Daemon 释放许可后才可替换发行目录：许可缺失即 fail closed，绝不猜测时序。
$deadline = (Get-Date).AddSeconds($PermitWaitSeconds)
while (-not (Test-Path -LiteralPath $PermitPath -PathType Leaf)) {
    if ((Get-Date) -gt $deadline) { Fail-Closed 'the daemon did not release the update permit in time' }
    Start-Sleep -Seconds 1
}

# 受托 owner identity + Description + Action(Exec 与直接 argv 配置路径的 Arguments) 全部匹配才可停止任务。
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }
if ($task.Description -ne $TaskDescription) { Fail-Closed 'managed scheduled task is not owned by the installer' }
$action = $task.Actions | Select-Object -First 1
if ($null -eq $action) { Fail-Closed 'managed scheduled task has no action' }
$expectedNode = Join-Path $InstalledDir 'runtime\\code-server\\lib\\node.exe'
$configPath = Join-Path (Split-Path -Parent $InstalledDir) 'daemon.json'
# 与安装器同一条 Windows 命令行规则：每个参数都加双引号。配置路径是真实 Windows 路径且以 daemon.json 结尾，
# 不含双引号或尾随反斜杠，因此转义后仍是两侧各一个引号。
$expectedArguments = '"main.js" "--config" "' + $configPath + '"'
if ($action.Execute -ne $expectedNode) { Fail-Closed 'managed scheduled task does not run the managed runtime' }
if ($action.Arguments -ne $expectedArguments) { Fail-Closed 'managed scheduled task arguments do not match the managed configuration' }
Stop-ScheduledTask -TaskName $TaskName

# 等到受管任务真正停下来再替换发行目录；旧进程仍持有受管文件时替换即失败，绝不猜测时序。
$stopDeadline = (Get-Date).AddSeconds($StopWaitSeconds)
while ($true) {
    $current = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($null -eq $current -or $current.State -ne 'Running') { break }
    if ((Get-Date) -gt $stopDeadline) { Fail-Closed 'managed scheduled task did not stop' }
    Start-Sleep -Seconds 1
}

# 唯一 backup 目录：绝不覆盖上一次留下的唯一旧备份。
$backupDir = Join-Path $OperationDir ('backup-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$previous = Join-Path $backupDir 'previous'
Move-Item -LiteralPath $InstalledDir -Destination $previous
# 替换失败不自动回滚：旧发行保留在 $previous，失败证据保留，服务保持停止。
Move-Item -LiteralPath $StagedDir -Destination $InstalledDir

Start-ScheduledTask -TaskName $TaskName
Complete-Update -Phase 'PREPARED' -Message 'distribution replaced and service restarted'
`
