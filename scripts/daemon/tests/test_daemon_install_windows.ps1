#Requires -Version 5.1
<#
.SYNOPSIS
Contract tests for scripts/daemon/install.ps1 on Windows PowerShell 5.1 and PowerShell 7.

.DESCRIPTION
The tests never download a real release, never register a real Scheduled Task and never
touch the real user profile. They build a tiny archive from a real node.exe (already on the
runner) plus a stub daemon main.js, stub the network and the ScheduledTasks cmdlets, and
point USERPROFILE at a private sandbox so install.ps1 exercises its real ownership/ACL,
SHA256, --check-config preflight and atomic program-publication logic end to end.
#>
[CmdletBinding()]
param(
    [switch] $ProcessOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$installer = Join-Path $repoRoot "scripts\daemon\install.ps1"
$releaseTag = "v1.0.12"
$daemonVersion = "1.0.12"
$asset = "kk-studio-daemon-$releaseTag-windows-amd64.tar.gz"
$platform = "windows-amd64"

$script:Failures = 0
$script:Checks = 0

function Assert-True {
    param([Parameter(Mandatory = $true)][bool] $Condition, [Parameter(Mandatory = $true)][string] $Message)
    $script:Checks++
    if (-not $Condition) {
        $script:Failures++
        Write-Host "FAIL: $Message" -ForegroundColor Red
    }
}

function Assert-Contains {
    param([AllowEmptyString()][string] $Haystack, [Parameter(Mandatory = $true)][string] $Needle, [string] $Message)
    Assert-True -Condition ($Haystack -like "*$Needle*") -Message $Message
}

# --- sandbox and fixtures -------------------------------------------------------------------

$sandbox = Join-Path ([IO.Path]::GetTempPath()) ("kk-studio-ps-test-" + [Guid]::NewGuid().ToString("N"))
$httpDir = Join-Path $sandbox "http"
$profileDir = Join-Path $sandbox "profile"
New-Item -ItemType Directory -Path $httpDir, $profileDir -Force | Out-Null

$nodeCommand = Get-Command "node.exe" -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -eq $nodeCommand) {
    throw "a real node.exe is required on PATH for the fixture"
}

$stubMain = @'
const args = process.argv.slice(2)
let argv = args
if (args[0] === "--base64-args") {
  argv = args.slice(1).map((value) => Buffer.from(value, "base64").toString("utf8"))
}
if (argv[0] === "--version") {
  process.stdout.write("kk-studio-daemon 1.0.12\n")
  process.exit(0)
}
if (argv[0] === "--check-config") {
  const fs = require("fs")
  const path = argv[1]
  if (!path || !fs.existsSync(path)) { process.exit(2) }
  const body = fs.readFileSync(path, "utf8")
  if (body.includes("INVALID")) {
    process.stderr.write("Invalid daemon configuration: daemon.studioUrl must be an https URL\n")
    process.exit(2)
  }
  process.stdout.write("Daemon configuration is valid\n")
  process.exit(0)
}
process.exit(3)
'@

$staging = Join-Path $sandbox "staging"
New-Item -ItemType Directory -Path (Join-Path $staging "runtime\code-server\lib") -Force | Out-Null
Set-Content -LiteralPath (Join-Path $staging "main.js") -Value $stubMain -Encoding UTF8
Set-Content -LiteralPath (Join-Path $staging "package.json") `
    -Value ('{"name":"@kk-studio/daemon","version":"' + $daemonVersion + '","type":"module"}') -Encoding UTF8
Copy-Item -LiteralPath $nodeCommand.Source `
    -Destination (Join-Path $staging "runtime\code-server\lib\node.exe") -Force

$archivePath = Join-Path $httpDir $asset
tar.exe -czf $archivePath -C $staging .
if ($LASTEXITCODE -ne 0) { throw "failed to build the fixture archive" }
$digest = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
Set-Content -LiteralPath "$archivePath.sha256" -Value "$digest  $asset`n" -Encoding ASCII -NoNewline

# --- network and ScheduledTasks stubs ------------------------------------------------------

function Invoke-RestMethod {
    param([Parameter(Mandatory = $true)][string] $Uri, $Headers)
    return [pscustomobject] @{ tag_name = $script:FixtureTag }
}

function Invoke-WebRequest {
    param(
        [Parameter(Mandatory = $true)][string] $Uri,
        [switch] $UseBasicParsing,
        [Parameter(Mandatory = $true)][string] $OutFile
    )
    $name = Split-Path -Leaf $Uri
    $source = Join-Path $script:HttpDir $name
    if (-not (Test-Path -LiteralPath $source)) { throw "no fixture for $Uri" }
    Copy-Item -LiteralPath $source -Destination $OutFile -Force
}

$script:Tasks = @{}
$script:VerifyTimeoutSeconds = 1
$script:VerifyStableSeconds = 0

function New-ScheduledTaskAction {
    param([string] $Execute, [string] $Argument, [string] $WorkingDirectory)
    return [pscustomobject] @{ Execute = $Execute; Argument = $Argument; WorkingDirectory = $WorkingDirectory }
}

function New-ScheduledTaskTrigger { param([switch] $AtLogOn, [string] $User) return "trigger" }
function New-ScheduledTaskPrincipal { param([string] $UserId, $LogonType, $RunLevel) return "principal" }
function New-ScheduledTaskSettingsSet {
    param($ExecutionTimeLimit, [switch] $AllowStartIfOnBatteries, [switch] $DontStopIfGoingOnBatteries,
        $MultipleInstances, [int] $RestartCount, $RestartInterval)
    return "settings"
}

function New-ScheduledTask {
    param($Action, $Trigger, $Principal, $Settings, [string] $Description)
    return [pscustomobject] @{ TaskPath = "\"; TaskName = $script:TaskName; State = "Ready"; Description = $Description; Action = $Action }
}

function Register-ScheduledTask {
    param([string] $TaskName, [string] $TaskPath = "\", $InputObject, [switch] $Force)
    $script:Tasks[$TaskName] = $InputObject
}

function Get-ScheduledTask {
    param($TaskName, $TaskPath)
    if ($TaskName) { return @($script:Tasks[$TaskName]) }
    return @($script:Tasks.Values)
}

function Get-ScheduledTaskInfo { param([string] $TaskName, [string] $TaskPath = "\")
    return [pscustomobject] @{ LastRunTime = Get-Date; LastTaskResult = 0; NextRunTime = Get-Date }
}

function Export-ScheduledTask { param([string] $TaskName, [string] $TaskPath = "\") return "<Task />" }

function Start-ScheduledTask { param([string] $TaskName, [string] $TaskPath = "\")
    if ($script:Tasks.ContainsKey($TaskName)) { $script:Tasks[$TaskName].State = "Running" }
}

function Stop-ScheduledTask { param([string] $TaskName, [string] $TaskPath = "\")
    if ($script:Tasks.ContainsKey($TaskName)) { $script:Tasks[$TaskName].State = "Ready" }
}

function Unregister-ScheduledTask { param([string] $TaskName, [string] $TaskPath = "\", [switch] $Confirm)
    $script:Tasks.Remove($TaskName) | Out-Null
}

# --- harness -------------------------------------------------------------------------------

$script:FixtureTag = $releaseTag
$script:HttpDir = $httpDir
$originalProfile = $env:USERPROFILE
$env:USERPROFILE = $profileDir

function New-Inputs {
    param(
        [string] $Config = '{"daemon":{"studioUrl":"https://studio.example.com"}}',
        [string] $Token = "token-value"
    )
    $directory = Join-Path $sandbox ("inputs-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $configPath = Join-Path $directory "daemon.json"
    $tokenPath = Join-Path $directory "daemon.token"
    Set-Content -LiteralPath $configPath -Value $Config -Encoding UTF8
    Set-Content -LiteralPath $tokenPath -Value $Token -Encoding UTF8
    return @($configPath, $tokenPath)
}

function Invoke-Installer {
    param([string[]] $Arguments)
    $script:Tasks = @{}
    . $installer @Arguments
    return (Invoke-Main)
}

try {
    Set-Content -LiteralPath (Join-Path $profileDir ".keep") -Value "keep" -Encoding ASCII

    # Pure contract checks that do not touch the filesystem.
    Assert-True -Condition ($installer -and (Test-Path -LiteralPath $installer)) -Message "installer exists"

    $pairs = New-Inputs
    $exit = Invoke-Installer -Arguments @("help")
    Assert-True -Condition ($exit -eq 0) -Message "help exits 0"

    $rejected = $false
    try {
        Invoke-Installer -Arguments @("install", "-ConfigFile", $pairs[0], "-TokenFile", $pairs[1], "-JavaHome", "/opt/jdk") | Out-Null
    }
    catch {
        $rejected = $true
    }
    Assert-True -Condition $rejected -Message "unknown -JavaHome option is rejected"

    # Full install through stubs.
    $pairs = New-Inputs
    $exit = Invoke-Installer -Arguments @("install", "-ConfigFile", $pairs[0], "-TokenFile", $pairs[1])
    Assert-True -Condition ($exit -eq 0) -Message "install exits 0"

    $installRoot = Join-Path $profileDir ".kk-studio"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $installRoot "lib\main.js")) -Message "lib main.js published"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $installRoot "lib\runtime\code-server\lib\node.exe")) `
        -Message "frozen node.exe published"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $installRoot "daemon.json")) -Message "config published"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $installRoot "daemon.token")) -Message "token published"
    Assert-True -Condition ($script:Tasks.Count -eq 1) -Message "scheduled task registered"

    $registered = @($script:Tasks.Values)[0]
    Assert-Contains -Haystack $registered.Action.Argument -Needle "--base64-args" -Message "task passes base64 args"
    Assert-Contains -Haystack $registered.Action.Execute -Needle "node.exe" -Message "task runs node.exe"

    # status is read-only and reports Running.
    . $installer status | Out-Null
    $exit = Invoke-Main
    Assert-True -Condition ($exit -eq 0) -Message "status exits 0 when Running"

    # uninstall removes the program and task but keeps config/token.
    $exit = Invoke-Installer -Arguments @("uninstall")
    Assert-True -Condition ($exit -eq 0) -Message "uninstall exits 0"
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $installRoot "lib"))) -Message "lib removed"
    Assert-True -Condition ($script:Tasks.Count -eq 0) -Message "task removed"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $installRoot "daemon.json")) -Message "config preserved"
}
finally {
    $env:USERPROFILE = $originalProfile
    Remove-Item -LiteralPath $sandbox -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ("Windows installer fixture: {0} checks, {1} failures" -f $script:Checks, $script:Failures)
if ($script:Failures -gt 0) { exit 1 }
exit 0
