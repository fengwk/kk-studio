# Managed by the kk-studio managed Daemon update flow.
#
# Applies one already verified binary update handoff and restarts the managed scheduled
# task. It runs as an independent one-shot scheduled task, so it outlives the old daemon.
# It replaces only the binary; configuration, token and data are untouched and it never
# rolls back automatically. It performs no elevation and does not become a resident engine.
#
# It fails closed: the handoff metadata must name this operation, the private permit must
# appear and name this operation, and only after that is the state required to be ACCEPTED;
# the managed task must be owned by the installer (description plus the unique action's
# executable, arguments and working directory) before anything is stopped. The independent
# one-shot task starts while the daemon is still persisting PREPARING, so an early PREPARING
# is normal and is never rejected as a mismatch.
#
# Failure handling is uniform: every step that can fail runs under one top-level
# try/catch/finally with a single fail-closed entry point. Reporting and cleanup are
# independent (cleanup sits in its own finally), so a failing receipt or diagnostic can
# never skip it, and `Write-Error` (script-terminating under `$ErrorActionPreference =
# 'Stop'`) is avoided in favour of stderr. The success receipt is best effort.
#
# Usage: kk-studio-daemon-update.ps1 -StagedJar <path> -InstalledJar <path>
param(
  [Parameter(Mandatory = $true)][string]$StagedJar,
  [Parameter(Mandatory = $true)][string]$InstalledJar
)

$ErrorActionPreference = 'Stop'
$updateDir = Split-Path -Parent $StagedJar
$operationId = Split-Path -Leaf $updateDir
$handoffPath = Join-Path $updateDir 'handoff.json'
$permitPath = Join-Path $updateDir 'permit'
$resultPath = Join-Path $updateDir 'update-result.json'
$updateTask = "kk-studio-daemon-update-$operationId"
# The permit budget only bounds how long an already accepted update may wait; it is not a
# timing guess. The daemon releases the permit after the PREPARED receipt is frozen.
$permitWaitSeconds = if ($env:KK_STUDIO_UPDATE_PERMIT_WAIT_SECONDS) { [int]$env:KK_STUDIO_UPDATE_PERMIT_WAIT_SECONDS } else { 120 }
$stopWaitSeconds = 30
# The managed daemon task lives at the scheduler root; an exact TaskPath match keeps a
# same-named task in another folder from being mistaken for it.
$taskPath = '\'

# Current phase, used only to turn an unexpected exception into a safe diagnostic that
# names the stage and the exception type but never the failing command output or paths.
$step = 'preflight'
# Message of an explicit, already de-sensitised fail-closed decision; empty means the
# failure came from an unexpected exception.
$failClosedMessage = ''

function Write-UpdateResult([string]$Phase, [string]$Message) {
  $json = @{ operationId = $operationId; phase = $Phase; message = $Message } | ConvertTo-Json -Compress
  [IO.File]::WriteAllText($resultPath, $json)
}

# Records an explicit, de-sensitised failure and aborts the remaining steps. Throwing
# (rather than Write-Error/exit) keeps a single failure entry point and always runs the
# finally block that reports and cleans up.
function Fail-Closed([string]$Message) {
  $script:failClosedMessage = $Message
  throw [System.InvalidOperationException]::new($Message)
}

# Diagnostic output goes straight to stderr: under `$ErrorActionPreference = 'Stop'`,
# Write-Error is a script-terminating error and would skip the failure receipt and cleanup.
function Write-FailureDiagnostic([string]$Message) {
  [Console]::Error.WriteLine("update ${operationId} failed: $Message")
}

# Best-effort reporting and cleanup stay visible without changing the update outcome.
function Write-UpdateWarning([string]$Message) {
  [Console]::Error.WriteLine("update ${operationId} warning: $Message")
}

# A handoff/permit must be an existing regular non-reparse-point (non-symlink) file.
function Test-ManagedFile([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
  $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
  if ($null -eq $item) { return $false }
  return ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0
}

# Compares two absolute paths after normalisation, tolerating Windows case-insensitivity.
function Test-SamePath([string]$Left, [string]$Right) {
  if ([string]::IsNullOrEmpty($Left) -or [string]::IsNullOrEmpty($Right)) { return $false }
  return [IO.Path]::GetFullPath($Left) -eq [IO.Path]::GetFullPath($Right)
}

# Enumerates the scheduler root instead of filtering by name: a name-filtered Get-ScheduledTask
# reports an absent task as an error, which is indistinguishable from a genuine Task Scheduler
# /CIM failure. Enumerating keeps absence as $null while real failures still terminate.
function Find-ManagedTask([string]$TaskName) {
  return Get-ScheduledTask -ErrorAction Stop |
    Where-Object { $_.TaskPath -eq $taskPath -and $_.TaskName -eq $TaskName } |
    Select-Object -First 1
}

function Read-Handoff {
  return Get-Content -LiteralPath $handoffPath -Raw -Encoding utf8 | ConvertFrom-Json -ErrorAction Stop
}

$failure = $null
try {
  if (-not (Test-Path -LiteralPath $StagedJar -PathType Leaf)) { Fail-Closed 'staged artifact is missing' }
  if (-not (Test-Path -LiteralPath $InstalledJar -PathType Leaf)) { Fail-Closed 'installed artifact is missing' }

  # Handoff metadata must exist, be a regular file and name this operation. Its state is
  # deliberately NOT required here: the one-shot task starts while the daemon is still
  # persisting PREPARING, so an early PREPARING is normal and must not be rejected.
  $step = 'handoff'
  if (-not (Test-ManagedFile -Path $handoffPath)) { Fail-Closed 'handoff metadata is missing' }
  $handoff = Read-Handoff
  if ([string]$handoff.operationId -ne $operationId) { Fail-Closed 'handoff metadata does not match the operation' }

  # The private permit gates the replacement. It is released only after the daemon froze the
  # PREPARED receipt and must name this operation; missing or mismatched means zero replace.
  $step = 'permit'
  $permitDeadline = (Get-Date).AddSeconds($permitWaitSeconds)
  while (-not (Test-Path -LiteralPath $permitPath -PathType Leaf)) {
    if ((Get-Date) -gt $permitDeadline) { Fail-Closed 'the daemon did not release the update permit in time' }
    Start-Sleep -Seconds 1
  }
  if (-not (Test-ManagedFile -Path $permitPath)) { Fail-Closed 'the update permit is not a regular file' }
  if ((Get-Content -LiteralPath $permitPath -Raw).Trim() -ne $operationId) { Fail-Closed 'the update permit does not match the operation' }

  # The permit is released only after ACCEPTED is durably frozen, so re-read the transitioned
  # metadata and require ACCEPTED before anything is stopped.
  $handoff = Read-Handoff
  if ([string]$handoff.operationId -ne $operationId) { Fail-Closed 'handoff metadata does not match the operation' }
  if ([string]$handoff.state -ne 'ACCEPTED') { Fail-Closed 'handoff metadata is not accepted' }
  if ([string]::IsNullOrEmpty([string]$handoff.javaExecutable)) { Fail-Closed 'handoff metadata does not record the managed runtime' }

  # Task ownership: description plus the unique action's executable, arguments and working
  # directory must match the installer's managed daemon task before anything is stopped.
  $step = 'stop'
  $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
  $taskName = "kk-studio-environment-daemon-$sid"
  $taskDescription = "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$sid"
  $installedDir = Split-Path -Parent $InstalledJar
  $installRoot = Split-Path -Parent $installedDir
  $configPath = Join-Path $installRoot 'daemon.json'
  $expectedArguments = '-jar kk-studio-daemon.jar --base64-args ' +
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('--config')) + ' ' +
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($configPath))

  $task = Find-ManagedTask $taskName
  if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }
  if ([string]$task.Description -ne $taskDescription) { Fail-Closed 'managed scheduled task is not owned by the installer' }
  $actions = @($task.Actions)
  if ($actions.Count -ne 1) { Fail-Closed 'managed scheduled task does not have exactly one action' }
  $action = $actions[0]
  if (-not (Test-SamePath -Left ([string]$action.Execute) -Right ([string]$handoff.javaExecutable))) {
    Fail-Closed 'managed scheduled task does not run the managed runtime'
  }
  if ([string]$action.Arguments -ne $expectedArguments) {
    Fail-Closed 'managed scheduled task arguments do not match the managed configuration'
  }
  if (-not (Test-SamePath -Left ([string]$action.WorkingDirectory) -Right $installedDir)) {
    Fail-Closed 'managed scheduled task working directory does not match the managed installation'
  }

  Stop-ScheduledTask -TaskName $taskName -TaskPath $taskPath
  $stopDeadline = (Get-Date).AddSeconds($stopWaitSeconds)
  while ($true) {
    $current = Find-ManagedTask $taskName
    # Both Running and Queued keep the task alive: a queued instance may still start, so the
    # binary is not replaced until the task is neither running nor queued.
    if ($null -eq $current -or [string]$current.State -notin @('Running', 'Queued')) { break }
    if ((Get-Date) -gt $stopDeadline) { Fail-Closed 'managed scheduled task did not stop' }
    Start-Sleep -Seconds 1
  }

  # Unique backup directory: created without -Force so a generated name is never reused and a
  # previous backup is never overwritten. The installed artifact is copied, keeping it in place
  # for the replacement step. There is no automatic rollback; on failure recovery is manual.
  $step = 'backup'
  $backupDir = Join-Path $updateDir ('backup-' + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $backupDir | Out-Null
  Copy-Item -LiteralPath $InstalledJar -Destination (Join-Path $backupDir 'previous')

  # The staged jar atomically replaces the installed jar on the same volume (both live under
  # the managed data directory): the installed jar is never deleted or moved aside first and
  # there is no non-atomic fallback, so a failure leaves it in place.
  #
  # The third argument is the optional backup file name. A `$null` literal binds to the empty
  # string for the [string] parameter, which File.Replace rejects, so a genuine null is passed.
  $step = 'replace'
  [IO.File]::Replace($StagedJar, $InstalledJar, [NullString]::Value)

  $step = 'restart'
  Start-ScheduledTask -TaskName $taskName -TaskPath $taskPath

  # The success receipt is best effort: the binary is already replaced and the service
  # restarted, so a receipt that cannot be written is reported but never turned into a
  # false update failure.
  try {
    Write-UpdateResult 'PREPARED' 'binary replaced and service restarted'
  } catch {
    Write-UpdateWarning "could not write the PREPARED receipt ($($_.Exception.GetType().Name))"
  }
} catch {
  if ($failClosedMessage) {
    $failure = $failClosedMessage
  } else {
    # Unexpected exception: report only the safe stage and exception type.
    $failure = "update failed during ${step} ($($_.Exception.GetType().Name))"
  }
} finally {
  # Reporting and cleanup are independent: the diagnostic can itself throw, so the one-shot
  # task removal lives in its own finally and is never skipped by a failing report.
  try {
    if ($null -ne $failure) {
      try {
        Write-UpdateResult 'FAILED' $failure
      } catch {
        Write-UpdateWarning "could not write the failure receipt ($($_.Exception.GetType().Name))"
      }
      Write-FailureDiagnostic $failure
    }
  } finally {
    try {
      Unregister-ScheduledTask -TaskName $updateTask -Confirm:$false -ErrorAction SilentlyContinue
    } catch {
      Write-UpdateWarning "could not unregister its one-shot task ($($_.Exception.GetType().Name))"
    }
  }
}

if ($null -ne $failure) { exit 1 }
