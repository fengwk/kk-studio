# Runs the shipped Windows update script in a disposable Windows PowerShell process.
#
# The ScheduledTasks module is not available on the Linux/macOS CI legs, so this runner replaces
# only the three scheduler cmdlets. Get-ScheduledTask returns the installer-owned task shape;
# Stop/Start append to a log and move the in-memory task state. The production script text still
# runs on a real Windows PowerShell host, and every ownership check stays in that text.
[Console]::Error.WriteLine('[kk-update-test] runner-start')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptPath = [Environment]::GetEnvironmentVariable('KK_TEST_SCRIPT', 'Process')
if ([string]::IsNullOrEmpty($scriptPath) -or -not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw 'KK_TEST_SCRIPT must name the exported update script'
}
$logPath = [Environment]::GetEnvironmentVariable('KK_TEST_LOG', 'Process')
if ([string]::IsNullOrEmpty($logPath)) { throw 'KK_TEST_LOG is required' }
$installedDir = [Environment]::GetEnvironmentVariable('KK_TEST_INSTALLED', 'Process')
if ([string]::IsNullOrEmpty($installedDir)) { throw 'KK_TEST_INSTALLED is required' }
$operationDir = [Environment]::GetEnvironmentVariable('KK_TEST_OPERATION', 'Process')
$stagedDir = [Environment]::GetEnvironmentVariable('KK_TEST_STAGED', 'Process')
$action = [Environment]::GetEnvironmentVariable('KK_TEST_ACTION', 'Process')
$initialState = [Environment]::GetEnvironmentVariable('KK_TEST_STATE', 'Process')
if ([string]::IsNullOrEmpty($initialState)) { $initialState = 'Ready' }
$ownership = [Environment]::GetEnvironmentVariable('KK_TEST_OWNERSHIP', 'Process')

function Add-TestLog([string]$Message) {
    Add-Content -LiteralPath $logPath -Value $Message
}

# Functions and mutable fixture state are global because the production script runs in a child
# script scope; $script: inside a stub would resolve to that callee scope instead of this runner.
$global:fixtureState = $initialState
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$taskName = "kk-studio-environment-daemon-$sid"
$description = if ($ownership -eq 'foreign') {
    'someone else'
} else {
    "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$sid"
}
$expectedNode = Join-Path $installedDir 'runtime\code-server\lib\node.exe'
$configPath = Join-Path (Split-Path -Parent $installedDir) 'daemon.json'
$expectedArguments = '"main.js" "--config" "' + $configPath + '"'

function Get-ScheduledTask {
    param([string]$ErrorAction = 'Stop')
    Add-TestLog ('get:' + $global:fixtureState)
    $taskAction = [pscustomobject]@{
        Execute          = $expectedNode
        Arguments        = $expectedArguments
        WorkingDirectory = $installedDir
    }
    return [pscustomobject]@{
        TaskName    = $taskName
        TaskPath    = '\'
        Description = $description
        State       = $global:fixtureState
        Actions     = @($taskAction)
    }
}

function Stop-ScheduledTask {
    param([string]$TaskName, [string]$TaskPath, [string]$ErrorAction = 'Stop')
    Add-TestLog 'stop'
    $global:fixtureState = 'Ready'
}

function Start-ScheduledTask {
    param([string]$TaskName, [string]$TaskPath, [string]$ErrorAction = 'Stop')
    Add-TestLog 'start'
    $global:fixtureState = 'Running'
}

$global:LASTEXITCODE = 1
[Console]::Error.WriteLine('[kk-update-test] fixture-ready; script-start')
& $scriptPath -Action $action -OperationDir $operationDir -StagedDir $stagedDir -InstalledDir $installedDir
[Console]::Error.WriteLine('[kk-update-test] script-returned')
exit $LASTEXITCODE
