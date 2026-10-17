# Managed update script contracts for the shipped Node Daemon.
#
# The update script is imported from daemon/src/update-scripts.ts through Node 24, then run in
# a disposable child Windows PowerShell process with the scheduled-task API replaced by
# in-process stubs. No real scheduled task is ever read, stopped, started or unregistered, and
# no path outside the temporary fixture is touched.
#
# The exported text keeps every production statement. Only the two wall-clock bounds are
# shortened in the temporary copy so a missing permit fails within the test budget instead of
# waiting the production 120 seconds.
#
# Covered: the operation identity gate, the permit gate (missing, mismatched content and a
# permit that arrives after ACCEPTED was frozen), the state gate that runs after the permit,
# managed task ownership (description, exactly one action, frozen node.exe, direct argv and
# working directory at the scheduler root), the Running/Queued stop wait, the unique backup and
# the backup-before-replace ordering, stop/backup/replace/restart failures, receipt failures that
# never skip the one-shot cleanup, and the absence of real service actions in every rejected case.
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
$nodeCommand = Get-Command 'node.exe' -CommandType Application -ErrorAction SilentlyContinue |
  Select-Object -First 1
if ($null -eq $nodeCommand) {
  throw 'a real node.exe is required to import the shipped update script'
}
$exported = & $nodeCommand.Source --input-type=module -e `
  'import { pathToFileURL } from "node:url"; const m = await import(pathToFileURL(process.argv[1])); process.stdout.write(m[process.argv[2]])' `
  $updateScripts 'WINDOWS_UPDATE_SCRIPT'
if ($LASTEXITCODE -ne 0) {
  throw 'cannot import WINDOWS_UPDATE_SCRIPT from daemon/src/update-scripts.ts'
}
$sandbox = Join-Path ([IO.Path]::GetTempPath()) ('kk-update-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $sandbox -Force | Out-Null
$updateScript = Join-Path $sandbox 'kk-studio-daemon-update.ps1'
$updateText = ($exported -join "`n").
  Replace('$PermitWaitSeconds = 120', '$PermitWaitSeconds = 2').
  Replace('$StopWaitSeconds = 30', '$StopWaitSeconds = 2')
[IO.File]::WriteAllText($updateScript, $updateText, [Text.UTF8Encoding]::new($false))

$hostExe = (Get-Process -Id $PID).Path
$operationId = '11111111-1111-1111-1111-111111111111'
$otherOperationId = '22222222-2222-2222-2222-222222222222'

$script:failures = 0
$script:checks = 0

function Assert-True([bool]$Condition, [string]$Message) {
  $script:checks++
  if ($Condition) {
    Write-Host "ok: $Message"
  } else {
    Write-Host "FAIL: $Message"
    $script:failures++
  }
}

function New-Case([string]$Name, [string]$HandoffState = 'ACCEPTED',
                  [string]$HandoffOperation = $operationId, [string]$Permit = 'match') {
  $root = Join-Path ([IO.Path]::GetTempPath()) ('kk-update-' + $Name + '-' + [guid]::NewGuid().ToString('N'))
  $updateDir = Join-Path $root "updates/$operationId"
  $staged = Join-Path $updateDir 'extract'
  $installed = Join-Path $root 'lib'
  New-Item -ItemType Directory -Force -Path $updateDir, $staged, $installed | Out-Null
  $case = [pscustomobject]@{
    Name       = $Name
    Root       = $root
    UpdateDir  = $updateDir
    Staged     = $staged
    Installed  = $installed
    Config     = (Join-Path $root 'daemon.json')
    Handoff    = (Join-Path $updateDir 'handoff.json')
    Permit     = (Join-Path $updateDir 'permit')
    Result     = (Join-Path $updateDir 'update-result.json')
    Log        = (Join-Path $root 'calls.txt')
    StderrFile = (Join-Path $root 'stderr.txt')
    StdoutFile = (Join-Path $root 'stdout.txt')
    Runner     = (Join-Path $root 'runner.ps1')
  }
  [IO.File]::WriteAllText((Join-Path $staged 'main.js'), 'staged-release-main-js')
  [IO.File]::WriteAllText((Join-Path $installed 'main.js'), 'installed-release-main-js')
  [IO.File]::WriteAllText($case.Config, '{}')
  [IO.File]::WriteAllText($case.Log, '')
  if ($HandoffState -ne 'missing') {
    Write-Handoff -Case $case -State $HandoffState -Operation $HandoffOperation
  }
  if ($Permit -eq 'match') {
    [IO.File]::WriteAllText($case.Permit, $operationId)
  }
  return $case
}

function Write-Handoff([object]$Case, [string]$State, [string]$Operation = $operationId) {
  $handoff = [ordered]@{
    state         = $State
    operationId   = $Operation
    targetVersion = '1.1.1'
  }
  [IO.File]::WriteAllText($Case.Handoff, ($handoff | ConvertTo-Json -Compress))
}

function Get-RunnerTemplate {
  return @'
$ErrorActionPreference = 'Stop'
$logPath = $env:KK_TEST_LOG
function Add-TestLog([string]$message) { Add-Content -LiteralPath $logPath -Value $message }
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$serviceTask = "kk-studio-environment-daemon-$sid"
$expectedDescription = "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$sid"
$installedDir = '__INSTALLEDDIR__'
$configPath = '__CONFIG__'
$expectedNode = Join-Path $installedDir 'runtime\code-server\lib\node.exe'
$expectedArguments = '"main.js" "--config" "' + $configPath + '"'
# Stub state that straddles the invocation lives in the global scope: the call operator below
# makes `$script:` inside a stub resolve to the callee's scope instead of this runner's.
$global:stateIndex = 0
$global:moveCount = 0
function New-Action([string]$Execute, [string]$Arguments, [string]$WorkingDirectory) {
  return [pscustomobject]@{
    Execute          = $Execute
    Arguments        = $Arguments
    WorkingDirectory = $WorkingDirectory
  }
}
function New-Task([string]$Description, [string]$State, $Actions, [string]$TaskName = $serviceTask) {
  return [pscustomobject]@{
    TaskName    = $TaskName
    TaskPath    = '\'
    Description = $Description
    State       = $State
    Actions     = $Actions
  }
}
function New-ManagedAction {
  return New-Action -Execute $expectedNode -Arguments $expectedArguments -WorkingDirectory $installedDir
}
__GET__
__STOP__
__START__
__UNREGISTER__
__EXTRA__
$LASTEXITCODE = 0
$global:childFailed = $false
try {
  & '__SCRIPT__' -OperationDir '__OPERATION__' -StagedDir '__STAGED__' -InstalledDir '__INSTALLED__'
}
catch {
  $global:childFailed = $true
  Add-TestLog ('ChildException:' + $_.Exception.GetType().Name)
}
if ($global:childFailed) { exit 1 }
exit $LASTEXITCODE
'@
}

function Write-Runner([object]$Case, [string]$Get, [string]$Stop, [string]$Start,
                      [string]$Unregister, [string]$Extra = '') {
  $template = Get-RunnerTemplate
  $runner = $template.
    Replace('__GET__', $Get).
    Replace('__STOP__', $Stop).
    Replace('__START__', $Start).
    Replace('__UNREGISTER__', $Unregister).
    Replace('__EXTRA__', $Extra).
    Replace('__SCRIPT__', $updateScript).
    Replace('__OPERATION__', $Case.UpdateDir).
    Replace('__STAGED__', $Case.Staged).
    Replace('__INSTALLED__', $Case.Installed).
    Replace('__INSTALLEDDIR__', $Case.Installed).
    Replace('__CONFIG__', $Case.Config)
  [IO.File]::WriteAllText($Case.Runner, $runner, [Text.UTF8Encoding]::new($false))
}

function Start-Case([object]$Case, [string]$Get, [string]$Stop, [string]$Start,
                    [string]$Unregister, [string]$Extra = '', [hashtable]$Env = @{}) {
  Write-Runner -Case $Case -Get $Get -Stop $Stop -Start $Start -Unregister $Unregister -Extra $Extra
  $env:KK_TEST_LOG = $Case.Log
  $env:KK_TEST_STATE = 'Ready'
  $env:KK_TEST_STATE_SEQUENCE = ''
  foreach ($name in $Env.Keys) {
    Set-Item -Path ('env:' + $name) -Value $Env[$name]
  }
  return Start-Process -FilePath $hostExe `
    -ArgumentList @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                    '-File', ('"' + $Case.Runner + '"')) `
    -NoNewWindow -PassThru `
    -RedirectStandardOutput $Case.StdoutFile -RedirectStandardError $Case.StderrFile
}

function Wait-Case([object]$Case, $Process) {
  $Process.WaitForExit()
  # With -RedirectStandard* the copy into the files is asynchronous; give it a moment.
  Start-Sleep -Milliseconds 300
  return $Process.ExitCode
}

function Invoke-Case([object]$Case, [string]$Get, [string]$Stop, [string]$Start,
                     [string]$Unregister, [string]$Extra = '', [hashtable]$Env = @{}) {
  $process = Start-Case -Case $Case -Get $Get -Stop $Stop -Start $Start `
    -Unregister $Unregister -Extra $Extra -Env $Env
  return (Wait-Case -Case $Case -Process $process)
}

function Read-Stderr([object]$Case) {
  if (-not (Test-Path -LiteralPath $Case.StderrFile)) {
    return ''
  }
  return (Get-Content -LiteralPath $Case.StderrFile -Raw)
}

function Read-Result([object]$Case) {
  if (-not (Test-Path -LiteralPath $Case.Result -PathType Leaf)) {
    return $null
  }
  return (Get-Content -LiteralPath $Case.Result -Raw | ConvertFrom-Json)
}

function Read-Log([object]$Case) {
  if (-not (Test-Path -LiteralPath $Case.Log)) {
    return ''
  }
  return (Get-Content -LiteralPath $Case.Log -Raw)
}

function Read-Backup([object]$Case) {
  $directories = @(Get-ChildItem -LiteralPath $Case.UpdateDir -Directory -Filter 'backup-*')
  if ($directories.Count -ne 1) {
    return @{ Count = $directories.Count; Content = $null }
  }
  $previous = Join-Path $directories[0].FullName 'previous\main.js'
  if (-not (Test-Path -LiteralPath $previous -PathType Leaf)) {
    return @{ Count = $directories.Count; Content = $null }
  }
  return @{ Count = $directories.Count; Content = (Get-Content -LiteralPath $previous -Raw) }
}

function Remove-Case([object]$Case) {
  try {
    Remove-Item -LiteralPath $Case.Root -Recurse -Force -ErrorAction SilentlyContinue
  } catch {
  }
}

$defaultGet = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  $state = $env:KK_TEST_STATE
  if ($env:KK_TEST_STATE_SEQUENCE) {
    $states = $env:KK_TEST_STATE_SEQUENCE.Split(',')
    $index = [Math]::Min($global:stateIndex, $states.Count - 1)
    $state = $states[$index]
    $global:stateIndex++
  }
  Add-TestLog "GetScheduledTask:$state"
  return New-Task -Description $expectedDescription -State $state -Actions @(New-ManagedAction)
}
'@
$defaultStop = @'
function Stop-ScheduledTask {
  param([string]$TaskName, [string]$TaskPath, [string]$ErrorAction)
  Add-TestLog "StopScheduledTask:$TaskName"
}
'@
$defaultStart = @'
function Start-ScheduledTask {
  param([string]$TaskName, [string]$TaskPath, [string]$ErrorAction)
  Add-TestLog "StartScheduledTask:$TaskName"
}
'@
$defaultUnregister = @'
function Unregister-ScheduledTask {
  param([string]$TaskName, [switch]$Confirm, [string]$ErrorAction)
  Add-TestLog "UnregisterScheduledTask:$TaskName"
}
'@

# 1. Success: permit, ACCEPTED handoff, ownership, unique backup, replace, restart.
$case = New-Case 'success'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  $log = Read-Log $case
  Assert-True ($exit -eq 0) 'success exits 0'
  Assert-True ($result.phase -eq 'PREPARED') 'success writes the PREPARED receipt'
  Assert-True ((Get-Content -LiteralPath (Join-Path $case.Installed 'main.js') -Raw) -eq 'staged-release-main-js') `
    'success replaces the installed distribution'
  Assert-True (-not (Test-Path -LiteralPath $case.Staged)) 'success consumes the staged distribution'
  $backup = Read-Backup $case
  Assert-True ($backup.Count -eq 1) 'success leaves exactly one unique backup directory'
  Assert-True ($backup.Content -eq 'installed-release-main-js') `
    'success keeps the previous distribution in backup-*/previous'
  Assert-True ($log -match 'StopScheduledTask:kk-studio-environment-daemon-') `
    'success stops the managed task'
  Assert-True ($log -match 'StartScheduledTask') 'success restarts the managed task'
  Assert-True ($log -match 'UnregisterScheduledTask') 'success unregisters the one-shot update task'
} finally { Remove-Case $case }

# 2. The stop wait keeps polling while the task reports Running, then proceeds.
$case = New-Case 'stop-wait'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister `
    -Env @{ KK_TEST_STATE_SEQUENCE = 'Running,Ready' }
  $log = Read-Log $case
  Assert-True ($exit -eq 0) 'a Running task eventually stops without failing'
  Assert-True ($log -match 'GetScheduledTask:Running') 'the stop wait polls the Running state'
  Assert-True ($log -match 'StartScheduledTask') 'the update proceeds once the task is not Running'
} finally { Remove-Case $case }

# 3. A PREPARING handoff is the normal start state: the identity alone is checked up front and
#    only the missing permit fails the job.
$case = New-Case 'early-preparing' -HandoffState 'PREPARING' -Permit 'absent'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a transient PREPARING job exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'a transient PREPARING job writes a FAILED receipt'
  Assert-True ($result.message -eq 'the daemon did not release the update permit in time') `
    'an early PREPARING reaches the permit gate instead of being rejected as a mismatch'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'a transient PREPARING performs no destructive action'
} finally { Remove-Case $case }

# 4. An ACCEPTED handoff with no permit fails closed at the permit deadline.
$case = New-Case 'missing-permit' -Permit 'absent'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a missing permit exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'a missing permit writes a FAILED receipt'
  Assert-True ($result.message -eq 'the daemon did not release the update permit in time') `
    'a missing permit fails with the permit reason'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'a missing permit performs no destructive action'
  Assert-True ((Get-Content -LiteralPath (Join-Path $case.Installed 'main.js') -Raw) -eq 'installed-release-main-js') `
    'a missing permit leaves the installed distribution untouched'
} finally { Remove-Case $case }

# 5. A PREPARING handoff with a permit present is still refused: the state is re-read after the
#    permit and must be ACCEPTED.
$case = New-Case 'not-accepted' -HandoffState 'PREPARING'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'an unaccepted handoff exits non-zero'
  Assert-True ($result.message -eq 'handoff metadata is not accepted') `
    'only ACCEPTED may replace the distribution'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'an unaccepted handoff performs no destructive action'
} finally { Remove-Case $case }

# 6. A FAILED handoff is refused.
$case = New-Case 'failed-handoff' -HandoffState 'FAILED'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a failed handoff exits non-zero'
  Assert-True ($result.message -eq 'handoff metadata is not accepted') `
    'a failed handoff is not accepted'
} finally { Remove-Case $case }

# 7. Missing handoff metadata is refused before anything else.
$case = New-Case 'missing-handoff' -HandoffState 'missing'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a missing handoff exits non-zero'
  Assert-True ($result.message -eq 'handoff metadata is missing') `
    'a missing handoff reports the safe reason'
} finally { Remove-Case $case }

# 8. A handoff naming another operation is refused.
$case = New-Case 'mismatched-handoff' -HandoffOperation $otherOperationId
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a mismatched handoff exits non-zero'
  Assert-True ($result.message -eq 'handoff metadata does not match the operation') `
    'a mismatched handoff reports the safe reason'
} finally { Remove-Case $case }

# 9. A missing managed task is reported and still unregisters the one-shot task.
$case = New-Case 'missing'
try {
  $getMissing = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:missing'
  return $null
}
'@
  $exit = Invoke-Case $case $getMissing $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'missing task exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'missing task writes a FAILED receipt'
  Assert-True ($result.message -eq 'managed scheduled task is missing') `
    'missing task reports the safe reason'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'missing task still cleans up the one-shot update task'
} finally { Remove-Case $case }

# 10. A task not owned by the installer is rejected without a stop.
$case = New-Case 'not-owned'
try {
  $getForeign = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:foreign'
  return New-Task -Description 'someone else' -State 'Ready' -Actions @(New-ManagedAction)
}
'@
  $exit = Invoke-Case $case $getForeign $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'foreign task exits non-zero'
  Assert-True ($result.message -eq 'managed scheduled task is not owned by the installer') `
    'foreign task reports the safe reason'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'foreign task is rejected before anything is stopped'
} finally { Remove-Case $case }

# 11. An action running another executable is rejected.
$case = New-Case 'foreign-runtime'
try {
  $getForeignRuntime = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:foreign-runtime'
  return New-Task -Description $expectedDescription -State 'Ready' `
    -Actions @((New-Action -Execute 'C:\other\node.exe' -Arguments $expectedArguments `
      -WorkingDirectory $installedDir))
}
'@
  $exit = Invoke-Case $case $getForeignRuntime $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a foreign runtime exits non-zero'
  Assert-True ($result.message -eq 'managed scheduled task does not run the managed runtime') `
    'the action must run the runtime recorded by the handoff'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'a foreign runtime performs no destructive action'
} finally { Remove-Case $case }

# 12. Arguments other than the managed direct argv are rejected.
$case = New-Case 'foreign-arguments'
try {
  $getForeignArguments = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:foreign-arguments'
  return New-Task -Description $expectedDescription -State 'Ready' `
    -Actions @((New-Action -Execute $expectedNode -Arguments ($expectedArguments + ' --extra') `
      -WorkingDirectory $installedDir))
}
'@
  $exit = Invoke-Case $case $getForeignArguments $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'foreign arguments exit non-zero'
  Assert-True ($result.message -eq 'managed scheduled task arguments do not match the managed configuration') `
    'arguments must be the managed direct argv'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'foreign arguments perform no destructive action'
} finally { Remove-Case $case }

# 13. A failed replacement keeps the previous distribution in its unique backup and never
#     restarts the service. The production script has no rollback branch.
$case = New-Case 'replace-fail'
try {
  $replaceFails = @'
function Move-Item {
  param([string]$LiteralPath, [string]$Destination)
  Add-TestLog "MoveItem:$Destination"
  $global:moveCount++
  if ($global:moveCount -eq 2) { throw 'replacement is blocked' }
  Microsoft.PowerShell.Management\Move-Item -LiteralPath $LiteralPath -Destination $Destination
}
'@
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister $replaceFails
  $log = Read-Log $case
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'replace failure exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'replace failure writes a FAILED receipt'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'replace failure still cleans up the one-shot update task'
  Assert-True ($log -match 'MoveItem:') 'the real backup move runs before the replacement'
  Assert-True ((Read-Log $case) -notmatch 'StartScheduledTask') `
    'replace failure never restarts the managed task'
  $backup = Read-Backup $case
  Assert-True ($backup.Count -eq 1) 'replace failure leaves exactly one unique backup directory'
  Assert-True ($backup.Content -eq 'installed-release-main-js') `
    'replace failure keeps the previous distribution in its backup'
  Assert-True ((Test-Path -LiteralPath (Join-Path $case.Staged 'main.js') -PathType Leaf)) `
    'replace failure keeps the staged distribution'
} finally { Remove-Case $case }

# 14. A failed restart keeps the replaced distribution (no automatic rollback).
$case = New-Case 'restart-fail'
try {
  $startFails = @'
function Start-ScheduledTask {
  param([string]$TaskName, [string]$TaskPath, [string]$ErrorAction)
  Add-TestLog "StartScheduledTask:$TaskName"
  throw 'managed scheduled task cannot be started'
}
'@
  $exit = Invoke-Case $case $defaultGet $defaultStop $startFails $defaultUnregister
  $backup = Read-Backup $case
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'restart failure exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'restart failure writes a FAILED receipt'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'restart failure still cleans up the one-shot update task'
  Assert-True ((Get-Content -LiteralPath (Join-Path $case.Installed 'main.js') -Raw) -eq 'staged-release-main-js') `
    'restart failure keeps the replaced distribution'
  Assert-True ($backup.Content -eq 'installed-release-main-js') `
    'restart failure keeps the previous distribution in its backup'
} finally { Remove-Case $case }

# 15. A queued task is still holding the binary: the stop wait must not accept it either.
$case = New-Case 'queued'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister `
    -Env @{ KK_TEST_STATE_SEQUENCE = 'Queued,Ready' }
  $log = Read-Log $case
  Assert-True ($exit -eq 0) 'a Queued task eventually clears without failing'
  Assert-True ($log -match 'GetScheduledTask:Queued') 'the stop wait polls the Queued state'
  Assert-True ($log -match 'StartScheduledTask') 'the update proceeds once the task is neither Running nor Queued'
} finally { Remove-Case $case }

# 16. A same-named task in another folder is not the managed task: only the scheduler root counts.
$case = New-Case 'root-task-path'
try {
  $getOtherFolder = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:other-folder'
  return [pscustomobject]@{
    TaskName    = $serviceTask
    TaskPath    = '\Other\'
    Description = $expectedDescription
    State       = 'Ready'
    Actions     = @(New-ManagedAction)
  }
}
'@
  $exit = Invoke-Case $case $getOtherFolder $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a task outside the root exits non-zero'
  Assert-True ($result.message -eq 'managed scheduled task is missing') `
    'a task in another folder is not mistaken for the managed task'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'a foreign-folder task is never stopped'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'the one-shot update task is still cleaned up'
} finally { Remove-Case $case }

# 17. More than one action is not the installer's managed task.
$case = New-Case 'multi-action'
try {
  $getMultiAction = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:multi-action'
  return New-Task -Description $expectedDescription -State 'Ready' `
    -Actions @((New-ManagedAction), (New-ManagedAction))
}
'@
  $exit = Invoke-Case $case $getMultiAction $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a multi-action task exits non-zero'
  Assert-True ($result.message -eq 'managed scheduled task does not have exactly one action') `
    'exactly one action is required'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'a multi-action task is never stopped'
} finally { Remove-Case $case }

# 18. A different working directory is not the installer's managed task.
$case = New-Case 'foreign-working-directory'
try {
  $getForeignDirectory = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:foreign-directory'
  return New-Task -Description $expectedDescription -State 'Ready' `
    -Actions @((New-Action -Execute $expectedNode -Arguments $expectedArguments -WorkingDirectory 'C:\other'))
}
'@
  $exit = Invoke-Case $case $getForeignDirectory $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a foreign working directory exits non-zero'
  Assert-True ($result.message -eq 'managed scheduled task working directory does not match the managed installation') `
    'the working directory is part of the ownership proof'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'a foreign working directory performs no destructive action'
} finally { Remove-Case $case }

# 19. A permit naming another operation is not a release for this one.
$case = New-Case 'permit-mismatch'
try {
  [IO.File]::WriteAllText($case.Permit, $otherOperationId)
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a mismatched permit exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'a mismatched permit writes a FAILED receipt'
  Assert-True ($result.message -eq 'the update permit does not match the operation') `
    'the permit content must name this operation'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'a mismatched permit performs no destructive action'
  Assert-True ((Get-Content -LiteralPath (Join-Path $case.Installed 'main.js') -Raw) -eq 'installed-release-main-js') `
    'a mismatched permit leaves the installed distribution untouched'
} finally { Remove-Case $case }

# 20. The production sequence: the job starts with PREPARING and the permit arrives only after
#     the daemon froze ACCEPTED.
$case = New-Case 'late-permit' -HandoffState 'PREPARING' -Permit 'absent'
try {
  $process = Start-Case -Case $case -Get $defaultGet -Stop $defaultStop -Start $defaultStart `
    -Unregister $defaultUnregister
  Start-Sleep -Seconds 1
  Assert-True (-not (Test-Path -LiteralPath $case.Result)) `
    'an early PREPARING must not report a failure while it waits'
  Write-Handoff -Case $case -State 'ACCEPTED'
  [IO.File]::WriteAllText($case.Permit, $operationId)
  $exit = Wait-Case -Case $case -Process $process
  $result = Read-Result $case
  Assert-True ($exit -eq 0) 'the update applies once ACCEPTED and the permit arrive'
  Assert-True ($result.phase -eq 'PREPARED') 'a late permit still produces the PREPARED receipt'
  Assert-True ((Read-Log $case) -match 'StopScheduledTask') `
    'the update stops the managed task after the permit arrives'
} finally { Remove-Case $case }

# 21. An identity that changes while waiting for the permit still fails closed.
$case = New-Case 'identity-change' -HandoffState 'PREPARING' -Permit 'absent'
try {
  $process = Start-Case -Case $case -Get $defaultGet -Stop $defaultStop -Start $defaultStart `
    -Unregister $defaultUnregister
  Start-Sleep -Seconds 1
  Write-Handoff -Case $case -State 'ACCEPTED' -Operation $otherOperationId
  [IO.File]::WriteAllText($case.Permit, $operationId)
  $exit = Wait-Case -Case $case -Process $process
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a changed identity exits non-zero'
  Assert-True ($result.message -eq 'handoff metadata does not match the operation') `
    'the snapshot is re-read after the permit and must still match'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'a changed identity performs no destructive action'
} finally { Remove-Case $case }

# 22. A genuine Task Scheduler/CIM failure is not an absent task: it fails with the stage.
$case = New-Case 'cim-failure'
try {
  $getCimFailure = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:cim-failure'
  throw 'Task Scheduler service is unavailable'
}
'@
  $exit = Invoke-Case $case $getCimFailure $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a CIM failure exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'a CIM failure writes a FAILED receipt'
  Assert-True ($result.message -match '^update failed during stop \(.+\)$') `
    'a CIM failure reports only the stage and the exception type'
  Assert-True ($result.message -notmatch 'unavailable') `
    'the diagnostic never carries the failing command output'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'a CIM failure still cleans up the one-shot update task'
} finally { Remove-Case $case }

# 23. A failing stop is reported and still cleans up the one-shot task.
$case = New-Case 'stop-failure'
try {
  $stopFails = @'
function Stop-ScheduledTask {
  param([string]$TaskName, [string]$TaskPath, [string]$ErrorAction)
  Add-TestLog "StopScheduledTask:$TaskName"
  throw 'the managed task cannot be stopped'
}
'@
  $exit = Invoke-Case $case $defaultGet $stopFails $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a stop failure exits non-zero'
  Assert-True ($result.message -match '^update failed during stop \(.+\)$') `
    'a stop failure reports only the stage and the exception type'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'a stop failure still cleans up the one-shot update task'
  Assert-True (-not (Test-Path -LiteralPath (Join-Path $case.UpdateDir 'backup-*'))) `
    'a stop failure performs no backup'
} finally { Remove-Case $case }

# 24. A failing backup is reported, never restarts the task and still cleans up.
$case = New-Case 'backup-failure'
try {
  $backupFails = @'
function Move-Item {
  param([string]$LiteralPath, [string]$Destination)
  Add-TestLog "MoveItem:$Destination"
  throw 'the installed distribution cannot be moved'
}
'@
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister $backupFails
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a backup failure exits non-zero'
  Assert-True ($result.message -match '^update failed during backup \(.+\)$') `
    'a backup failure reports only the stage and the exception type'
  Assert-True ((Read-Log $case) -notmatch 'StartScheduledTask') `
    'a backup failure never restarts the managed task'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'a backup failure still cleans up the one-shot update task'
  Assert-True ((Get-Content -LiteralPath (Join-Path $case.Installed 'main.js') -Raw) -eq 'installed-release-main-js') `
    'a backup failure leaves the installed distribution untouched'
} finally { Remove-Case $case }

# 25. A receipt that cannot be written is reported and never skips the one-shot cleanup.
$case = New-Case 'unwritable-receipt' -HandoffState 'missing'
try {
  New-Item -ItemType Directory -Force -Path $case.Result | Out-Null
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $stderr = Read-Stderr $case
  Assert-True ($exit -ne 0) 'an unwritable receipt still exits non-zero'
  Assert-True ($stderr -match 'could not write the failure receipt') `
    'an unwritable failure receipt is reported'
  Assert-True ($stderr -match 'failed: handoff metadata is missing') `
    'the diagnostic is not truncated by the failing receipt'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'cleanup is not skipped by a failing receipt'
} finally { Remove-Case $case }

# 26. Failing to remove the one-shot task is a warning: the update result is unaffected.
$case = New-Case 'unregister-failure'
try {
  $unregisterFails = @'
function Unregister-ScheduledTask {
  param([string]$TaskName, [switch]$Confirm, [string]$ErrorAction)
  Add-TestLog "UnregisterScheduledTask:$TaskName"
  throw 'the one-shot task cannot be removed'
}
'@
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $unregisterFails
  $result = Read-Result $case
  Assert-True ($exit -eq 0) 'a failing cleanup does not change the update result'
  Assert-True ($result.phase -eq 'PREPARED') 'the PREPARED receipt is still written'
  Assert-True ((Read-Log $case) -match 'StartScheduledTask') 'the managed task is still restarted'
  Assert-True ((Read-Stderr $case) -match 'could not unregister its one-shot task') `
    'a failing cleanup is reported as a safe warning'
} finally { Remove-Case $case }

if ($script:failures -gt 0) {
  Write-Host ("Windows update fixture: {0} checks, {1} failures" -f $script:checks, $script:failures)
  exit 1
}
Write-Host ("Windows update fixture: {0} checks, 0 failures" -f $script:checks)
exit 0
