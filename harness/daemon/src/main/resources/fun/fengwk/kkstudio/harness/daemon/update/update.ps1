# Managed by the kk-studio managed Daemon update flow.
#
# Applies one already verified binary update handoff and restarts the managed scheduled
# task. It runs as an independent one-shot scheduled task, so it outlives the old daemon.
# It replaces only the binary; configuration, token and data are untouched and it never
# rolls back automatically. It performs no elevation and does not become a resident engine.
#
# It fails closed: if the managed task definition is missing, not owned by the installer,
# or cannot be stopped, it reports failure and leaves the old binary untouched.
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
$backupDir = Join-Path $updateDir 'backup'
$resultPath = Join-Path $updateDir 'update-result.json'
$updateTask = "kk-studio-daemon-update-$operationId"

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

$failure = $null
try {
  if (-not (Test-Path -LiteralPath $StagedJar)) { Fail-Closed 'staged artifact is missing' }
  if (-not (Test-Path -LiteralPath $InstalledJar)) { Fail-Closed 'installed artifact is missing' }

  # Give the old daemon a moment to flush its PREPARED receipt before the task is stopped.
  $grace = if ($env:KK_STUDIO_UPDATE_GRACE_SECONDS) { [int]$env:KK_STUDIO_UPDATE_GRACE_SECONDS } else { 3 }
  $step = 'stop'
  Start-Sleep -Seconds $grace

  $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
  $serviceTask = "kk-studio-environment-daemon-$sid"
  $expectedDescription = "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$sid"

  $task = Get-ScheduledTask -TaskName $serviceTask -ErrorAction SilentlyContinue
  if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }
  if ([string]$task.Description -ne $expectedDescription) {
    Fail-Closed 'scheduled task is not owned by the installer'
  }

  Stop-ScheduledTask -TaskName $serviceTask
  $waited = 0
  while ((Get-ScheduledTask -TaskName $serviceTask -ErrorAction SilentlyContinue).State -eq 'Running' -and $waited -lt 30) {
    Start-Sleep -Seconds 1
    $waited++
  }
  if ((Get-ScheduledTask -TaskName $serviceTask -ErrorAction SilentlyContinue).State -eq 'Running') {
    Fail-Closed 'managed scheduled task did not stop'
  }

  $step = 'backup'
  New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
  Copy-Item -LiteralPath $InstalledJar -Destination (Join-Path $backupDir (Split-Path -Leaf $InstalledJar)) -Force

  $step = 'replace'
  Move-Item -Force -LiteralPath $StagedJar -Destination $InstalledJar

  $step = 'restart'
  Start-ScheduledTask -TaskName $serviceTask

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
