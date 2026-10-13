# Managed update script (`update.ps1`) contracts.
#
# Runs the real update script in a disposable child Windows PowerShell process with the
# scheduled-task API, and the filesystem commands used for the backup/replace steps,
# replaced by in-process stubs. No real scheduled task is ever read, stopped, started or
# unregistered, and no path outside the temporary fixture is touched.
#
# Covered: missing/not-owned/stop/backup/replace/restart failures, a failing failure-receipt
# write, and the guarantee that the one-shot task cleanup is independent of both the update
# outcome and the receipt write.
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
$env:KK_STUDIO_UPDATE_GRACE_SECONDS = '0'

$script:failures = 0

function Assert-True([bool]$Condition, [string]$Message) {
  if ($Condition) {
    Write-Host "ok: $Message"
  } else {
    Write-Host "FAIL: $Message"
    $script:failures++
  }
}

function New-Case([string]$Name) {
  $root = Join-Path ([System.IO.Path]::GetTempPath()) ("kk-update-" + $Name + "-" + [guid]::NewGuid())
  $updateDir = Join-Path $root 'updates/11111111-1111-1111-1111-111111111111'
  $installedDir = Join-Path $root 'lib'
  New-Item -ItemType Directory -Force -Path $updateDir, $installedDir | Out-Null
  $case = [pscustomobject]@{
    Name       = $Name
    Root       = $root
    UpdateDir  = $updateDir
    Staged     = (Join-Path $updateDir 'kk-studio-daemon-v1.0.9.jar')
    Installed  = (Join-Path $installedDir 'kk-studio-daemon.jar')
    Result     = (Join-Path $updateDir 'update-result.json')
    Log        = (Join-Path $root 'calls.txt')
    StderrFile = (Join-Path $root 'stderr.txt')
    StdoutFile = (Join-Path $root 'stdout.txt')
    Runner     = (Join-Path $root 'runner.ps1')
  }
  Set-Content -LiteralPath $case.Staged -Value 'staged-release-jar' -NoNewline
  Set-Content -LiteralPath $case.Installed -Value 'installed-release-jar' -NoNewline
  Set-Content -LiteralPath $case.Log -Value ''
  return $case
}

function Get-RunnerTemplate {
  return @'
$ErrorActionPreference = 'Stop'
$logPath = $env:KK_TEST_LOG
function Add-TestLog([string]$message) { Add-Content -LiteralPath $logPath -Value $message }
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$serviceTask = "kk-studio-environment-daemon-$sid"
$expectedDescription = "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$sid"
__GET__
__STOP__
__START__
__UNREGISTER__
__EXTRA__
. '__SCRIPT__' -StagedJar '__STAGED__' -InstalledJar '__INSTALLED__'
'@
}

function Invoke-Case([object]$Case, [string]$Get, [string]$Stop, [string]$Start,
                      [string]$Unregister, [string]$Extra = '') {
  $template = Get-RunnerTemplate
  $runner = $template.
    Replace('__GET__', $Get).
    Replace('__STOP__', $Stop).
    Replace('__START__', $Start).
    Replace('__UNREGISTER__', $Unregister).
    Replace('__EXTRA__', $Extra).
    Replace('__SCRIPT__', $updateScript).
    Replace('__STAGED__', $Case.Staged).
    Replace('__INSTALLED__', $Case.Installed)
  Set-Content -LiteralPath $Case.Runner -Value $runner -Encoding UTF8
  $env:KK_TEST_LOG = $Case.Log
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

function Remove-Case([object]$Case) {
  # Best effort: a report-failure case deliberately leaves an unwritable result path.
  try {
    Remove-Item -LiteralPath $Case.Root -Recurse -Force -ErrorAction SilentlyContinue
  } catch {
  }
}

$defaultGet = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog "GetScheduledTask:$TaskName"
  [pscustomobject]@{ TaskName = $TaskName; Description = $expectedDescription; State = 'Ready' }
}
'@
$defaultStop = @'
function Stop-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog "StopScheduledTask:$TaskName"
}
'@
$defaultStart = @'
function Start-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog "StartScheduledTask:$TaskName"
}
'@
$defaultUnregister = @'
function Unregister-ScheduledTask {
  param([string]$TaskName, [switch]$Confirm, [string]$ErrorAction)
  Add-TestLog "UnregisterScheduledTask:$TaskName"
}
'@

# 1. Success: back up, replace, restart and clean up the one-shot task.
$case = New-Case 'success'
try {
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  $log = Read-Log $case
  Assert-True ($exit -eq 0) 'success exits 0'
  Assert-True ($result.phase -eq 'PREPARED') 'success writes the PREPARED receipt'
  Assert-True ((Get-Content -LiteralPath $case.Installed -Raw) -eq 'staged-release-jar') `
    'success replaces the installed binary'
  Assert-True ((Get-Content -LiteralPath `
      (Join-Path $case.UpdateDir 'backup/kk-studio-daemon.jar') -Raw) -eq 'installed-release-jar') `
    'success backs up the previous binary'
  Assert-True ($log -match 'StopScheduledTask') 'success stops the managed task'
  Assert-True ($log -match 'StartScheduledTask') 'success restarts the managed task'
  Assert-True ($log -match 'UnregisterScheduledTask') 'success unregisters the one-shot task'
} finally { Remove-Case $case }

# 2. Missing managed task is a fail-closed report and still cleans up the one-shot task.
$case = New-Case 'missing'
try {
  $getMissing = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog "GetScheduledTask:$TaskName"
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

# 3. A task not owned by the installer is rejected without any destructive action.
$case = New-Case 'not-owned'
try {
  $getForeign = @'
function Get-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
  Add-TestLog "GetScheduledTask:$TaskName"
  [pscustomobject]@{ TaskName = $TaskName; Description = 'someone else'; State = 'Ready' }
}
'@
  $exit = Invoke-Case $case $getForeign $defaultStop $defaultStart $defaultUnregister
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'foreign task exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'foreign task writes a FAILED receipt'
  Assert-True ($result.message -eq 'scheduled task is not owned by the installer') `
    'foreign task reports the safe reason'
  Assert-True ((Get-Content -LiteralPath $case.Installed -Raw) -eq 'installed-release-jar') `
    'foreign task leaves the installed binary untouched'
} finally { Remove-Case $case }

# 4. Stop failure is caught by the top-level handler and still cleans up.
$case = New-Case 'stop-fail'
try {
  $stopFails = @'
function Stop-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
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

# 5. Backup failure is caught and reported; the installed binary is untouched.
$case = New-Case 'backup-fail'
try {
  $backupFails = @'
function New-Item {
  param([string]$ItemType, [switch]$Force, [string]$Path)
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

# 6. Replace failure is caught and reported; the installed binary is untouched.
$case = New-Case 'replace-fail'
try {
  $replaceFails = @'
function Move-Item {
  param([switch]$Force, [string]$LiteralPath, [string]$Destination)
  throw 'installed artifact cannot be replaced'
}
'@
  $exit = Invoke-Case $case $defaultGet $defaultStop $defaultStart $defaultUnregister $replaceFails
  $result = Read-Result $case
  Assert-True ($exit -ne 0) 'replace failure exits non-zero'
  Assert-True ($result.phase -eq 'FAILED') 'replace failure writes a FAILED receipt'
  Assert-True ($result.message -like 'update failed during replace*') `
    'replace failure reports the safe stage and exception type'
  Assert-True ((Get-Content -LiteralPath $case.Installed -Raw) -eq 'installed-release-jar') `
    'replace failure leaves the installed binary untouched'
} finally { Remove-Case $case }

# 7. Restart failure is reported and never rolls the replaced binary back.
$case = New-Case 'restart-fail'
try {
  $startFails = @'
function Start-ScheduledTask {
  param([string]$TaskName, [string]$ErrorAction)
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

# 8. A failing failure-receipt write cannot skip the one-shot task cleanup.
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

# 9. A success receipt that cannot be written never turns an applied update into a failure.
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

# 10. A failing one-shot task cleanup never masks a successful update.
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

if ($script:failures -gt 0) {
  Write-Host "$script:failures assertion(s) failed"
  exit 1
}
Write-Host 'all update.ps1 assertions passed'
