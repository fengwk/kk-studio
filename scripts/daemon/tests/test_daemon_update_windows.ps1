# Managed update script contracts for the shipped Node Daemon.
#
# The update script is imported from daemon/src/update-scripts.ts through Node 24 and then run in
# a disposable child Windows PowerShell process. Only the ScheduledTasks cmdlets are replaced by
# in-process stubs; no real task is read, stopped or started, and no path outside the temporary
# fixture is touched. The Node updater owns replacement state; this fixture only verifies the
# script's run/stop/start actions and the installer ownership gate.
#
# The import goes through the shared scripts/daemon/tests/export-update-script.mjs with a path and
# an export name: Windows PowerShell 5.1 drops the quotes of an inline `-e '<source>'` when it
# forwards native argv, and it decodes native stdout with the console code page. Both would corrupt
# the shipped text, so the script body only ever travels as a UTF-8 file.
#
# This script needs Windows PowerShell 5.1 or PowerShell 7 on Windows: it reads the real
# WindowsIdentity SID and drives ScheduledTasks-shaped objects.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$updateScripts = Join-Path $repoRoot 'daemon\src\update-scripts.ts'
if (-not (Test-Path -LiteralPath $updateScripts)) {
    throw "update script source not found: $updateScripts"
}
$exporter = Join-Path $PSScriptRoot 'export-update-script.mjs'
if (-not (Test-Path -LiteralPath $exporter)) {
    throw "update script exporter not found: $exporter"
}
$nodeCommand = Get-Command 'node.exe' -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -eq $nodeCommand) {
    throw 'a real node.exe is required to import the shipped update script'
}
$sandbox = Join-Path ([IO.Path]::GetTempPath()) ('kk-update-' + [guid]::NewGuid().ToString('N'))
$utf8 = [Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Path $sandbox -Force | Out-Null
$exportedScript = Join-Path $sandbox 'exported-update-script.ps1'
& $nodeCommand.Source $exporter $updateScripts 'WINDOWS_UPDATE_SCRIPT' $exportedScript
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $exportedScript)) {
    throw 'cannot import WINDOWS_UPDATE_SCRIPT from daemon/src/update-scripts.ts'
}
# Keep the production text byte-for-byte except for the bounded stop wait, which is shortened so a
# stuck-task timeout stays inside the test budget. The production value is pinned by the static
# contract test.
$updateText = [IO.File]::ReadAllText($exportedScript, $utf8).
    Replace('$StopWaitSeconds = 30', '$StopWaitSeconds = 2')
$updateScript = Join-Path $sandbox 'kk-studio-daemon-update.ps1'
[IO.File]::WriteAllText($updateScript, $updateText, $utf8)

$hostExe = (Get-Process -Id $PID).Path
$operationId = '11111111-1111-1111-1111-111111111111'
$operationDir = Join-Path $sandbox "updates/$operationId"
$staged = Join-Path $operationDir 'extract'
$installed = Join-Path $sandbox 'lib'
New-Item -ItemType Directory -Force -Path $operationDir, $staged, (Join-Path $installed 'runtime\code-server\lib') |
    Out-Null
[IO.File]::WriteAllText((Join-Path $installed 'runtime\code-server\lib\node.exe'), 'fixture node')
[IO.File]::WriteAllText((Join-Path $sandbox 'daemon.json'), '{}')
[IO.File]::WriteAllText((Join-Path $installed 'main.js'), 'fixture main')
$log = Join-Path $sandbox 'calls.log'
[IO.File]::WriteAllText($log, '')

# The fake updater entry records the exact direct argv the script passed and returns a controlled
# exit code, proving the run action launches an independent process with fixed paths. A real
# node.exe is used so the fixture also covers the shipped updater runtime contract.
$updaterEntry = Join-Path $sandbox 'updater-entry.mjs'
$updaterEntryText = @'
import { appendFileSync } from 'node:fs'

appendFileSync(process.env.KK_TEST_LOG, `updater|${process.argv.slice(2).join('|')}\n`)
process.exit(Number(process.env.KK_UPDATER_EXIT ?? '0'))
'@
[IO.File]::WriteAllText($updaterEntry, $updaterEntryText, $utf8)

$runner = Join-Path $sandbox 'runner.ps1'
$runnerText = @'
$ErrorActionPreference = 'Stop'
$logPath = $env:KK_TEST_LOG
function Add-TestLog([string]$Message) {
    Add-Content -LiteralPath $logPath -Value $Message
}
$installedDir = $env:KK_TEST_INSTALLED
$state = $env:KK_TEST_STATE
if ([string]::IsNullOrEmpty($state)) { $state = 'Ready' }
$global:fixtureState = $state
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$taskName = "kk-studio-environment-daemon-$sid"
$mode = $env:KK_TEST_TASK_MODE
if ([string]::IsNullOrEmpty($mode)) { $mode = 'owned' }
$description = if ($mode -eq 'foreign-description') {
    'someone else'
} else {
    "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$sid"
}
$expectedNode = Join-Path $installedDir 'runtime\code-server\lib\node.exe'
$configPath = Join-Path (Split-Path -Parent $installedDir) 'daemon.json'
$expectedTaskArguments = '"main.js" "--config" "' + $configPath + '"'
$execute = if ($mode -eq 'foreign-runtime') { 'C:\other\node.exe' } else { $expectedNode }
$taskArguments = if ($mode -eq 'foreign-arguments') {
    $expectedTaskArguments + ' --extra'
} else {
    $expectedTaskArguments
}
$workingDirectory = if ($mode -eq 'foreign-working-directory') { 'C:\other' } else { $installedDir }

function New-TaskAction {
    return [pscustomobject]@{
        Execute          = $execute
        Arguments        = $taskArguments
        WorkingDirectory = $workingDirectory
    }
}
function Get-ScheduledTask {
    param([string]$ErrorAction = 'Stop')
    Add-TestLog ('get:' + $global:fixtureState)
    if ($env:KK_TEST_GET_FAIL -eq '1') { throw 'Task Scheduler service is unavailable' }
    if ($mode -eq 'missing') { return $null }
    $taskAction = New-TaskAction
    $actions = if ($mode -eq 'multi-action') { @($taskAction, $taskAction) } else { @($taskAction) }
    return [pscustomobject]@{
        TaskName    = $taskName
        TaskPath    = '\'
        Description = $description
        State       = $global:fixtureState
        Actions     = $actions
    }
}
function Stop-ScheduledTask {
    param([string]$TaskName, [string]$TaskPath, [string]$ErrorAction = 'Stop')
    Add-TestLog 'stop'
    if ($env:KK_TEST_STOP_FAIL -eq '1') { throw 'the managed task cannot be stopped' }
    if ($env:KK_TEST_STOP_STUCK -eq '1') { return }
    $global:fixtureState = 'Ready'
}
function Start-ScheduledTask {
    param([string]$TaskName, [string]$TaskPath, [string]$ErrorAction = 'Stop')
    Add-TestLog 'start'
    if ($env:KK_TEST_START_FAIL -eq '1') { throw 'the managed task cannot be started' }
    $global:fixtureState = 'Running'
}

# Hashtable splatting binds named parameters such as -Action, -UpdaterNode and -UpdaterEntry;
# a string array would pass their names as positional values instead.
$scriptArguments = @{
    Action       = $env:KK_TEST_ACTION
    OperationDir = $env:KK_TEST_OPERATION
    StagedDir    = $env:KK_TEST_STAGED
    InstalledDir = $env:KK_TEST_INSTALLED
}
if (-not [string]::IsNullOrEmpty($env:KK_TEST_UPDATER_NODE)) {
    $scriptArguments.UpdaterNode = $env:KK_TEST_UPDATER_NODE
}
if (-not [string]::IsNullOrEmpty($env:KK_TEST_UPDATER_ENTRY)) {
    $scriptArguments.UpdaterEntry = $env:KK_TEST_UPDATER_ENTRY
}
$global:LASTEXITCODE = 1
& $env:KK_TEST_SCRIPT @scriptArguments
exit $LASTEXITCODE
'@
[IO.File]::WriteAllText($runner, $runnerText, $utf8)

$script:checks = 0
$script:failures = 0
$script:currentCase = $null

function Assert-True([bool]$Condition, [string]$Message) {
    $script:checks++
    if ($Condition) {
        Write-Host "ok: $Message"
    } else {
        Write-Host "FAIL: $Message"
        if ($null -ne $script:currentCase) {
            Write-Host ("case: {0}, exit: {1}" -f $script:currentCase.Name, $script:currentCase.Status)
            if (-not [string]::IsNullOrWhiteSpace($script:currentCase.Stderr)) {
                Write-Host ("stderr:`n{0}" -f $script:currentCase.Stderr)
            }
        }
        $script:failures++
    }
}

function Read-Text([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ''
    }
    return [IO.File]::ReadAllText($Path, [Text.UTF8Encoding]::new($false))
}

function Read-Log {
    return @((Read-Text $log) -split "`r?`n" | Where-Object { $_ -ne '' })
}

$caseKeys = @(
    'KK_TEST_ACTION',
    'KK_TEST_STATE',
    'KK_TEST_TASK_MODE',
    'KK_TEST_STOP_FAIL',
    'KK_TEST_STOP_STUCK',
    'KK_TEST_START_FAIL',
    'KK_TEST_GET_FAIL',
    'KK_TEST_UPDATER_NODE',
    'KK_TEST_UPDATER_ENTRY',
    'KK_UPDATER_EXIT'
)

function Set-CaseEnvironment([hashtable]$Overrides = @{}) {
    foreach ($key in $caseKeys) {
        Remove-Item -Path ('env:' + $key) -ErrorAction SilentlyContinue
    }
    $env:KK_TEST_SCRIPT = $updateScript
    $env:KK_TEST_LOG = $log
    $env:KK_TEST_INSTALLED = $installed
    $env:KK_TEST_OPERATION = $operationDir
    $env:KK_TEST_STAGED = $staged
    foreach ($key in $Overrides.Keys) {
        Set-Item -Path ('env:' + $key) -Value ([string] $Overrides[$key])
    }
}

function Invoke-Case([string]$Name, [hashtable]$Env = @{}) {
    [IO.File]::WriteAllText($log, '')
    Set-CaseEnvironment $Env
    $stdout = Join-Path $sandbox "$Name.stdout.txt"
    $stderr = Join-Path $sandbox "$Name.stderr.txt"
    $process = Start-Process -FilePath $hostExe `
        -ArgumentList @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                        '-File', ('"' + $runner + '"')) `
        -NoNewWindow -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    # Windows PowerShell 5.1 drops the native handle when a Process object leaves a function; read
    # Handle immediately so ExitCode stays reliable instead of becoming $null.
    [void] $process.Handle
    $process.WaitForExit()
    Start-Sleep -Milliseconds 200
    $process.Refresh()
    if ($null -eq $process.ExitCode) {
        throw "cannot read the exit code of case '$Name'; the native process handle was lost"
    }
    $script:currentCase = [pscustomobject]@{
        Name   = $Name
        Status = [int] $process.ExitCode
        Stdout = (Read-Text $stdout)
        Stderr = (Read-Text $stderr)
        Log    = @(Read-Log)
    }
    return $script:currentCase
}

function Assert-Log([object]$Case, [string]$Expected) {
    $actual = ($Case.Log -join '|')
    Assert-True ($actual -eq $Expected) "log is '$Expected' (actual: '$actual')"
}

function Assert-NoServiceAction([object]$Case) {
    Assert-True (-not ($Case.Log -contains 'stop')) 'no stop action occurs'
    Assert-True (-not ($Case.Log -contains 'start')) 'no start action occurs'
}

try {
    $expectedUpdater = @(
        'updater',
        '--operation-dir', $operationDir,
        '--staged-dir', $staged,
        '--installed-dir', $installed,
        '--update-script', $updateScript
    ) -join '|'

    # 1. stop really stops and never starts; this is the regression for the case-insensitive
    #    variable shadowing that made $Action see the task action object instead of 'stop'.
    $case = Invoke-Case 'stop' @{ KK_TEST_ACTION = 'stop'; KK_TEST_STATE = 'Running' }
    Assert-True ($case.Status -eq 0) 'stop exits 0'
    Assert-Log $case 'get:Running|stop|get:Ready'
    # PowerShell pipelines unwrap empty and singleton arrays; verify each log cardinality.
    Assert-True (($case.Log -is [array]) -and ($case.Log.Count -eq 3)) 'multiple log entries remain an array'
    Assert-True (-not ($case.Log -contains 'start')) 'stop never starts the task'

    # 2. Queued is treated like Running: the task must not be restarted while it still holds the
    #    runtime.
    $case = Invoke-Case 'stop-queued' @{ KK_TEST_ACTION = 'stop'; KK_TEST_STATE = 'Queued' }
    Assert-True ($case.Status -eq 0) 'a Queued task can be stopped'
    Assert-Log $case 'get:Queued|stop|get:Ready'

    # 3. start really starts and never stops.
    $case = Invoke-Case 'start' @{ KK_TEST_ACTION = 'start'; KK_TEST_STATE = 'Ready' }
    Assert-True ($case.Status -eq 0) 'start exits 0'
    Assert-Log $case 'get:Ready|start'
    Assert-True (-not ($case.Log -contains 'stop')) 'start never stops the task'

    # 4. A stuck task fails closed after the bounded wait, without a start fallback.
    $case = Invoke-Case 'stop-stuck' @{
        KK_TEST_ACTION = 'stop'
        KK_TEST_STATE = 'Running'
        KK_TEST_STOP_STUCK = '1'
    }
    Assert-True ($case.Status -ne 0) 'a stuck task exits non-zero'
    Assert-True ($case.Stderr -match 'managed scheduled task did not stop') 'a stuck task reports the stop wait'
    Assert-True ((@($case.Log | Where-Object { $_ -eq 'get:Running' }).Count) -ge 2) 'the stop wait polls Running'
    Assert-True (-not ($case.Log -contains 'start')) 'a stuck task is never started'

    # 5. A missing task and a foreign task fail before any stop/start.
    $case = Invoke-Case 'missing' @{ KK_TEST_ACTION = 'stop'; KK_TEST_TASK_MODE = 'missing' }
    Assert-True ($case.Status -ne 0) 'a missing task exits non-zero'
    Assert-True ($case.Stderr -match 'managed scheduled task is missing') 'a missing task reports its reason'
    Assert-Log $case 'get:Ready'
    Assert-True (($case.Log -is [array]) -and ($case.Log.Count -eq 1)) 'one log entry remains an array'
    Assert-NoServiceAction $case

    $case = Invoke-Case 'foreign-description' @{
        KK_TEST_ACTION = 'stop'
        KK_TEST_TASK_MODE = 'foreign-description'
    }
    Assert-True ($case.Status -ne 0) 'a foreign description exits non-zero'
    Assert-True ($case.Stderr -match 'managed scheduled task is not owned by the installer') `
        'a foreign description reports the ownership reason'
    Assert-Log $case 'get:Ready'
    Assert-NoServiceAction $case

    $case = Invoke-Case 'foreign-description-start' @{
        KK_TEST_ACTION = 'start'
        KK_TEST_TASK_MODE = 'foreign-description'
    }
    Assert-True ($case.Status -ne 0) 'start also rejects a foreign description'
    Assert-Log $case 'get:Ready'
    Assert-NoServiceAction $case

    # 6. Every action field participates in the ownership proof.
    $case = Invoke-Case 'foreign-runtime' @{
        KK_TEST_ACTION = 'stop'
        KK_TEST_TASK_MODE = 'foreign-runtime'
    }
    Assert-True ($case.Status -ne 0) 'a foreign runtime exits non-zero'
    Assert-True ($case.Stderr -match 'managed scheduled task does not run the managed runtime') `
        'a foreign runtime reports the runtime reason'
    Assert-NoServiceAction $case

    $case = Invoke-Case 'foreign-arguments' @{
        KK_TEST_ACTION = 'stop'
        KK_TEST_TASK_MODE = 'foreign-arguments'
    }
    Assert-True ($case.Status -ne 0) 'foreign arguments exit non-zero'
    Assert-True ($case.Stderr -match 'managed scheduled task arguments do not match the managed configuration') `
        'foreign arguments report the configured argv reason'
    Assert-NoServiceAction $case

    $case = Invoke-Case 'foreign-working-directory' @{
        KK_TEST_ACTION = 'stop'
        KK_TEST_TASK_MODE = 'foreign-working-directory'
    }
    Assert-True ($case.Status -ne 0) 'a foreign working directory exits non-zero'
    Assert-True ($case.Stderr -match 'managed scheduled task working directory does not match the managed installation') `
        'a foreign working directory reports the installation reason'
    Assert-NoServiceAction $case

    $case = Invoke-Case 'multi-action' @{
        KK_TEST_ACTION = 'stop'
        KK_TEST_TASK_MODE = 'multi-action'
    }
    Assert-True ($case.Status -ne 0) 'a multi-action task exits non-zero'
    Assert-True ($case.Stderr -match 'managed scheduled task does not have exactly one action') `
        'a multi-action task reports the action count reason'
    Assert-NoServiceAction $case

    # 7. A genuine scheduler failure is not mistaken for an absent task and performs no action.
    $case = Invoke-Case 'scheduler-failure' @{
        KK_TEST_ACTION = 'stop'
        KK_TEST_GET_FAIL = '1'
    }
    Assert-True ($case.Status -ne 0) 'a scheduler failure exits non-zero'
    Assert-NoServiceAction $case

    # 8. Stop and start failures propagate without crossing into the other action.
    $case = Invoke-Case 'stop-failure' @{
        KK_TEST_ACTION = 'stop'
        KK_TEST_STATE = 'Running'
        KK_TEST_STOP_FAIL = '1'
    }
    Assert-True ($case.Status -ne 0) 'a stop failure exits non-zero'
    Assert-Log $case 'get:Running|stop'
    Assert-True (-not ($case.Log -contains 'start')) 'a stop failure never starts the task'

    $case = Invoke-Case 'start-failure' @{
        KK_TEST_ACTION = 'start'
        KK_TEST_STATE = 'Ready'
        KK_TEST_START_FAIL = '1'
    }
    Assert-True ($case.Status -ne 0) 'a start failure exits non-zero'
    Assert-Log $case 'get:Ready|start'
    Assert-True (-not ($case.Log -contains 'stop')) 'a start failure never stops the task'

    # 9. run launches only the independent updater and propagates its exit code.
    $case = Invoke-Case 'run' @{
        KK_TEST_ACTION = 'run'
        KK_TEST_UPDATER_NODE = $nodeCommand.Source
        KK_TEST_UPDATER_ENTRY = $updaterEntry
        KK_UPDATER_EXIT = '7'
    }
    Assert-True ($case.Status -eq 7) 'the updater exit code propagates through run'
    Assert-Log $case $expectedUpdater
    Assert-True (-not ($case.Log -contains 'get:Ready')) 'run does not read the managed task'

    # 10. run requires both updater paths and a real runtime/entry.
    $case = Invoke-Case 'run-missing-arguments' @{ KK_TEST_ACTION = 'run' }
    Assert-True ($case.Status -ne 0) 'run without updater arguments exits non-zero'
    Assert-True ($case.Stderr -match 'run requires updater node and entry') 'run reports missing updater arguments'
    Assert-True ($case.Log -is [array]) 'an empty log remains an array'
    Assert-True ($case.Log.Count -eq 0) 'run without arguments touches no task'

    $case = Invoke-Case 'run-missing-runtime' @{
        KK_TEST_ACTION = 'run'
        KK_TEST_UPDATER_NODE = (Join-Path $sandbox 'missing-node.exe')
        KK_TEST_UPDATER_ENTRY = $updaterEntry
    }
    Assert-True ($case.Status -ne 0) 'run with a missing runtime exits non-zero'
    Assert-True ($case.Stderr -match 'updater runtime is missing') 'run reports a missing runtime'
    Assert-True ($case.Log.Count -eq 0) 'run with a missing runtime touches no task'

    $case = Invoke-Case 'run-missing-entry' @{
        KK_TEST_ACTION = 'run'
        KK_TEST_UPDATER_NODE = $nodeCommand.Source
        KK_TEST_UPDATER_ENTRY = (Join-Path $sandbox 'missing-entry.mjs')
    }
    Assert-True ($case.Status -ne 0) 'run with a missing entry exits non-zero'
    Assert-True ($case.Stderr -match 'updater entry is missing') 'run reports a missing entry'
    Assert-True ($case.Log.Count -eq 0) 'run with a missing entry touches no task'
} finally {
    Remove-Item -LiteralPath $sandbox -Recurse -Force -ErrorAction SilentlyContinue
}

if ($script:failures -gt 0) {
    Write-Host ("Windows update fixture: {0} checks, {1} failures" -f $script:checks, $script:failures)
    exit 1
}
Write-Host ("Windows update fixture: {0} checks, 0 failures" -f $script:checks)
exit 0
