/**
 * 受管更新脚本（随编译产物一起发行，由 update.ts 在准备阶段写入 operation 私有目录）。
 *
 * <p>脚本只承担两件事：把 operation 私有目录中的独立更新器当作 OS 作业启动，以及在更新器确认交接后停止/启动安装器
 * 所有的受管服务。handoff、许可、备份、替换、启动确认和 result 全部由 `update-entry.ts` 持有，脚本不解释这些状态，
 * 也不读取凭据、不 source 任意文件、不自动回滚、不提权。
 *
 * <p>三个动作共享同一套服务所有权校验：只有安装器写入的 unit/plist/计划任务才可停止或启动。失败时脚本以非零退出，
 * 更新器负责写出有界 FAILED 结果并保留 backup/staging 证据。
 */

/** Unix 更新脚本（Linux systemd --user / macOS GUI LaunchAgent）。 */
export const UNIX_UPDATE_SCRIPT = `#!/usr/bin/env bash
# Managed by the kk-studio managed Node Daemon update flow.
# Launches the private updater or stops/starts only the installer-owned user service.
# It never reads credentials, sources metadata or replaces the distribution itself.
#
# Usage: kk-studio-daemon-update.sh <run|stop|start> <operation-dir> <staged-dir> <installed-dir> [<updater-node> <updater-entry>]
set -euo pipefail
umask 077

UNIT_MARKER='# Managed by scripts/daemon/install.sh'
PLIST_MARKER='<!-- Managed by scripts/daemon/install.sh -->'
SERVICE_NAME=kk-studio-daemon.service
LAUNCHD_LABEL=fun.fengwk.kkstudio.environment-daemon
STOP_WAIT_SECONDS=30

if [ "$#" -lt 4 ]; then
  echo "usage: $0 <run|stop|start> <operation-dir> <staged-dir> <installed-dir> [<updater-node> <updater-entry>]" >&2
  exit 2
fi
action=$1
update_dir=$2
staged_dir=$3
installed_dir=$4

if [ "$action" = "run" ]; then
  [ "$#" -eq 6 ] || { echo "run requires updater node and entry" >&2; exit 2; }
  updater_node=$5
  updater_entry=$6
  exec "$updater_node" "$updater_entry" \\
    --operation-dir "$update_dir" \\
    --staged-dir "$staged_dir" \\
    --installed-dir "$installed_dir" \\
    --update-script "$0"
fi

[ "$action" = "stop" ] || [ "$action" = "start" ] || {
  echo "unknown update script action" >&2
  exit 2
}

os=$(uname -s)
if [ "$os" = "Darwin" ]; then
  plist="$HOME/Library/LaunchAgents/$LAUNCHD_LABEL.plist"
  domain="gui/$(id -u)"
  [ -f "$plist" ] || { echo "managed launch agent definition is missing" >&2; exit 1; }
  [ "$(sed -n '2p' "$plist")" = "$PLIST_MARKER" ] || { echo "launch agent is not owned by the installer" >&2; exit 1; }
  if [ "$action" = "stop" ]; then
    launchctl bootout "$domain/$LAUNCHD_LABEL" >/dev/null 2>&1 || true
    waited=0
    while launchctl print "$domain/$LAUNCHD_LABEL" >/dev/null 2>&1; do
      waited=$((waited + 1))
      if [ "$waited" -ge "$STOP_WAIT_SECONDS" ]; then
        echo "managed launch agent did not stop" >&2
        exit 1
      fi
      sleep 1
    done
  else
    launchctl bootstrap "$domain" "$plist" || { echo "could not restart the managed launch agent" >&2; exit 1; }
  fi
else
  unit="$HOME/.config/systemd/user/$SERVICE_NAME"
  [ -f "$unit" ] || { echo "managed systemd unit is missing" >&2; exit 1; }
  [ "$(head -n 1 "$unit")" = "$UNIT_MARKER" ] || { echo "systemd unit is not owned by the installer" >&2; exit 1; }
  if [ "$action" = "stop" ]; then
    systemctl --user stop "$SERVICE_NAME" || { echo "managed service did not stop" >&2; exit 1; }
    waited=0
    while systemctl --user is-active --quiet "$SERVICE_NAME"; do
      waited=$((waited + 1))
      if [ "$waited" -ge "$STOP_WAIT_SECONDS" ]; then
        echo "managed service did not stop" >&2
        exit 1
      fi
      sleep 1
    done
  else
    systemctl --user start "$SERVICE_NAME" || { echo "could not restart the managed service" >&2; exit 1; }
  fi
fi
`

/** Windows 更新脚本（一次性计划任务，运行于受管 AtLogOn 任务之外）。 */
export const WINDOWS_UPDATE_SCRIPT = `# Managed by the kk-studio managed Node Daemon update flow.
# Launches the private updater or stops/starts only the installer-owned scheduled task.
# It never reads credentials or replaces the distribution itself.
#
# Usage: kk-studio-daemon-update.ps1 -Action <run|stop|start> -OperationDir <path> -StagedDir <path>
#        -InstalledDir <path> [-UpdaterNode <path> -UpdaterEntry <path>]
param(
    [Parameter(Mandatory = $true)][ValidateSet('run', 'stop', 'start')][string]$Action,
    [Parameter(Mandatory = $true)][string]$OperationDir,
    [Parameter(Mandatory = $true)][string]$StagedDir,
    [Parameter(Mandatory = $true)][string]$InstalledDir,
    [string]$UpdaterNode,
    [string]$UpdaterEntry
)
$ErrorActionPreference = 'Stop'
$OperationId = Split-Path -Leaf $OperationDir
$Sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$TaskName = "kk-studio-environment-daemon-$Sid"
$TaskDescription = "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$Sid"
$TaskPath = '\\'
$StopWaitSeconds = 30

function Write-Failure([string]$Message) {
    [Console]::Error.WriteLine("update \${OperationId} failed: $Message")
    exit 1
}

# 比较两个绝对路径（规范化后），容忍 Windows 大小写不敏感。
function Test-SamePath([string]$Left, [string]$Right) {
    if ([string]::IsNullOrEmpty($Left) -or [string]::IsNullOrEmpty($Right)) { return $false }
    return [IO.Path]::GetFullPath($Left) -eq [IO.Path]::GetFullPath($Right)
}
# 遍历计划任务的根目录而不是按名字过滤：按名字过滤时「任务不存在」会报错，与真实的计划任务/CIM
# 故障无法区分。遍历让缺失保持为 $null，而真实故障仍然终止（-ErrorAction Stop）。
function Find-ManagedTask([string]$Name) {
    return Get-ScheduledTask -ErrorAction Stop |
        Where-Object { $_.TaskPath -eq $TaskPath -and $_.TaskName -eq $Name } |
        Select-Object -First 1
}

if ($Action -eq 'run') {
    if ([string]::IsNullOrEmpty($UpdaterNode) -or [string]::IsNullOrEmpty($UpdaterEntry)) {
        Write-Failure 'run requires updater node and entry'
    }
    if (-not (Test-Path -LiteralPath $UpdaterNode -PathType Leaf)) { Write-Failure 'updater runtime is missing' }
    if (-not (Test-Path -LiteralPath $UpdaterEntry -PathType Leaf)) { Write-Failure 'updater entry is missing' }
    & $UpdaterNode $UpdaterEntry '--operation-dir' $OperationDir '--staged-dir' $StagedDir '--installed-dir' $InstalledDir '--update-script' $PSCommandPath
    exit $LASTEXITCODE
}

# 任务所有权：Description + 唯一 Action 的 Execute（冻结的 node.exe）+ Arguments + WorkingDirectory
# 全部匹配才可停止或启动。
$expectedNode = Join-Path $InstalledDir 'runtime\\code-server\\lib\\node.exe'
$configPath = Join-Path (Split-Path -Parent $InstalledDir) 'daemon.json'
# 与安装器同一条 Windows 命令行规则：每个参数都加双引号。配置路径是真实 Windows 路径且以 daemon.json 结尾，
# 不含双引号或尾随反斜杠，因此转义后仍是两侧各一个引号。
$expectedArguments = '"main.js" "--config" "' + $configPath + '"'

$task = Find-ManagedTask $TaskName
if ($null -eq $task) { Write-Failure 'managed scheduled task is missing' }
if ([string]$task.Description -ne $TaskDescription) { Write-Failure 'managed scheduled task is not owned by the installer' }
$actions = @($task.Actions)
if ($actions.Count -ne 1) { Write-Failure 'managed scheduled task does not have exactly one action' }
$action = $actions[0]
if (-not (Test-SamePath -Left ([string]$action.Execute) -Right $expectedNode)) {
    Write-Failure 'managed scheduled task does not run the managed runtime'
}
if ([string]$action.Arguments -ne $expectedArguments) {
    Write-Failure 'managed scheduled task arguments do not match the managed configuration'
}
if (-not (Test-SamePath -Left ([string]$action.WorkingDirectory) -Right $InstalledDir)) {
    Write-Failure 'managed scheduled task working directory does not match the managed installation'
}

if ($Action -eq 'stop') {
    Stop-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath
    # Running 与 Queued 都还持有任务，排队中的实例可能马上启动，因此两者都必须等待。
    $deadline = (Get-Date).AddSeconds($StopWaitSeconds)
    while ($true) {
        $current = Find-ManagedTask $TaskName
        if ($null -eq $current -or [string]$current.State -notin @('Running', 'Queued')) { break }
        if ((Get-Date) -gt $deadline) { Write-Failure 'managed scheduled task did not stop' }
        Start-Sleep -Seconds 1
    }
    exit 0
}

Start-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath
exit 0
`
