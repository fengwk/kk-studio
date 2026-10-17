#Requires -Version 5.1
<#
.SYNOPSIS
Contract tests for scripts/daemon/install.ps1 on Windows PowerShell 5.1 and PowerShell 7.

.DESCRIPTION
The tests never download a real release, never register a real Scheduled Task and never
touch the real user profile. They build a tiny archive from a real node.exe (already on the
runner) plus a stub daemon main.js, stub the network and the ScheduledTasks cmdlets, and
redirect managed paths to a private sandbox so install.ps1 exercises its real ownership/ACL,
SHA256, --check-config preflight and atomic program-publication logic end to end.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$installer = Join-Path $repoRoot "scripts\daemon\install.ps1"
$releaseTag = "v1.0.14"
$daemonVersion = "1.0.14"
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

function Assert-Equal {
    param([AllowEmptyString()][string] $Actual, [AllowEmptyString()][string] $Expected, [Parameter(Mandatory = $true)][string] $Message)
    Assert-True -Condition ($Actual -ceq $Expected) -Message $Message
}

# --- sandbox and fixtures -------------------------------------------------------------------

$sandbox = Join-Path ([IO.Path]::GetTempPath()) ("kk-studio-ps-test-" + [Guid]::NewGuid().ToString("N"))
$httpDir = Join-Path $sandbox "http"
$profileMarker = [string] [char] 0x73AF + [string] [char] 0x5883 + [char]::ConvertFromUtf32(0x1F600)
$profileDir = Join-Path $sandbox ("profile-" + $profileMarker)
New-Item -ItemType Directory -Path $httpDir, $profileDir -Force | Out-Null

$nodeCommand = Get-Command "node.exe" -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -eq $nodeCommand) {
    throw "a real node.exe is required on PATH for the fixture"
}

$stubMain = @'
import fs from "node:fs"
const argv = process.argv.slice(2)
if (argv[0] === "--version") {
  process.stdout.write("kk-studio-daemon 1.0.14\n")
  process.exit(0)
}
if (argv[0] === "--check-config") {
  const path = argv[1]
  if (!path || !fs.existsSync(path)) { process.exit(2) }
  const body = fs.readFileSync(path, "utf8")
  if (body.includes("INVALID") || /"daemon"\s*:/.test(body)) {
    process.stderr.write("Invalid daemon configuration: daemon.daemon: unknown field\n")
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
        [string] $Config = '{"studioUrl":"https://studio.example.com"}',
        [string] $Token = "token-value"
    )
    # Dot-sourcing help resets installer state; resolve the real SID again before input ACLs.
    Initialize-HostContext
    $directory = Join-Path $sandbox ("inputs-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $configPath = Join-Path $directory "daemon.json"
    $tokenPath = Join-Path $directory "daemon.token"
    Set-Content -LiteralPath $configPath -Value $Config -Encoding UTF8
    Set-Content -LiteralPath $tokenPath -Value $Token -Encoding UTF8
    # Real protected ACLs: the installer rejects inherited access, and this fixture must not mock that check.
    $acl = New-PrivateAcl
    Set-Acl -LiteralPath $configPath -AclObject $acl
    Set-Acl -LiteralPath $tokenPath -AclObject $acl
    return @($configPath, $tokenPath)
}

function Import-Installer {
    param([hashtable] $Parameters)
    # install.ps1 disables positional binding, so a string[] splat cannot turn "-ConfigFile"
    # into a named parameter. Callers pass a hashtable of the installer's real parameter names,
    # including Unicode paths. Unknown keys stay unbound so the installer itself rejects them.
    # help defines the installer functions without Invoke-Main and without touching an installation.
    . $installer @Parameters
    $script:InstallerInitializeHostContext = ${function:Initialize-HostContext}
    function Initialize-HostContext {
        & $script:InstallerInitializeHostContext
        # GetFolderPath(UserProfile) ignores USERPROFILE. Keep the real SID from the original
        # initializer, then confine every managed path to this fixture sandbox.
        $script:HomePath = $profileDir
        $script:InstallRoot = Join-Path $profileDir ".kk-studio"
        $script:InstalledLib = Join-Path $script:InstallRoot "lib"
        $script:InstalledNode = Join-Path $script:InstalledLib "runtime\code-server\lib\node.exe"
        $script:InstalledConfig = Join-Path $script:InstallRoot "daemon.json"
        $script:InstalledToken = Join-Path $script:InstallRoot "daemon.token"
    }
}

function Invoke-Installer {
    param([hashtable] $Parameters)
    # Tasks survive across install, status, and uninstall. Dot-sourcing reinstalls the sandbox wrapper.
    # The hashtable is the installer's named-parameter set, not a string[] of flag text.
    . Import-Installer -Parameters $Parameters
    return (Invoke-Main)
}

function Assert-DirectArgumentRoundTrip {
    # Real Node receives the Unicode vector directly. Code points stay in the script as ASCII,
    # so Windows PowerShell 5.1 cannot misread this file through the ANSI code page.
    $probe = @'
import fs from "node:fs"
fs.writeFileSync(process.argv[2], JSON.stringify(process.argv.slice(3)), "utf8")
'@
    $probeDir = Join-Path $sandbox "argv-probe"
    New-Item -ItemType Directory -Path $probeDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $probeDir "package.json") -Value '{"type":"module"}' -Encoding ASCII
    $probePath = Join-Path $probeDir "argv-probe.js"
    $outputPath = Join-Path $probeDir "argv-probe.json"
    Set-Content -LiteralPath $probePath -Value $probe -Encoding ASCII
    $environment = [string] [char] 0x73AF + [string] [char] 0x5883
    $trailing = "C:\" + [string] [char] 0x5C3E + [string] [char] 0x968F + "\"
    $arguments = @(
        $environment,
        "",
        'say "hi"',
        $trailing,
        [char]::ConvertFromUtf32(0x1F600),
        "plain"
    )
    $serialized = ConvertTo-WindowsCommandLine -Arguments (@($outputPath) + $arguments)
    $probeInfo = [Diagnostics.ProcessStartInfo]::new()
    $probeInfo.FileName = $nodeCommand.Source
    $probeInfo.Arguments = '"' + $probePath + '" ' + $serialized
    $probeInfo.UseShellExecute = $false
    $probeInfo.RedirectStandardOutput = $true
    $probeInfo.RedirectStandardError = $true
    $probeProcess = [Diagnostics.Process]::new()
    $probeProcess.StartInfo = $probeInfo
    try {
        [void] $probeProcess.Start()
        if (-not $probeProcess.WaitForExit(15000)) {
            $probeProcess.Kill()
            [void] $probeProcess.WaitForExit(5000)
            throw "direct argument probe timed out"
        }
        Assert-True -Condition ($probeProcess.ExitCode -eq 0) -Message "real node accepts the direct argument vector"
    }
    finally {
        $probeProcess.Dispose()
    }
    $received = Get-Content -LiteralPath $outputPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-True -Condition ($received.Count -eq $arguments.Count) -Message "direct argument count is preserved"
    for ($index = 0; $index -lt $arguments.Count; $index++) {
        Assert-Equal -Actual ([string] $received[$index]) -Expected $arguments[$index] `
            -Message "direct argument $index round-trips through node"
    }
}

try {
    # Load function definitions before the probe or ACL helper; help does not call Invoke-Main.
    # The original initializer resolves the real SID and does not write files; the wrapper then
    # redirects every managed path into the sandbox before any input ACL or installation work.
    . Import-Installer -Parameters @{ Command = "help" }
    Initialize-HostContext
    Assert-True -Condition ($script:CurrentSid -match "^S-") -Message "fixture keeps the real current SID"
    Assert-True -Condition ($script:InstallRoot.StartsWith($profileDir, [StringComparison]::Ordinal)) `
        -Message "managed installation root stays inside the sandbox"
    Assert-DirectArgumentRoundTrip
    Set-Content -LiteralPath (Join-Path $profileDir ".keep") -Value "keep" -Encoding ASCII

    # Pure contract checks that do not touch the filesystem.
    Assert-True -Condition ($installer -and (Test-Path -LiteralPath $installer)) -Message "installer exists"

    $pairs = New-Inputs
    $exit = Invoke-Installer -Parameters @{ Command = "help" }
    Assert-True -Condition ($exit -eq 0) -Message "help exits 0"

    $rejected = $false
    try {
        Invoke-Installer -Parameters @{
            Command = "install"; ConfigFile = $pairs[0]; TokenFile = $pairs[1]; JavaHome = "/opt/jdk"
        } | Out-Null
    }
    catch {
        $rejected = $true
    }
    Assert-True -Condition $rejected -Message "unknown -JavaHome option is rejected"

    # The real daemon.json is the bare config object; a wrapped "daemon" object must fail preflight.
    $wrapped = New-Inputs -Config '{"daemon":{"studioUrl":"https://studio.example.com"}}'
    $wrappedRejected = $false
    try {
        Invoke-Installer -Parameters @{
            Command = "install"; ConfigFile = $wrapped[0]; TokenFile = $wrapped[1]
        } | Out-Null
    }
    catch {
        $wrappedRejected = $true
    }
    Assert-True -Condition $wrappedRejected -Message "wrapped daemon object is rejected by preflight"

    # Full install through stubs.
    $pairs = New-Inputs
    $exit = Invoke-Installer -Parameters @{
        Command = "install"; ConfigFile = $pairs[0]; TokenFile = $pairs[1]
    }
    Assert-True -Condition ($exit -eq 0) -Message "install exits 0"

    $installRoot = Join-Path $profileDir ".kk-studio"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $installRoot "lib\main.js")) -Message "lib main.js published"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $installRoot "lib\runtime\code-server\lib\node.exe")) `
        -Message "frozen node.exe published"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $installRoot "daemon.json")) -Message "config published"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $installRoot "daemon.token")) -Message "token published"
    Assert-True -Condition ($script:Tasks.Count -eq 1) -Message "scheduled task registered"

    $registered = @($script:Tasks.Values)[0]
    $expectedConfig = Join-Path $installRoot "daemon.json"
    $expectedArguments = '"main.js" "--config" "' + $expectedConfig + '"'
    Assert-True -Condition ($registered.Action.Argument -ceq $expectedArguments) `
        -Message "task arguments are the quoted direct argv"
    Assert-Contains -Haystack $registered.Action.Execute -Needle "node.exe" -Message "task runs node.exe"

    # status is read-only and reports Running. It must use the same sandbox wrapper as install.
    $exit = Invoke-Installer -Parameters @{ Command = "status" }
    Assert-True -Condition ($exit -eq 0) -Message "status exits 0 when Running"
    Assert-True -Condition ($script:Tasks.Count -eq 1) -Message "status preserves the registered task"

    # uninstall removes the program and task but keeps config/token.
    $exit = Invoke-Installer -Parameters @{ Command = "uninstall" }
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

# The Windows gate invokes this installer suite for both PowerShell hosts. Run the managed
# update suite from the same gate so the update contract cannot silently fall out of CI.
$hostExe = (Get-Process -Id $PID).Path
$updateSuite = Join-Path $PSScriptRoot "test_daemon_update_windows.ps1"
& $hostExe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $updateSuite
if ($LASTEXITCODE -ne 0) {
    Write-Host "Windows managed-update fixture failed"
    exit 1
}
exit 0
