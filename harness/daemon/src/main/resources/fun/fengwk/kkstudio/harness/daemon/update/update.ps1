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

function Write-UpdateResult([string]$Phase, [string]$Message) {
  $json = @{ operationId = $operationId; phase = $Phase; message = $Message } | ConvertTo-Json -Compress
  [IO.File]::WriteAllText($resultPath, $json)
}

function Fail-Closed([string]$Message) {
  Write-UpdateResult 'FAILED' $Message
  Write-Error "update $operationId failed: $Message"
  Unregister-ScheduledTask -TaskName $updateTask -Confirm:$false -ErrorAction SilentlyContinue
  exit 1
}

if (-not (Test-Path -LiteralPath $StagedJar)) { Fail-Closed 'staged artifact is missing' }
if (-not (Test-Path -LiteralPath $InstalledJar)) { Fail-Closed 'installed artifact is missing' }

# Give the old daemon a moment to flush its PREPARED receipt before the task is stopped.
$grace = if ($env:KK_STUDIO_UPDATE_GRACE_SECONDS) { [int]$env:KK_STUDIO_UPDATE_GRACE_SECONDS } else { 3 }
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

New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
Copy-Item -LiteralPath $InstalledJar -Destination (Join-Path $backupDir (Split-Path -Leaf $InstalledJar)) -Force

Move-Item -Force -LiteralPath $StagedJar -Destination $InstalledJar

Start-ScheduledTask -TaskName $serviceTask

Write-UpdateResult 'PREPARED' 'binary replaced and service restarted'
Unregister-ScheduledTask -TaskName $updateTask -Confirm:$false -ErrorAction SilentlyContinue
