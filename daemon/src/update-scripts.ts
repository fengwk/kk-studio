/**
 * 受管更新脚本（随编译产物一起发行，由 update.ts 在准备阶段写入 operation 私有目录）。
 *
 * 它们只负责在旧 Daemon 释放许可后把已验证好的暂存发行目录替换为受管目录并重启服务；
 * 不读取凭据、不接受任意路径、不 source 或执行任意元数据、不自动回滚，也不提权。凭据不进入 argv 或日志。
 *
 * 契约：**失败保留旧程序与 backup 目录，不自动回滚运行中的服务**；重复运行同一 operation 由 handoff
 * 状态（许可到达后仍须 ACCEPTED）与唯一 backup 目录共同防止覆盖唯一的旧备份。
 *
 * 时序契约（两平台一致，与真实 daemon 的写入顺序对应）：update.ts 先冻结 handoff= PREPARING，然后启动
 * 这个独立 job，最后才冻结 ACCEPTED 并要求 Runtime 释放许可。因此 job 启动时看到 PREPARING 是**正常**
 * 状态，绝不是身份错配：开场只校验 operation 身份，等到私有许可出现（普通非链接文件且内容严格等于
 * operationId）之后，才重新读取同一份 handoff 快照并要求身份仍匹配且 state=ACCEPTED，在此之前零停止、
 * 零备份、零替换。
 *
 * 失败处理：单一 fail_closed 入口 + 意外退出兜底（Unix 用 EXIT trap，Windows 用顶层 try/catch/finally），
 * 任何路径都留下安全的 FAILED 回执；回执写入失败必须可见，但绝不因此把已经完成的替换+重启改判为失败，
 * 也绝不跳过其余清理。
 */

/** Unix 更新脚本（Linux systemd --user / macOS GUI LaunchAgent）。 */
export const UNIX_UPDATE_SCRIPT = `#!/usr/bin/env bash
# Managed by the kk-studio managed Node Daemon update flow.
# Applies one verified distribution handoff and restarts the managed user service.
# Launched as an independent short-lived OS job so it outlives the old daemon.
# It replaces only the program distribution; config, token and data are untouched.
# Fails closed: an unknown or unowned service definition leaves the old distribution untouched.
#
# The job necessarily starts while the daemon still persists PREPARING, so an early PREPARING is
# never rejected as a mismatch: the operation identity alone is checked up front, and only after
# the private permit appears (a regular non-symlink file whose content is this operation) is the
# same handoff snapshot re-read and required to still match and be ACCEPTED. A missing or
# mismatched handoff/permit leaves the old distribution untouched with zero stop, backup or
# replace.
#
# Failure handling is uniform: fail_closed is the single failure entry point, every step that
# touches the filesystem or the service goes through it, and an EXIT trap guarantees that any
# still-unreported non-zero exit also leaves a durable FAILED receipt. Receipt writing is best
# effort so a broken disk can never hide the failure, skip the cleanup, or turn an already
# completed replacement and restart into a false failure.
#
# Usage: kk-studio-daemon-update.sh <operation-dir> <staged-dir> <installed-dir>
set -euo pipefail
umask 077

UNIT_MARKER='# Managed by scripts/daemon/install.sh'
PLIST_MARKER='<!-- Managed by scripts/daemon/install.sh -->'
SERVICE_NAME=kk-studio-daemon.service
LAUNCHD_LABEL=fun.fengwk.kkstudio.environment-daemon
STOP_WAIT_SECONDS=30
# 许可预算只约束「已经接受的更新能等多久」，不是时序猜测：Daemon 在冻结 PREPARED 回执后才释放许可。
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
result_path="$update_dir/update-result.json"
result_written=0

# 耐久且最小的证据：在任何破坏性动作之前写入，失败时保留；回执写失败只报告，绝不中断调用方的清理。
write_result() {
  if printf '{"operationId":"%s","phase":"%s","message":"%s"}\\n' "$operation_id" "$1" "$2" \\
    >"$result_path" 2>/dev/null; then
    result_written=1
  else
    echo "update $operation_id could not write the $1 receipt" >&2
  fi
}

# 单一失败入口：报告安全原因后中止；EXIT trap 负责与结果无关的通用清理。
fail_closed() {
  write_result FAILED "$1"
  echo "update $operation_id failed: $1" >&2
  exit 1
}

# 与结果无关的兜底：正常退出保留成功回执，任何「没有回执的非零退出」（例如 set -e 下的意外 I/O 中止）
# 也留下可耐久读取、已去敏的 FAILED 回执；回执已经写过就不再覆盖。
cleanup() {
  status=$?
  if [ "$status" -ne 0 ] && [ "$result_written" -eq 0 ]; then
    write_result FAILED "update aborted unexpectedly (exit $status)"
  fi
}
trap cleanup EXIT

[ -f "$staged_dir/main.js" ] || fail_closed "staged distribution is missing"
[ -d "$installed_dir" ] || fail_closed "installed distribution is missing"

# Handoff 元数据必须存在、是普通文件（非符号链接）且与本 operation 严格一致；绝不 source 或 eval 任意文件。
# 这里刻意**不**要求 state：独立 job 正是在 Daemon 仍持久化 PREPARING 时被启动的，过早的 PREPARING 是正常
# 状态，绝不能当作错配拒绝。state 的校验推迟到许可到达之后。
[ -f "$handoff_file" ] && [ ! -L "$handoff_file" ] || fail_closed "handoff metadata is missing"
grep -qE "\\"operationId\\"[[:space:]]*:[[:space:]]*\\"$operation_id\\"" "$handoff_file" ||
  fail_closed "handoff metadata does not match the operation"

# 只有旧 Daemon 为本 operation 释放许可后才可替换发行目录：许可缺失即 fail closed，绝不猜测时序。
waited=0
while [ ! -f "$permit_file" ]; do
  waited=$((waited + 1))
  [ "$waited" -lt "$PERMIT_WAIT_SECONDS" ] ||
    fail_closed "the daemon did not release the update permit in time"
  sleep 1
done
[ ! -L "$permit_file" ] || fail_closed "the update permit is not a regular file"
[ "$(cat "$permit_file")" = "$operation_id" ] ||
  fail_closed "the update permit does not match the operation"

# 许可之后重新读取同一份 handoff 快照：身份必须仍然匹配，且此时必须已被接受，然后才可停止服务。
[ -f "$handoff_file" ] && [ ! -L "$handoff_file" ] || fail_closed "handoff metadata is missing"
handoff_metadata=$(cat "$handoff_file") || fail_closed "could not read handoff metadata"
printf '%s\\n' "$handoff_metadata" |
  grep -qE "\\"operationId\\"[[:space:]]*:[[:space:]]*\\"$operation_id\\"" ||
  fail_closed "handoff metadata does not match the operation"
printf '%s\\n' "$handoff_metadata" |
  grep -qE "\\"state\\"[[:space:]]*:[[:space:]]*\\"ACCEPTED\\"" ||
  fail_closed "handoff metadata is not accepted"

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

# 成功回执是尽力而为：发行目录已经替换、服务已经重启，写不出回执只报告，绝不改判为失败。
write_result PREPARED "distribution replaced and service restarted"
echo "update $operation_id applied"
`

/** Windows 更新脚本（一次性计划任务，运行于受管 AtLogOn 任务之外）。 */
export const WINDOWS_UPDATE_SCRIPT = `# Managed by the kk-studio managed Node Daemon update flow.
# Applies one verified distribution handoff and restarts the managed AtLogOn task.
# Runs as an independent one-shot scheduled task, so it outlives the old daemon.
# It replaces only the program distribution; config, token and data are untouched.
#
# The one-shot task necessarily starts while the daemon still persists PREPARING, so an early
# PREPARING is never rejected as a mismatch: the operation identity alone is checked up front,
# and only after the private permit appears (a regular non-reparse file whose content is this
# operation) is the same handoff snapshot re-read and required to still match and be ACCEPTED.
# The managed task must be owned by the installer (description plus the single action's
# executable, arguments and working directory) before anything is stopped.
#
# Failure handling is uniform: every step runs under one top-level try/catch/finally with a
# single fail-closed entry point. Reporting and cleanup are independent (cleanup sits in its own
# finally), so a failing receipt or diagnostic can never skip the cleanup that removes this
# operation's one-shot task, and Write-Error (script-terminating under
# $ErrorActionPreference = 'Stop') is avoided in favour of stderr. The success receipt is best
# effort so an already completed replacement and restart is never turned into a false failure.
# Tasks are only ever looked up at the scheduler root and by exact identity, so an absent task
# stays distinguishable from a genuine Task Scheduler/CIM failure.
#
# Usage: kk-studio-daemon-update.ps1 -OperationDir <path> -StagedDir <path> -InstalledDir <path>
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
# 许可预算只约束「已经接受的更新能等多久」，不是时序猜测：Daemon 在冻结 PREPARED 回执后才释放许可。
$PermitWaitSeconds = 120
$StopWaitSeconds = 30
$Sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$TaskName = "kk-studio-environment-daemon-$Sid"
$TaskDescription = "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$Sid"
$UpdateTaskName = "kk-studio-daemon-update-$OperationId"
# 受管 daemon 任务位于计划任务根目录；用精确 TaskPath 匹配，避免把别处同名任务当成它。
$TaskPath = '\\'

# 当前阶段只用来把意外异常变成安全的阶段+异常类型诊断，绝不带命令输出或路径。
$Step = 'preflight'
# 显式 fail-closed 决定的（已去敏）消息；为空表示失败来自意外异常。
$FailClosedMessage = ''

function Write-Result([string]$Phase, [string]$Message) {
    $payload = [ordered]@{ operationId = $OperationId; phase = $Phase; message = $Message }
    # WriteAllText 写不带 BOM 的 UTF-8：Daemon 用 JSON.parse 读取回执，BOM 会让它无法解析。
    [IO.File]::WriteAllText($ResultPath, ($payload | ConvertTo-Json -Compress))
}
# 诊断直接写 stderr：在 $ErrorActionPreference = 'Stop' 下 Write-Error 是脚本终止错误，会跳过失败回执与清理。
function Write-FailureDiagnostic([string]$Message) {
    [Console]::Error.WriteLine("update \${OperationId} failed: $Message")
}
# 尽力而为的报告与清理保持可见，但不改变更新结果。
function Write-UpdateWarning([string]$Message) {
    [Console]::Error.WriteLine("update \${OperationId} warning: $Message")
}
# 记录显式、已去敏的失败并中止后续步骤。抛异常（而不是 Write-Error/exit）保持单一失败入口，
# 并保证 finally 一定执行报告与清理。
function Fail-Closed([string]$Message) {
    $script:FailClosedMessage = $Message
    throw [System.InvalidOperationException]::new($Message)
}
# handoff/permit 必须是已存在的普通文件，且不是重解析点（符号链接）。
function Test-ManagedFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($null -eq $item) { return $false }
    return ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0
}
# 比较两个绝对路径（规范化后），容忍 Windows 大小写不敏感。
function Test-SamePath([string]$Left, [string]$Right) {
    if ([string]::IsNullOrEmpty($Left) -or [string]::IsNullOrEmpty($Right)) { return $false }
    return [IO.Path]::GetFullPath($Left) -eq [IO.Path]::GetFullPath($Right)
}
# 遍历计划任务的根目录而不是按名字过滤：按名字过滤时「任务不存在」会报错，与真实的计划任务/CIM
# 故障无法区分。遍历让缺失保持为 $null，而真实故障仍然终止（-ErrorAction Stop）。
function Find-ManagedTask([string]$TaskName) {
    return Get-ScheduledTask -ErrorAction Stop |
        Where-Object { $_.TaskPath -eq $TaskPath -and $_.TaskName -eq $TaskName } |
        Select-Object -First 1
}
function Read-Handoff {
    return Get-Content -LiteralPath $HandoffPath -Raw -Encoding utf8 | ConvertFrom-Json -ErrorAction Stop
}

$failure = $null
try {
    if (-not (Test-Path -LiteralPath (Join-Path $StagedDir 'main.js') -PathType Leaf)) { Fail-Closed 'staged distribution is missing' }
    if (-not (Test-Path -LiteralPath $InstalledDir)) { Fail-Closed 'installed distribution is missing' }

    # Handoff 元数据必须存在、是普通文件且与本 operation 严格一致。这里刻意**不**要求 state：一次性 job
    # 正是在 Daemon 仍持久化 PREPARING 时被启动的，过早的 PREPARING 是正常状态，绝不能当作错配拒绝；
    # state 的校验推迟到许可到达之后。绝不 source 或执行任意路径。
    $Step = 'handoff'
    if (-not (Test-ManagedFile -Path $HandoffPath)) { Fail-Closed 'handoff metadata is missing' }
    try {
        $handoff = Read-Handoff
    } catch {
        Fail-Closed 'handoff metadata is malformed'
    }
    if ([string]$handoff.operationId -ne $OperationId) { Fail-Closed 'handoff metadata does not match the operation' }

    # 私有许可把守替换：它只有在 Daemon 冻结 PREPARED 回执之后才被释放，且内容必须是本 operation；
    # 缺失或错配即零替换。
    $Step = 'permit'
    $deadline = (Get-Date).AddSeconds($PermitWaitSeconds)
    while (-not (Test-Path -LiteralPath $PermitPath -PathType Leaf)) {
        if ((Get-Date) -gt $deadline) { Fail-Closed 'the daemon did not release the update permit in time' }
        Start-Sleep -Seconds 1
    }
    if (-not (Test-ManagedFile -Path $PermitPath)) { Fail-Closed 'the update permit is not a regular file' }
    if ((Get-Content -LiteralPath $PermitPath -Raw).Trim() -ne $OperationId) { Fail-Closed 'the update permit does not match the operation' }

    # 许可之后重新读取同一份 handoff 快照：身份必须仍然匹配，且此时必须已被接受，然后才可停止任务。
    try {
        $handoff = Read-Handoff
    } catch {
        Fail-Closed 'handoff metadata is malformed'
    }
    if ([string]$handoff.operationId -ne $OperationId) { Fail-Closed 'handoff metadata does not match the operation' }
    if ([string]$handoff.state -ne 'ACCEPTED') { Fail-Closed 'handoff metadata is not accepted' }

    # 任务所有权：Description + 唯一 Action 的 Execute（冻结的 node.exe）+ Arguments + WorkingDirectory
    # 全部匹配才可停止。
    $Step = 'stop'
    $expectedNode = Join-Path $InstalledDir 'runtime\\code-server\\lib\\node.exe'
    $configPath = Join-Path (Split-Path -Parent $InstalledDir) 'daemon.json'
    # 与安装器同一条 Windows 命令行规则：每个参数都加双引号。配置路径是真实 Windows 路径且以 daemon.json 结尾，
    # 不含双引号或尾随反斜杠，因此转义后仍是两侧各一个引号。
    $expectedArguments = '"main.js" "--config" "' + $configPath + '"'

    $task = Find-ManagedTask $TaskName
    if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }
    if ([string]$task.Description -ne $TaskDescription) { Fail-Closed 'managed scheduled task is not owned by the installer' }
    $actions = @($task.Actions)
    if ($actions.Count -ne 1) { Fail-Closed 'managed scheduled task does not have exactly one action' }
    $action = $actions[0]
    if (-not (Test-SamePath -Left ([string]$action.Execute) -Right $expectedNode)) {
        Fail-Closed 'managed scheduled task does not run the managed runtime'
    }
    if ([string]$action.Arguments -ne $expectedArguments) {
        Fail-Closed 'managed scheduled task arguments do not match the managed configuration'
    }
    if (-not (Test-SamePath -Left ([string]$action.WorkingDirectory) -Right $InstalledDir)) {
        Fail-Closed 'managed scheduled task working directory does not match the managed installation'
    }

    Stop-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath

    # 等到受管任务真正停下来再替换发行目录：Running 与 Queued 都还持有任务，排队中的实例可能马上启动，
    # 因此两者都必须等待；旧进程仍持有受管文件时替换即失败，绝不猜测时序。
    $stopDeadline = (Get-Date).AddSeconds($StopWaitSeconds)
    while ($true) {
        $current = Find-ManagedTask $TaskName
        if ($null -eq $current -or [string]$current.State -notin @('Running', 'Queued')) { break }
        if ((Get-Date) -gt $stopDeadline) { Fail-Closed 'managed scheduled task did not stop' }
        Start-Sleep -Seconds 1
    }

    # 唯一 backup 目录：绝不覆盖上一次留下的唯一旧备份。
    $Step = 'backup'
    $backupDir = Join-Path $OperationDir ('backup-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
    $previous = Join-Path $backupDir 'previous'
    Move-Item -LiteralPath $InstalledDir -Destination $previous
    # 替换失败不自动回滚：旧发行保留在 $previous，失败证据保留，服务保持停止。
    $Step = 'replace'
    Move-Item -LiteralPath $StagedDir -Destination $InstalledDir

    $Step = 'restart'
    Start-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath

    # 成功回执是尽力而为：发行目录已经替换、服务已经重启，写不出回执只报告，绝不改判为失败。
    try {
        Write-Result 'PREPARED' 'distribution replaced and service restarted'
    } catch {
        Write-UpdateWarning "could not write the PREPARED receipt ($($_.Exception.GetType().Name))"
    }
} catch {
    if ($FailClosedMessage) {
        $failure = $FailClosedMessage
    } else {
        # 意外异常：只报告安全的阶段与异常类型。
        $failure = "update failed during \${Step} ($($_.Exception.GetType().Name))"
    }
} finally {
    # 报告与清理互相独立：诊断本身可能失败，因此一次性任务清理放在它自己的 finally 里，
    # 绝不会因为报告失败而被跳过，也不会掩盖主结果。
    try {
        if ($null -ne $failure) {
            try {
                Write-Result 'FAILED' $failure
            } catch {
                Write-UpdateWarning "could not write the failure receipt ($($_.Exception.GetType().Name))"
            }
            Write-FailureDiagnostic $failure
        }
    } finally {
        try {
            Unregister-ScheduledTask -TaskName $UpdateTaskName -Confirm:$false -ErrorAction Stop
        } catch {
            Write-UpdateWarning "could not unregister its one-shot task ($($_.Exception.GetType().Name))"
        }
    }
}

if ($null -ne $failure) { exit 1 }
`
