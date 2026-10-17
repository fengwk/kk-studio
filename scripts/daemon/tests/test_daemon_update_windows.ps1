# Managed update script (`update.ps1`) contracts.
#
# Runs the real update script in a disposable child Windows PowerShell process with the
# scheduled-task API, and the filesystem commands used for the backup/replace steps,
# replaced by in-process stubs. No real scheduled task is ever read, stopped, started or
# unregistered, and no path outside the temporary fixture is touched.
#
# Covered: the handoff/permit gate (a transient PREPARING at job start is never rejected, only
# the matching permit plus a re-read ACCEPTED state may replace anything, a missing/mismatched
# permit fails closed), the exact-root scheduled-task ownership checks (description, the single
# action's executable compared against the handoff's managed runtime, its base64 config
# arguments and its working directory), the Running/Queued stop wait, missing/not-owned/stop/
# backup/replace/restart failures, a failing failure-receipt write, a failing stderr diagnostic,
# and the guarantee that the one-shot task cleanup is independent of the update outcome, the
# receipt write and the diagnostic.
#
# This script needs Windows PowerShell (5.1) or PowerShell 7 on Windows: it reads the real
# WindowsIdentity SID and drives native scheduled-task APIs. It is intentionally not wired
# into `unittest` discovery (which runs on Unix); the main agent owns its CI wiring.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$updateScript = Join-Path $repoRoot `
  'harness/daemon/src/main/resources/fun/fengwk/kkstudio/harness/daemon/update/update.ps1'
if (-not (Test-Path -LiteralPath $updateScript)) {
  throw "update script not found: $updateScript"
}
$hostExe = (Get-Process -Id $PID).Path

$operationId = '11111111-1111-1111-1111-111111111111'
$otherOperationId = '22222222-2222-2222-2222-222222222222'
$javaExecutable = 'C:\Program Files\Eclipse Adoptium\jdk-21-hotspot\bin\java.exe'

$script:failures = 0

function Assert-True([bool]$Condition, [string]$Message) {
  if ($Condition) {
    Write-Host "ok: $Message"
  } else {
    Write-Host "FAIL: $Message"
    $script:failures++
  }
}

function New-Case([string]$Name, [string]$HandoffState = 'ACCEPTED',
                  [string]$HandoffOperation = $operationId, [string]$Permit = 'match') {
  $root = Join-Path ([System.IO.Path]::GetTempPath()) ("kk-update-" + $Name + "-" + [guid]::NewGuid())
  $updateDir = Join-Path $root "updates/$operationId"
  $installedDir = Join-Path $root 'lib'
  New-Item -ItemType Directory -Force -Path $updateDir, $installedDir | Out-Null
  $case = [pscustomobject]@{
    Name       = $Name
    Root       = $root
    UpdateDir  = $updateDir
    Staged     = (Join-Path $updateDir 'kk-studio-daemon-v1.0.9.jar')
    Installed  = (Join-Path $installedDir 'kk-studio-daemon.jar')
    Config     = (Join-Path $root 'daemon.json')
    Handoff    = (Join-Path $updateDir 'handoff.json')
    Permit     = (Join-Path $updateDir 'permit')
    Result     = (Join-Path $updateDir 'update-result.json')
    Log        = (Join-Path $root 'calls.txt')
    StderrFile = (Join-Path $root 'stderr.txt')
    StdoutFile = (Join-Path $root 'stdout.txt')
    Runner     = (Join-Path $root 'runner.ps1')
  }
  [IO.File]::WriteAllText($case.Staged, 'staged-release-jar')
  [IO.File]::WriteAllText($case.Installed, 'installed-release-jar')
  [IO.File]::WriteAllText($case.Config, '{}')
  [IO.File]::WriteAllText($case.Log, '')
  if ($HandoffState -ne 'missing') {
    Write-Handoff -Case $case -State $HandoffState -Operation $HandoffOperation
  }
  if ($Permit -eq 'match') {
    [IO.File]::WriteAllText($case.Permit, $operationId)
  } elseif ($Permit -eq 'wrong') {
    [IO.File]::WriteAllText($case.Permit, $otherOperationId)
  }
  return $case
}

function Write-Handoff([object]$Case, [string]$State, [string]$Operation = $operationId) {
  $handoff = [ordered]@{
    state          = $State
    operationId    = $Operation
    targetVersion  = '1.0.9'
    javaExecutable = $javaExecutable
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
$javaExecutable = '__JAVA__'
$installedDir = '__INSTALLEDDIR__'
$installRoot = '__INSTALLROOT__'
$configPath = '__CONFIG__'
$expectedArguments = '-jar kk-studio-daemon.jar --base64-args ' +
  [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('--config')) + ' ' +
  [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($configPath))
# Stub state that straddles the invocation lives in the global scope: the call operator below
# makes `$script:` inside a stub resolve to the callee's scope instead of this runner's.
$global:stateIndex = 0
function New-Action([string]$Execute, [string]$Arguments, [string]$WorkingDirectory) {
  return [pscustomobject]@{
    Execute          = $Execute
    Arguments        = $Arguments
    WorkingDirectory = $WorkingDirectory
  }
}
function New-Task([string]$Description, [string]$State, $Actions, [string]$TaskPath = '\',
                  [string]$TaskName = $serviceTask) {
  return [pscustomobject]@{
    TaskName    = $TaskName
    TaskPath    = $TaskPath
    Description = $Description
    State       = $State
    Actions     = $Actions
  }
}
function New-ManagedAction {
  return New-Action -Execute $javaExecutable -Arguments $expectedArguments -WorkingDirectory $installedDir
}
__GET__
__STOP__
__START__
__UNREGISTER__
__EXTRA__
# The production script runs in a child scope through the call operator, so its fail-closed
# `exit 1` sets $LASTEXITCODE here; a dot-sourced script cannot propagate it (the host still
# exits 0) and every exit-code assertion would silently pass.
$LASTEXITCODE = 0
& '__SCRIPT__' -StagedJar '__STAGED__' -InstalledJar '__INSTALLED__'
exit $LASTEXITCODE
'@
}

function Invoke-Case([object]$Case, [string]$Get, [string]$Stop, [string]$Start,
                     [string]$Unregister, [string]$Extra = '', [hashtable]$Env = @{}) {
  $template = Get-RunnerTemplate
  $runner = $template.
    Replace('__GET__', $Get).
    Replace('__STOP__', $Stop).
    Replace('__START__', $Start).
    Replace('__UNREGISTER__', $Unregister).
    Replace('__EXTRA__', $Extra).
    Replace('__SCRIPT__', $updateScript).
    Replace('__STAGED__', $Case.Staged).
    Replace('__INSTALLED__', $Case.Installed).
    Replace('__INSTALLEDDIR__', (Split-Path -Parent $Case.Installed)).
    Replace('__INSTALLROOT__', (Split-Path -Parent (Split-Path -Parent $Case.Installed))).
    Replace('__CONFIG__', $Case.Config).
    Replace('__JAVA__', $javaExecutable)
  Set-Content -LiteralPath $Case.Runner -Value $runner -Encoding UTF8
  $env:KK_TEST_LOG = $Case.Log
  $env:KK_TEST_STATE = 'Ready'
  $env:KK_TEST_STATE_SEQUENCE = ''
  # The permit budget only bounds the wait; the failures exercised here never get a permit.
  $env:KK_STUDIO_UPDATE_PERMIT_WAIT_SECONDS = '2'
  foreach ($name in $Env.Keys) {
    Set-Item -Path ("env:" + $name) -Value $Env[$name]
  }
  $process = Start-Process -FilePath $hostExe `
    -ArgumentList @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                    '-File', ('"' + $Case.Runner + '"')) `
    -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $Case.StdoutFile -RedirectStandardError $Case.StderrFile
  return $process.ExitCode
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

function Read-Stderr([object]$Case) {
  if (-not (Test-Path -LiteralPath $Case.StderrFile)) {
    return ''
  }
  return (Get-Content -LiteralPath $Case.StderrFile -Raw)
}

function Read-Backup([object]$Case) {
  $directories = @(Get-ChildItem -LiteralPath $Case.UpdateDir -Directory -Filter 'backup-*')
  if ($directories.Count -ne 1) {
    return @{ Count = $directories.Count; Content = $null }
  }
  $previous = Join-Path $directories[0].FullName 'previous'
  if (-not (Test-Path -LiteralPath $previous -PathType Leaf)) {
    return @{ Count = $directories.Count; Content = $null }
  }
  return @{ Count = $directories.Count; Content = (Get-Content -LiteralPath $previous -Raw) }
}

function Remove-Case([object]$Case) {
  # Best effort: a report-failure case deliberately leaves an unwritable result path.
  try {
    Remove-Item -LiteralPath $Case.Root -Recurse -Force -ErrorAction SilentlyContinue
  } catch {
  }
}

$defaultGet = @'
function Get-ScheduledTask {
  param([string]$ErrorAction)
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
  Add-TestLog "StopScheduledTask:$TaskPath$TaskName"
}
'@
$defaultStart = @'
function Start-ScheduledTask {
  param([string]$TaskName, [string]$TaskPath, [string]$ErrorAction)
  Add-TestLog "StartScheduledTask:$TaskPath$TaskName"
}
'@
$defaultUnregister = @'
function Unregister-ScheduledTask {
  param([string]$TaskName, [switch]$Confirm, [string]$ErrorAction)
  Add-TestLog "UnregisterScheduledTask:$TaskName"
}
'@

# 1. Success: await the permit, re-read ACCEPTED, verify ownership, back up, replace, restart.
$case = New-Case 'success'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  $log = Read-Log $case
  Assert-True ($exit -eq 0) 'success exits 0'
  Assert-True ($result.phase -eq 'PREPARED') 'success writes the PREPARED receipt'
  Assert-True ((Get-Content -LiteralPath $case.Installed -Raw) -eq 'staged-release-jar') `
    'success replaces the installed binary'
  $backup = Read-Backup $case
  Assert-True ($backup.Count -eq 1) 'success leaves exactly one unique backup directory'
  Assert-True ($backup.Content -eq 'installed-release-jar') `
    'success backs up the previous binary into backup-*/previous'
  Assert-True ((Test-Path -LiteralPath $case.Staged) -eq $false) 'success consumes the staged artifact'
  Assert-True ($log -match 'StopScheduledTask:\\kk-studio-environment-daemon-') `
    'success stops the managed task at the exact root path'
  Assert-True ($log -match 'StartScheduledTask') 'success restarts the managed task'
  Assert-True ($log -match 'UnregisterScheduledTask') 'success unregisters the one-shot task'
  Assert-True ((Get-Content -LiteralPath $case.Permit -Raw).Trim() -eq $operationId) `
    'success keeps the permit for this operation'
} finally { Remove-Case $case }

# 2. The stop wait keeps waiting while the task is Running or Queued, then proceeds.
$case = New-Case 'stop-wait'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister `
    -Env @{ KK_TEST_STATE_SEQUENCE = 'Running,Queued,Ready' }
  $log = Read-Log $case
  Assert-True ($exit -eq 0) 'a Running/Queued task eventually stops without failing'
  Assert-True ($log -match 'GetScheduledTask:Queued') `
    'a Queued instance keeps the stop wait alive'
  Assert-True ($log -match 'StartScheduledTask') 'the update proceeds once the task is not queued'
} finally { Remove-Case $case }

# 3. A transient PREPARING at job start is not rejected: the job waits for the permit and only
#    then reports the missing permit, never the (normal) not-yet-ACCEPTED state.
$case = New-Case 'early-preparing' -HandoffState 'PREPARING' -Permit 'absent'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'an unpermitted PREPARING job exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'an unpermitted PREPARING job writes a FAILED receipt'
  Assert-True ($result.message -eq 'the daemon did not release the update permit in time') `
    'a transient PREPARING is never rejected before the permit is awaited'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'a transient PREPARING performs no destructive action'
  Assert-True ((Get-Content -LiteralPath $case.Installed -Raw) -eq 'installed-release-jar') `
    'a transient PREPARING leaves the installed binary untouched'
} finally { Remove-Case $case }

# 4. A PREPARING handoff with a permit present must still be refused (never accepted implicitly).
$case = New-Case 'not-accepted' -HandoffState 'PREPARING'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'an unaccepted handoff exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'an unaccepted handoff writes a FAILED receipt'
  Assert-True ($result.message -eq 'handoff metadata is not accepted') `
    'only ACCEPTED may replace the binary once the permit arrived'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'an unaccepted handoff performs no destructive action'
} finally { Remove-Case $case }

# 5. A FAILED handoff is refused for the same reason.
$case = New-Case 'failed-handoff' -HandoffState 'FAILED'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a failed handoff exits non-zero'
  Assert-True ($result.message -eq 'handoff metadata is not accepted') `
    'a failed handoff is not accepted'
} finally { Remove-Case $case }

# 6. A missing handoff metadata file is refused before the permit is awaited.
$case = New-Case 'missing-handoff' -HandoffState 'missing'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a missing handoff exits non-zero'
  Assert-True ($result.message -eq 'handoff metadata is missing') `
    'a missing handoff reports the safe reason'
} finally { Remove-Case $case }

# 7. A handoff naming another operation is refused.
$case = New-Case 'mismatched-handoff' -HandoffOperation $otherOperationId
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a mismatched handoff exits non-zero'
  Assert-True ($result.message -eq 'handoff metadata does not match the operation') `
    'a mismatched handoff reports the safe reason'
} finally { Remove-Case $case }

# 8. A permit naming another operation is refused.
$case = New-Case 'mismatched-permit' -Permit 'wrong'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a mismatched permit exits non-zero'
  Assert-True ($result.message -eq 'the update permit does not match the operation') `
    'a mismatched permit reports the safe reason'
  Assert-True ((Test-Path -LiteralPath $case.Staged) -eq $true) `
    'a mismatched permit leaves the staged artifact untouched'
} finally { Remove-Case $case }

# 9. The managed task must live at the scheduler root: a same-named task elsewhere is not it.
$case = New-Case 'other-folder'
try {
  $getOtherFolder = @'
function Get-ScheduledTask {
  param([string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:other-folder'
  return New-Task -Description $expectedDescription -State 'Ready' `
    -Actions @(New-ManagedAction) -TaskPath '\OtherFolder\'
}
'@
  $exit = Invoke-Case $case $getOtherFolder $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a task outside the scheduler root exits non-zero'
  Assert-True ($result.message -eq 'managed scheduled task is missing') `
    'only the exact root task path counts as the managed task'
} finally { Remove-Case $case }

# 10. Missing managed task is a fail-closed report and still cleans up the one-shot task.
$case = New-Case 'missing'
try {
  $getMissing = @'
function Get-ScheduledTask {
  param([string]$ErrorAction)
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
    'missing task still cleans up the one-shot task'
} finally { Remove-Case $case }

# 11. A task not owned by the installer is rejected without any destructive action.
$case = New-Case 'not-owned'
try {
  $getForeign = @'
function Get-ScheduledTask {
  param([string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:foreign'
  return New-Task -Description 'someone else' -State 'Ready' -Actions @(New-ManagedAction)
}
'@
  $exit = Invoke-Case $case $getForeign $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'foreign task exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'foreign task writes a FAILED receipt'
  Assert-True ($result.message -eq 'managed scheduled task is not owned by the installer') `
    'foreign task reports the safe reason'
  Assert-True ((Get-Content -LiteralPath $case.Installed -Raw) -eq 'installed-release-jar') `
    'foreign task leaves the installed binary untouched'
  Assert-True ((Read-Log $case) -notmatch 'StopScheduledTask') `
    'foreign task is rejected before anything is stopped'
} finally { Remove-Case $case }

# 12. A task with more than one action is rejected (the managed task has exactly one).
$case = New-Case 'multiple-actions'
try {
  $getTwoActions = @'
function Get-ScheduledTask {
  param([string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:two-actions'
  return New-Task -Description $expectedDescription -State 'Ready' `
    -Actions @((New-ManagedAction), (New-ManagedAction))
}
'@
  $exit = Invoke-Case $case $getTwoActions $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a task with two actions exits non-zero'
  Assert-True ($result.message -eq 'managed scheduled task does not have exactly one action') `
    'only a single-action task is the managed task'
} finally { Remove-Case $case }

# 13. An action running another runtime than the handoff's managed runtime is rejected.
$case = New-Case 'foreign-runtime'
try {
  $getForeignRuntime = @'
function Get-ScheduledTask {
  param([string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:foreign-runtime'
  return New-Task -Description $expectedDescription -State 'Ready' `
    -Actions @((New-Action -Execute 'C:\other\bin\java.exe' -Arguments $expectedArguments `
      -WorkingDirectory $installedDir))
}
'@
  $exit = Invoke-Case $case $getForeignRuntime $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a foreign runtime exits non-zero'
  Assert-True ($result.message -eq 'managed scheduled task does not run the managed runtime') `
    'the action must run the runtime recorded by the handoff'
} finally { Remove-Case $case }

# 14. Arguments that are not the managed base64 config invocation are rejected.
$case = New-Case 'foreign-arguments'
try {
  $getForeignArguments = @'
function Get-ScheduledTask {
  param([string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:foreign-arguments'
  return New-Task -Description $expectedDescription -State 'Ready' `
    -Actions @((New-Action -Execute $javaExecutable -Arguments ($expectedArguments + ' --extra') `
      -WorkingDirectory $installedDir))
}
'@
  $exit = Invoke-Case $case $getForeignArguments $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'foreign arguments exit non-zero'
  Assert-True ($result.message -eq 'managed scheduled task arguments do not match the managed configuration') `
    'arguments must be the managed base64 config invocation'
} finally { Remove-Case $case }

# 15. A working directory outside the managed installation is rejected.
$case = New-Case 'foreign-cwd'
try {
  $getForeignCwd = @'
function Get-ScheduledTask {
  param([string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:foreign-cwd'
  return New-Task -Description $expectedDescription -State 'Ready' `
    -Actions @((New-Action -Execute $javaExecutable -Arguments $expectedArguments `
      -WorkingDirectory 'C:\elsewhere'))
}
'@
  $exit = Invoke-Case $case $getForeignCwd $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'a foreign working directory exits non-zero'
  Assert-True ($result.message -eq 'managed scheduled task working directory does not match the managed installation') `
    'the working directory must be the managed installation'
} finally { Remove-Case $case }

# 16. Stop failure is caught by the top-level handler and still cleans up.
$case = New-Case 'stop-fail'
try {
  $stopFails = @'
function Stop-ScheduledTask {
  param([string]$TaskName, [string]$TaskPath, [string]$ErrorAction)
  Add-TestLog "StopScheduledTask:$TaskName"
  throw 'scheduled task did not stop'
}
'@
  $exit = Invoke-Case $case $defaultGet $stopFails $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'stop failure exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'stop failure writes a FAILED receipt'
  Assert-True ($result.message -like 'update failed during stop*') `
    'stop failure reports the safe stage and exception type'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'stop failure still cleans up the one-shot task'
} finally { Remove-Case $case }

# 17. Backup failure is caught and reported; the installed binary is untouched.
$case = New-Case 'backup-fail'
try {
  $backupFails = @'
function New-Item {
  param([string]$ItemType, [string]$Path)
  throw 'backup directory cannot be created'
}
'@
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister $backupFails
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'backup failure exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'backup failure writes a FAILED receipt'
  Assert-True ($result.message -like 'update failed during backup*') `
    'backup failure reports the safe stage and exception type'
  Assert-True ((Get-Content -LiteralPath $case.Installed -Raw) -eq 'installed-release-jar') `
    'backup failure leaves the installed binary untouched'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'backup failure still cleans up the one-shot task'
} finally { Remove-Case $case }

# 18. Replace failure is reported; the copy-based backup leaves the installed binary in place.
#     The real backup copy runs first, then a FileShare.None handle on the installed jar makes
#     the real [IO.File]::Replace fail: no artificial hook exists in the production script.
$case = New-Case 'replace-fail'
try {
  $replaceBlocked = @'
function Copy-Item {
  param([string]$LiteralPath, [string]$Destination, [switch]$Force)
  Add-TestLog "CopyItem:$LiteralPath"
  Microsoft.PowerShell.Management\Copy-Item -LiteralPath $LiteralPath -Destination $Destination -Force:$Force
  # The locked handle must outlive this stub call, so it is held in the global scope.
  $global:installedLock = [IO.File]::Open($LiteralPath, [IO.FileMode]::Open,
    [IO.FileAccess]::Read, [IO.FileShare]::None)
}
'@
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister $replaceBlocked
  $result = Read-Result $case
  $log = Read-Log $case
  Assert-True ($exit -ne 0) 'replace failure exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'replace failure writes a FAILED receipt'
  Assert-True ($result.message -like 'update failed during replace*') `
    'replace failure reports the safe stage and exception type'
  Assert-True ($log -match 'CopyItem:') 'the real backup copy runs before the replacement'
  Assert-True ((Get-Content -LiteralPath $case.Installed -Raw) -eq 'installed-release-jar') `
    'replace failure leaves the installed binary in place'
  Assert-True ((Test-Path -LiteralPath $case.Staged -PathType Leaf)) `
    'replace failure keeps the staged artifact'
  $backup = Read-Backup $case
  Assert-True ($backup.Content -eq 'installed-release-jar') `
    'replace failure keeps the previous binary in its unique backup'
  Assert-True ($log -notmatch 'StartScheduledTask') `
    'replace failure never restarts the managed task'
} finally { Remove-Case $case }

# 19. Restart failure is reported and never rolls the replaced binary back.
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
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'restart failure exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'restart failure writes a FAILED receipt'
  Assert-True ($result.message -like 'update failed during restart*') `
    'restart failure reports the safe stage and exception type'
  Assert-True ((Get-Content -LiteralPath $case.Installed -Raw) -eq 'staged-release-jar') `
    'restart failure keeps the replaced binary (no automatic rollback)'
} finally { Remove-Case $case }

# 20. A failing failure-receipt write cannot skip the one-shot task cleanup.
$case = New-Case 'report-fail'
try {
  Remove-Item -LiteralPath $case.Staged -Force
  New-Item -ItemType Directory -Path $case.Result | Out-Null
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $stderr = Read-Stderr $case
  Assert-True ($exit -ne 0) 'report failure exits non-zero'
  Assert-True ($stderr -match 'could not write the failure receipt') `
    'report failure leaves a safe diagnostic'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'report failure still cleans up the one-shot task independently'
} finally { Remove-Case $case }

# 21. A success receipt that cannot be written never turns an applied update into a failure.
$case = New-Case 'success-report-fail'
try {
  New-Item -ItemType Directory -Path $case.Result | Out-Null
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  Assert-True ($exit -eq 0) 'unwritable success receipt keeps exit 0'
  Assert-True ((Get-Content -LiteralPath $case.Installed -Raw) -eq 'staged-release-jar') `
    'unwritable success receipt still replaces the installed binary'
  Assert-True ((Read-Stderr $case) -match 'could not write the PREPARED receipt') `
    'unwritable success receipt leaves a safe warning'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'unwritable success receipt still cleans up the one-shot task'
} finally { Remove-Case $case }

# 22. A failing one-shot task cleanup never masks a successful update.
$case = New-Case 'cleanup-fail'
try {
  $unregisterFails = @'
function Unregister-ScheduledTask {
  param([string]$TaskName, [switch]$Confirm, [string]$ErrorAction)
  Add-TestLog "UnregisterScheduledTask:$TaskName"
  throw 'one-shot task cannot be removed'
}
'@
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $unregisterFails
  $result = Read-Result $case
  Assert-True ($exit -eq 0) 'cleanup failure keeps the successful update exit code'
  Assert-True ($result.phase -eq 'PREPARED') 'cleanup failure keeps the PREPARED receipt'
  Assert-True ((Read-Stderr $case) -match 'could not unregister its one-shot task') `
    'cleanup failure leaves a safe diagnostic'
} finally { Remove-Case $case }

# 23. A failing stderr diagnostic cannot skip the one-shot task cleanup.
$case = New-Case 'diagnostic-fail'
try {
  $getMissing = @'
function Get-ScheduledTask {
  param([string]$ErrorAction)
  Add-TestLog 'GetScheduledTask:missing'
  return $null
}
'@
  # Replace Console.Error with a writer that always throws: the real script has no test hook,
  # so this is the only way to drive the "diagnostic itself fails" path.
  $throwingStderr = @'
class ThrowingStderrWriter : System.IO.TextWriter {
  [System.Text.Encoding] get_Encoding() { return [System.Text.Encoding]::UTF8 }
  [void] Write([char]$value) { throw [System.IO.IOException]::new('stderr is unavailable') }
  [void] Write([string]$value) { if ($null -ne $value) { throw [System.IO.IOException]::new('stderr is unavailable') } }
  [void] WriteLine([string]$value) { throw [System.IO.IOException]::new('stderr is unavailable') }
}
[Console]::SetError([ThrowingStderrWriter]::new())
'@
  $exit = Invoke-Case $case $getMissing $defaultStop $defaultStart $defaultUnregister $throwingStderr
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'failing diagnostic exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'failing diagnostic still writes the FAILED receipt'
  Assert-True ($result.message -eq 'managed scheduled task is missing') `
    'failing diagnostic keeps the safe reason'
  Assert-True ((Read-Log $case) -match 'UnregisterScheduledTask') `
    'failing diagnostic still cleans up the one-shot task'
} finally { Remove-Case $case }

if ($script:failures -gt 0) {
  Write-Host "$script:failures assertion(s) failed"
  exit 1
}
Write-Host 'all update.ps1 assertions passed'
