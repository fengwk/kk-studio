#Requires -Version 5.1
<#
.SYNOPSIS
Contract tests for scripts/daemon/install.ps1 on Windows PowerShell 5.1 and PowerShell 7.

.DESCRIPTION
The tests never download a real release, never register a real Scheduled Task and never
touch the real user profile. They build tiny archives from a real node.exe (already on the
runner) plus a stub daemon main.js, a behaviourally faithful lock-helper stand-in (atomic
directory marker, holds stdin until release), stub network and ScheduledTasks cmdlets, and
redirect managed paths to a private sandbox so install.ps1 exercises its real ownership/ACL,
SHA256, JSON envelope, --check-config preflight and per-card publication logic end to end.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$installer = Join-Path $repoRoot "scripts\daemon\install.ps1"
$daemonVersion = "1.0.14"
$nextVersion = "1.0.15"
$environmentId = "11111111-1111-4111-8111-111111111111"
$secondEnvironmentId = "22222222-2222-4222-8222-222222222222"

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
$lockLog = Join-Path $sandbox "lock.log"
$profileMarker = [string] [char] 0x73AF + [string] [char] 0x5883 + [char]::ConvertFromUtf32(0x1F600)
$profileDir = Join-Path $sandbox ("profile-" + $profileMarker)
New-Item -ItemType Directory -Path $httpDir, $profileDir -Force | Out-Null

$nodeCommand = Get-Command "node.exe" -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -eq $nodeCommand) {
    throw "a real node.exe is required on PATH for the fixture"
}

$fakeHelper = @'
import fs from "node:fs"
import path from "node:path"

const [mode, directory, seconds] = process.argv.slice(2)
const log = process.env.KK_TEST_LOCK_LOG
const note = (value) => { if (log) { fs.appendFileSync(log, value + "\n") } }
fs.mkdirSync(directory, { recursive: true })
const marker = path.join(directory, ".fake-install-lock")
if (mode === "hold") {
  try { fs.mkdirSync(marker) } catch {
    process.stderr.write("install operation lock is busy\n")
    process.exit(75)
  }
  note("hold-start")
  process.stdout.write("locked\n")
  process.stdin.resume()
  process.stdin.on("end", () => {
    try { fs.rmdirSync(marker) } catch {}
    note("hold-end")
    process.exit(0)
  })
} else if (mode === "wait") {
  note("wait-runtime-lock")
  if (process.env.KK_TEST_DAEMON_LOCKED === "1") {
    process.stderr.write("daemon runtime lock wait timed out\n")
    process.exit(76)
  }
  process.exit(0)
} else {
  process.exit(2)
}
'@

function New-FixtureArchive {
    param([Parameter(Mandatory = $true)][string] $Version)
    $assets = "kk-studio-daemon-v$Version-windows-amd64.tar.gz"
    $staging = Join-Path $sandbox ("staging-" + $Version)
    New-Item -ItemType Directory -Path (Join-Path $staging "runtime\code-server\lib") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $staging "runtime\code-server\out\node") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $staging "native") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $staging "node_modules\koffi") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $staging "node_modules\@koromix\koffi-win32-x64") -Force | Out-Null
    $stubMain = @"
import fs from "node:fs"
const argv = process.argv.slice(2)
if (argv[0] === "--version") {
  process.stdout.write("kk-studio-daemon $Version\n")
  process.exit(0)
}
if (argv[0] === "--check-config") {
  const value = argv[1]
  if (!value || !fs.existsSync(value)) { process.exit(2) }
  const body = fs.readFileSync(value, "utf8")
  if (body.includes("INVALID") || /"daemon"\s*:/.test(body)) {
    process.stderr.write("Invalid daemon configuration: daemon.daemon: unknown field\n")
    process.exit(2)
  }
  process.stdout.write("Daemon configuration is valid\n")
  process.exit(0)
}
process.exit(3)
"@
    Set-Content -LiteralPath (Join-Path $staging "main.js") -Value $stubMain -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $staging "package.json") `
        -Value ('{"name":"@kk-studio/daemon","version":"' + $Version + '","type":"module"}') -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $staging "delay.js") -Value "export {}" -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $staging "native\lock-helper.js") -Value $fakeHelper -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $staging "node_modules\koffi\index.js") `
        -Value "module.exports = {}" -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $staging "node_modules\@koromix\koffi-win32-x64\index.js") `
        -Value "module.exports = {}" -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $staging "runtime\code-server\out\node\entry.js") `
        -Value "// frozen entry" -Encoding ASCII
    Copy-Item -LiteralPath $nodeCommand.Source `
        -Destination (Join-Path $staging "runtime\code-server\lib\node.exe") -Force
    $archivePath = Join-Path $httpDir $assets
    tar.exe -czf $archivePath -C $staging .
    if ($LASTEXITCODE -ne 0) { throw "failed to build the fixture archive" }
    $digest = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
    Set-Content -LiteralPath "$archivePath.sha256" -Value "$digest  $assets`n" -Encoding ASCII -NoNewline
}

New-FixtureArchive -Version $daemonVersion
New-FixtureArchive -Version $nextVersion

# --- network and ScheduledTasks stubs ------------------------------------------------------

function Invoke-WebRequest {
    param(
        [Parameter(Mandatory = $true)][string] $Uri,
        [switch] $UseBasicParsing,
        [Parameter(Mandatory = $true)][string] $OutFile,
        $Proxy,
        [switch] $NoProxy
    )
    $script:NetworkCalls += $Uri
    $script:NetworkProxies += $Proxy
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

$script:HttpDir = $httpDir
$script:NetworkCalls = @()
$script:NetworkProxies = @()
$originalProfile = $env:USERPROFILE
$originalLockLog = $env:KK_TEST_LOCK_LOG
$originalLocked = $env:KK_TEST_DAEMON_LOCKED
$env:USERPROFILE = $profileDir
$env:KK_TEST_LOCK_LOG = $lockLog
$env:KK_TEST_DAEMON_LOCKED = ""

function New-Inputs {
    param(
        [string] $Environment = $environmentId,
        [string] $Config,
        [string] $Token = "token-value"
    )
    $directory = Join-Path $sandbox ("inputs-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    if ([string]::IsNullOrEmpty($Config)) {
        $Config = '{"environmentId":"' + $Environment + '","version":"0","daemonConfig":{"studioUrl":"https://studio.example.com"}}'
    }
    $configPath = Join-Path $directory "daemon.json"
    $tokenPath = Join-Path $directory "daemon.token"
    # The real daemon.json is UTF-8 without BOM; the JSON envelope parser must see plain bytes.
    Set-Content -LiteralPath $configPath -Value $Config -Encoding ASCII
    Set-Content -LiteralPath $tokenPath -Value $Token -Encoding ASCII
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
        # GetFolderPath(UserProfile) ignores USERPROFILE. Keep the real SID and the UUID-derived
        # task name, then confine every managed path to this fixture sandbox.
        $script:HomePath = $profileDir
        $script:InstallRoot = Join-Path $profileDir ".kk-studio"
        $script:EnvironmentsRoot = Join-Path $script:InstallRoot "environments"
        $script:EnvironmentRoot = Join-Path $script:EnvironmentsRoot $script:TargetEnvironmentId
        $script:InstalledLib = Join-Path $script:EnvironmentRoot "lib"
        $script:InstalledNode = Join-Path $script:InstalledLib "runtime\code-server\lib\node.exe"
        $script:InstalledConfig = Join-Path $script:EnvironmentRoot "daemon.json"
        $script:InstalledToken = Join-Path $script:EnvironmentRoot "daemon.token"
        $script:WorkDirectory = Join-Path $script:EnvironmentRoot ".work"
        $script:LastOperation = Join-Path $script:EnvironmentRoot "last-operation.json"
    }
}

function Invoke-Installer {
    param([hashtable] $Parameters)
    # Tasks survive across install, status, and uninstall. Dot-sourcing reinstalls the sandbox wrapper.
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

function Get-CurrentTaskName {
    return "kk-studio-daemon-$($script:CurrentSid)-$environmentId"
}

try {
    # Load function definitions before the probe or ACL helper; help does not call Invoke-Main.
    . Import-Installer -Parameters @{ Command = "help"; EnvironmentId = $environmentId }
    Initialize-HostContext
    Assert-True -Condition ($script:CurrentSid -match "^S-") -Message "fixture keeps the real current SID"
    Assert-True -Condition ($script:InstallRoot.StartsWith($profileDir, [StringComparison]::Ordinal)) `
        -Message "managed installation root stays inside the sandbox"
    Assert-DirectArgumentRoundTrip
    Set-Content -LiteralPath (Join-Path $profileDir ".keep") -Value "keep" -Encoding ASCII

    $pairs = New-Inputs
    $exit = Invoke-Installer -Parameters @{ Command = "help" }
    Assert-True -Condition ($exit -eq 0) -Message "help exits 0"

    # Unknown parameters are rejected by the real binder before any work.
    $rejected = $false
    try {
        Invoke-Installer -Parameters @{
            Command = "install"; EnvironmentId = $environmentId; Version = $daemonVersion
            ConfigFile = $pairs[0]; TokenFile = $pairs[1]; UnknownOption = "unexpected"
        } | Out-Null
    }
    catch {
        $rejected = $true
    }
    Assert-True -Condition $rejected -Message "unknown parameter is rejected"

    # The old flat daemon.json must fail closed before any network access.
    $flat = New-Inputs -Config '{"studioUrl":"https://studio.example.com"}'
    $script:NetworkCalls = @()
    $flatRejected = $false
    try {
        Invoke-Installer -Parameters @{
            Command = "install"; EnvironmentId = $environmentId; Version = $daemonVersion
            ConfigFile = $flat[0]; TokenFile = $flat[1]
        } | Out-Null
    }
    catch {
        $flatRejected = $true
    }
    Assert-True -Condition ($flatRejected -and $script:NetworkCalls.Count -eq 0) -Message "flat config rejected before network"

    # A config that belongs to another card is rejected after download and before stopping.
    $foreign = New-Inputs -Environment $secondEnvironmentId
    $foreignRejected = $false
    try {
        Invoke-Installer -Parameters @{
            Command = "install"; EnvironmentId = $environmentId; Version = $daemonVersion
            ConfigFile = $foreign[0]; TokenFile = $foreign[1]
        } | Out-Null
    }
    catch {
        $foreignRejected = $true
    }
    Assert-True -Condition $foreignRejected -Message "another card configuration is rejected"

    # Invalid daemonConfig is rejected by the release preflight.
    $invalid = New-Inputs -Config ('{"environmentId":"' + $environmentId +
        '","version":"0","daemonConfig":{"invalid":true,"studioUrl":"https://studio.example.com"}}')
    $invalidRejected = $false
    try {
        Invoke-Installer -Parameters @{
            Command = "install"; EnvironmentId = $environmentId; Version = $daemonVersion
            ConfigFile = $invalid[0]; TokenFile = $invalid[1]
        } | Out-Null
    }
    catch {
        $invalidRejected = $true
    }
    Assert-True -Condition $invalidRejected -Message "invalid daemonConfig rejected by preflight"

    # Full install into the UUID-scoped layout. An online update supplies the operation id that
    # the fixed last-operation.json must carry.
    $updateOperationId = "9f1c1c5e-2f4b-4b6a-8f2e-3c4d5e6f7a8b"
    $env:KK_STUDIO_UPDATE_OPERATION_ID = $updateOperationId
    $pairs = New-Inputs
    $script:NetworkCalls = @()
    $exit = Invoke-Installer -Parameters @{
        Command = "install"; EnvironmentId = $environmentId; Version = $daemonVersion
        ConfigFile = $pairs[0]; TokenFile = $pairs[1]
    }
    Assert-True -Condition ($exit -eq 0) -Message "install exits 0"
    $envRoot = Join-Path (Join-Path (Join-Path $profileDir ".kk-studio") "environments") $environmentId
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $envRoot "lib\main.js")) -Message "lib main.js published"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $envRoot "lib\runtime\code-server\lib\node.exe")) `
        -Message "frozen node.exe published"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $envRoot "daemon.json")) -Message "config published"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $envRoot "daemon.token")) -Message "token published"
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $envRoot ".work"))) -Message "work staging cleaned"
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $profileDir ".kk-studio\backups"))) -Message "no backups directory"
    Assert-True -Condition ($script:Tasks.Count -eq 1) -Message "scheduled task registered"
    $taskName = Get-CurrentTaskName
    Assert-True -Condition ($script:Tasks.ContainsKey($taskName)) -Message "task name contains SID and UUID"
    $registered = $script:Tasks[$taskName]
    $expectedDescription = "Managed by scripts/daemon/install.ps1; ownerSid=$($script:CurrentSid); environmentId=$environmentId"
    Assert-Equal -Actual $registered.Description -Expected $expectedDescription -Message "task ownership marker is exact"
    $expectedArguments = '"main.js" "--config" "' + (Join-Path $envRoot "daemon.json") + '"'
    Assert-True -Condition ($registered.Action.Argument -ceq $expectedArguments) -Message "task arguments are the quoted direct argv"
    Assert-True -Condition (-not $registered.Action.Argument.Contains("token-value")) -Message "task arguments do not expose token"
    Assert-Contains -Haystack $registered.Action.Execute -Needle "node.exe" -Message "task runs node.exe"
    $record = Get-Content -LiteralPath (Join-Path $envRoot "last-operation.json") -Raw | ConvertFrom-Json
    Assert-Equal -Actual ([string] $record.type) -Expected "install" -Message "last operation type install"
    Assert-Equal -Actual ([string] $record.result) -Expected "succeeded" -Message "last operation succeeded"
    Assert-True -Condition ((Get-Content -LiteralPath (Join-Path $envRoot "last-operation.json") -Raw).Length -le 4096) `
        -Message "last operation stays bounded"
    Assert-Equal -Actual ([string] $record.operationId) -Expected $updateOperationId -Message "supplied operation id recorded"
    $env:KK_STUDIO_UPDATE_OPERATION_ID = ""

    # Two cards of the same user stay independent.
    $secondPairs = New-Inputs -Environment $secondEnvironmentId
    $exit = Invoke-Installer -Parameters @{
        Command = "install"; EnvironmentId = $secondEnvironmentId; Version = $daemonVersion
        ConfigFile = $secondPairs[0]; TokenFile = $secondPairs[1]
    }
    Assert-True -Condition ($exit -eq 0) -Message "second card installs independently"
    $secondRoot = Join-Path (Join-Path (Join-Path $profileDir ".kk-studio") "environments") $secondEnvironmentId
    Assert-True -Condition ((Test-Path -LiteralPath (Join-Path $secondRoot "lib\main.js")) -and
        (Test-Path -LiteralPath (Join-Path $envRoot "lib\main.js"))) -Message "both card programs exist"
    Assert-True -Condition ($script:Tasks.Count -eq 2) -Message "two independent tasks are registered"

    # update replaces only the program/task; config and token stay byte-identical.
    $configBefore = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $envRoot "daemon.json")))
    $tokenBefore = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $envRoot "daemon.token")))
    Set-Content -LiteralPath (Join-Path $envRoot "lib\old-marker") -Value "old" -Encoding ASCII
    $script:NetworkCalls = @()
    $exit = Invoke-Installer -Parameters @{
        Command = "update"; EnvironmentId = $environmentId; Version = $nextVersion
    }
    Assert-True -Condition ($exit -eq 0) -Message "update exits 0"
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $envRoot "lib\old-marker"))) -Message "program replaced"
    Assert-Equal -Actual ([Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $envRoot "daemon.json")))) `
        -Expected $configBefore -Message "config unchanged by update"
    Assert-Equal -Actual ([Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $envRoot "daemon.token")))) `
        -Expected $tokenBefore -Message "token unchanged by update"
    $record = Get-Content -LiteralPath (Join-Path $envRoot "last-operation.json") -Raw | ConvertFrom-Json
    Assert-Equal -Actual ([string] $record.type) -Expected "update" -Message "last operation type update"
    Assert-Equal -Actual ([string] $record.targetVersion) -Expected $nextVersion -Message "update target version recorded"

    # update refuses configuration options and never creates a missing installation.
    $rejectedUpdate = $false
    try {
        Invoke-Installer -Parameters @{
            Command = "update"; EnvironmentId = $environmentId; Version = $nextVersion
            ConfigFile = $pairs[0]; TokenFile = $pairs[1]
        } | Out-Null
    }
    catch {
        $rejectedUpdate = $true
    }
    Assert-True -Condition $rejectedUpdate -Message "update rejects configuration options"
    $missingUpdate = $false
    try {
        Invoke-Installer -Parameters @{
            Command = "update"; EnvironmentId = "33333333-3333-4333-8333-333333333333"; Version = $nextVersion
        } | Out-Null
    }
    catch {
        $missingUpdate = $true
    }
    Assert-True -Condition $missingUpdate -Message "update requires an existing installation"

    # A busy card fails without touching the program.
    $busyId = "44444444-4444-4444-8444-444444444444"
    $busyRoot = Join-Path (Join-Path (Join-Path $profileDir ".kk-studio") "environments") $busyId
    New-Item -ItemType Directory -Path $busyRoot -Force | Out-Null
    Set-Acl -LiteralPath $busyRoot -AclObject (New-PrivateAcl -Directory)
    New-Item -ItemType Directory -Path (Join-Path $busyRoot ".fake-install-lock") -Force | Out-Null
    $busyPairs = New-Inputs -Environment $busyId
    $busyRejected = $false
    try {
        Invoke-Installer -Parameters @{
            Command = "install"; EnvironmentId = $busyId; Version = $daemonVersion
            ConfigFile = $busyPairs[0]; TokenFile = $busyPairs[1]
        } | Out-Null
    }
    catch {
        $busyRejected = $true
    }
    Assert-True -Condition $busyRejected -Message "busy card is rejected"
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $busyRoot "lib"))) -Message "busy card keeps no program"

    # status is read-only and distinguishes Running from stopped.
    $exit = Invoke-Installer -Parameters @{ Command = "status"; EnvironmentId = $environmentId }
    Assert-True -Condition ($exit -eq 0) -Message "status exits 0 when Running"
    Assert-True -Condition ($script:Tasks.Count -eq 2) -Message "status preserves tasks"
    $script:Tasks[$taskName].State = "Ready"
    $exit = Invoke-Installer -Parameters @{ Command = "status"; EnvironmentId = $environmentId }
    Assert-True -Condition ($exit -eq 3) -Message "status exits 3 when stopped"

    # uninstall removes only the target card and keeps its configuration and user data.
    $skillDir = Join-Path $envRoot "skills\skill-a"
    New-Item -ItemType Directory -Path $skillDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $skillDir "SKILL.md") -Value "user data" -Encoding ASCII
    $exit = Invoke-Installer -Parameters @{ Command = "uninstall"; EnvironmentId = $environmentId }
    Assert-True -Condition ($exit -eq 0) -Message "uninstall exits 0"
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $envRoot "lib"))) -Message "only target lib removed"
    Assert-True -Condition (-not $script:Tasks.ContainsKey($taskName)) -Message "only target task removed"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $envRoot "daemon.json")) -Message "config preserved"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $envRoot "daemon.token")) -Message "token preserved"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $skillDir "SKILL.md")) -Message "user data preserved"
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $secondRoot "lib\main.js")) -Message "other card untouched"
    $record = Get-Content -LiteralPath (Join-Path $envRoot "last-operation.json") -Raw | ConvertFrom-Json
    Assert-Equal -Actual ([string] $record.type) -Expected "uninstall" -Message "last operation type uninstall"

    $exit = Invoke-Installer -Parameters @{ Command = "status"; EnvironmentId = $environmentId }
    Assert-True -Condition ($exit -eq 1) -Message "status exits 1 after uninstall"
    $exit = Invoke-Installer -Parameters @{ Command = "uninstall"; EnvironmentId = $environmentId }
    Assert-True -Condition ($exit -eq 0) -Message "second uninstall is idempotent"
}
finally {
    $env:USERPROFILE = $originalProfile
    $env:KK_TEST_LOCK_LOG = $originalLockLog
    $env:KK_TEST_DAEMON_LOCKED = $originalLocked
    Remove-Item -LiteralPath $sandbox -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ("Windows installer fixture: {0} checks, {1} failures" -f $script:Checks, $script:Failures)
if ($script:Failures -gt 0) { exit 1 }

exit 0
