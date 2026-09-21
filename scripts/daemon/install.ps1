#Requires -Version 5.1
<#
.SYNOPSIS
Installs the kk-studio Environment Daemon for the current Windows user.

.DESCRIPTION
The daemon runs as a per-user Scheduled Task while the installing user is logged in.
It is not a Windows Service. The task executes java.exe directly: no cmd.exe,
PowerShell runner, wrapper script, environment file, or registry entry is created.
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0)]
    [ValidateSet("install", "upgrade", "status", "uninstall")]
    [string] $Command,

    [switch] $Help,
    [AllowEmptyString()]
    [string] $GatewayUri,
    [AllowEmptyString()]
    [string] $RegistrationTokenFile,
    [AllowEmptyString()]
    [string] $JavaHome,
    [AllowEmptyString()]
    [string] $DataDir,
    [AllowEmptyString()]
    [string] $Note,
    [AllowEmptyString()]
    [string] $BashExecutable,
    [AllowEmptyString()]
    [string] $LspBridgeCommand,
    [AllowEmptyString()]
    [string] $JavapExecutable
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:InvocationParameters = @{}
foreach ($key in $PSBoundParameters.Keys) {
    $script:InvocationParameters[$key] = $PSBoundParameters[$key]
}

$script:ManagedBy = "scripts/daemon/install.ps1"
$script:InstallParameterNames = @(
    "GatewayUri",
    "RegistrationTokenFile",
    "JavaHome",
    "DataDir",
    "Note",
    "BashExecutable",
    "LspBridgeCommand",
    "JavapExecutable"
)
$script:VerifyTimeoutSeconds = 30
$script:VerifyStableSeconds = 3
$script:RepoRoot = $null
$script:BuiltJar = $null
$script:HomePath = $null
$script:InstallRoot = $null
$script:InstalledJar = $null
$script:CurrentSid = $null
$script:TaskName = $null
$script:TaskDescription = $null
$script:SelectedJavaHome = $null
$script:SelectedJava = $null
$script:SelectedJavap = $null
$script:SelectedBash = $null
$script:ResolvedTokenFile = $null
$script:ResolvedDataDir = $null
$script:StagedJar = $null

function Write-Usage {
    @'
Usage: scripts/daemon/install.ps1 <command> [options]

Install, upgrade, inspect, or remove the kk-studio Environment Daemon as a
per-user Windows Scheduled Task built from this source checkout.

Commands:
  install    Build this checkout, install/update the managed JAR and task,
             start it, and verify the task stays Running.
  upgrade    Require an existing managed task, rebuild and replace only the
             JAR, then restart and verify. No install option is accepted.
  status     Print task state and recent Task Scheduler information.
             Exit 0 when Running, 1 when not installed, 3 otherwise.
  uninstall  Stop and unregister the managed task, then remove its JAR.
             The registration token file and daemon data are preserved.
  -Help      Print this help. `install -Help` is also accepted.

Install options:
  -GatewayUri <uri>                 Required absolute ws:// or wss:// URI.
  -RegistrationTokenFile <path>     Required absolute nonempty regular file.
                                    The owner must be the current user, ACL
                                    inheritance must be disabled, and no other
                                    SID may have an Allow ACE. Token text is
                                    never read or printed by this installer.
  -JavaHome <dir>                   Optional JDK 21 home. Default order:
                                    JAVA_HOME_21, JAVA_HOME, then PATH.
  -DataDir <path>                   Optional absolute daemon data directory
                                    (default: %USERPROFILE%\.kk-studio).
  -Note <text>                      Optional trimmed single-line note, at most
                                    512 characters.
  -BashExecutable <path>            Optional bash.exe used by process.exec.
                                    Defaults to bash.exe on PATH; Git for
                                    Windows or a compatible Bash is required.
  -LspBridgeCommand <command>       Optional LSP bridge command.
  -JavapExecutable <path>           Optional javap.exe; defaults to the
                                    selected JDK's bin\javap.exe.

Environment:
  DAEMON_VERIFY_TIMEOUT_SECONDS=30  Seconds to wait for task state changes.
  DAEMON_VERIFY_STABLE_SECONDS=3    Seconds the task must remain Running.
                                    Both values are non-negative integers.

Managed layout:
  %LOCALAPPDATA%\kk-studio\daemon\kk-studio-daemon.jar
  Task: kk-studio-environment-daemon-<current-user-SID>

Windows host semantics:
  The task uses an AtLogOn trigger, Interactive logon, and Limited run level.
  It runs only while that user has an interactive login.
  Task Scheduler does not capture daemon stdout/stderr.
  RestartCount/RestartInterval are best effort settings; this is not Windows
  Service/systemd supervision.
'@ | Write-Host
}

function Throw-Failure {
    param([Parameter(Mandatory = $true)][string] $Message)
    throw [System.InvalidOperationException]::new($Message)
}

function Assert-NoControlCharacters {
    param(
        [AllowEmptyString()][string] $Value,
        [Parameter(Mandatory = $true)][string] $Name
    )
    if ($null -eq $Value -or $Value -match "[\x00-\x1F\x7F]") {
        Throw-Failure "$Name must not contain control characters"
    }
}

function Assert-RequiredValue {
    param(
        [AllowEmptyString()][string] $Value,
        [Parameter(Mandatory = $true)][string] $Name
    )
    if ([string]::IsNullOrEmpty($Value)) {
        Throw-Failure "$Name is required"
    }
    Assert-NoControlCharacters -Value $Value -Name $Name
}

function Assert-AbsoluteWindowsPath {
    param(
        [AllowEmptyString()][string] $Value,
        [Parameter(Mandatory = $true)][string] $Name
    )
    Assert-RequiredValue -Value $Value -Name $Name
    $drivePath = $Value -match "^[A-Za-z]:[\\/]"
    $uncPath = $Value -match "^\\\\[^\\/]+[\\/][^\\/]+(?:[\\/]|$)"
    if (-not $drivePath -and -not $uncPath) {
        Throw-Failure "$Name must be an absolute drive or UNC path"
    }
}

function Assert-GatewayUri {
    param([AllowEmptyString()][string] $Value)
    Assert-RequiredValue -Value $Value -Name "-GatewayUri"
    if ($Value -match "\s") {
        Throw-Failure "-GatewayUri must not contain whitespace"
    }
    $parsed = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref] $parsed)) {
        Throw-Failure "-GatewayUri must be an absolute ws/wss URI"
    }
    if (($parsed.Scheme -ne "ws" -and $parsed.Scheme -ne "wss") -or
        [string]::IsNullOrWhiteSpace($parsed.Host)) {
        Throw-Failure "-GatewayUri must use ws or wss and include a host"
    }
}

function Assert-Note {
    param([AllowEmptyString()][string] $Value)
    Assert-RequiredValue -Value $Value -Name "-Note"
    if ($Value.Trim() -ne $Value) {
        Throw-Failure "-Note must not have surrounding whitespace"
    }
    if ($Value.Length -gt 512) {
        Throw-Failure "-Note must not exceed 512 characters"
    }
}

function Convert-AccountNameToSid {
    param([Parameter(Mandatory = $true)][string] $AccountName)
    if ($AccountName -match "^S-[0-9]+(?:-[0-9]+)+$") {
        return ([System.Security.Principal.SecurityIdentifier]::new(
            $AccountName
        )).Value
    }
    $account = [System.Security.Principal.NTAccount]::new($AccountName)
    return $account.Translate([System.Security.Principal.SecurityIdentifier]).Value
}

function Assert-RegistrationTokenFile {
    param(
        [AllowEmptyString()][string] $Path,
        [Parameter(Mandatory = $true)][string] $ExpectedOwnerSid
    )
    Assert-AbsoluteWindowsPath -Value $Path -Name "-RegistrationTokenFile"
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Throw-Failure "-RegistrationTokenFile must be an existing regular file"
    }

    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        Throw-Failure "-RegistrationTokenFile must not be a reparse point"
    }
    if ($item.Length -le 0) {
        Throw-Failure "-RegistrationTokenFile must not be empty"
    }

    # Metadata only: token bytes are never opened or read.
    $acl = Get-Acl -LiteralPath $item.FullName
    $ownerSid = Convert-AccountNameToSid -AccountName $acl.Owner
    if ($ownerSid -ne $ExpectedOwnerSid) {
        Throw-Failure "-RegistrationTokenFile must be owned by the current user"
    }
    if (-not $acl.AreAccessRulesProtected) {
        Throw-Failure "-RegistrationTokenFile ACL inheritance must be disabled"
    }

    $currentUserCanRead = $false
    $rules = $acl.GetAccessRules(
        $true,
        $true,
        [System.Security.Principal.SecurityIdentifier]
    )
    foreach ($rule in $rules) {
        $ruleSid = $rule.IdentityReference.Value
        if ($rule.AccessControlType -eq
            [System.Security.AccessControl.AccessControlType]::Allow) {
            if ($ruleSid -ne $ExpectedOwnerSid) {
                Throw-Failure "-RegistrationTokenFile must not grant access to another SID"
            }
            if (($rule.FileSystemRights -band
                [System.Security.AccessControl.FileSystemRights]::ReadData) -ne 0) {
                $currentUserCanRead = $true
            }
        }
        elseif (($rule.FileSystemRights -band
            [System.Security.AccessControl.FileSystemRights]::ReadData) -ne 0) {
            Throw-Failure "-RegistrationTokenFile must not contain a deny-read ACE"
        }
    }
    if (-not $currentUserCanRead) {
        Throw-Failure "-RegistrationTokenFile must be readable by its owner"
    }
    return $item.FullName
}

function Get-NonnegativeEnvironmentInteger {
    param(
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][int] $DefaultValue
    )
    $value = [Environment]::GetEnvironmentVariable($Name, "Process")
    if ([string]::IsNullOrEmpty($value)) {
        return $DefaultValue
    }
    if ($value -notmatch "^[0-9]+$") {
        Throw-Failure "$Name must be a non-negative decimal integer: $value"
    }
    $parsed = 0
    if (-not [int]::TryParse($value, [ref] $parsed)) {
        Throw-Failure "$Name is too large: $value"
    }
    return $parsed
}

function Resolve-RepositoryRoot {
    if (-not [string]::IsNullOrEmpty($env:KK_STUDIO_REPO_ROOT)) {
        Assert-AbsoluteWindowsPath -Value $env:KK_STUDIO_REPO_ROOT `
            -Name "KK_STUDIO_REPO_ROOT"
        $override = Get-Item -LiteralPath $env:KK_STUDIO_REPO_ROOT
        if (-not $override.PSIsContainer) {
            Throw-Failure "KK_STUDIO_REPO_ROOT must name a directory"
        }
        return $override.FullName
    }

    $candidate = Get-Item -LiteralPath $PSScriptRoot
    while ($null -ne $candidate) {
        if (Test-Path -LiteralPath (Join-Path $candidate.FullName ".git")) {
            return $candidate.FullName
        }
        $candidate = $candidate.Parent
    }
    Throw-Failure "cannot locate the kk-studio repository root; set KK_STUDIO_REPO_ROOT"
}

function Assert-WindowsHost {
    if ($env:OS -ne "Windows_NT") {
        Throw-Failure "scripts/daemon/install.ps1 supports Windows only"
    }
}

function Assert-ScheduledTasksAvailable {
    foreach ($name in @(
        "Get-ScheduledTask",
        "Get-ScheduledTaskInfo",
        "New-ScheduledTask",
        "New-ScheduledTaskAction",
        "New-ScheduledTaskPrincipal",
        "New-ScheduledTaskSettingsSet",
        "New-ScheduledTaskTrigger",
        "Register-ScheduledTask",
        "Start-ScheduledTask",
        "Stop-ScheduledTask",
        "Unregister-ScheduledTask"
    )) {
        if ($null -eq (Get-Command $name -ErrorAction SilentlyContinue)) {
            Throw-Failure "required ScheduledTasks command is unavailable: $name"
        }
    }
}

function Initialize-HostContext {
    Assert-WindowsHost
    $script:VerifyTimeoutSeconds = Get-NonnegativeEnvironmentInteger `
        -Name "DAEMON_VERIFY_TIMEOUT_SECONDS" -DefaultValue 30
    $script:VerifyStableSeconds = Get-NonnegativeEnvironmentInteger `
        -Name "DAEMON_VERIFY_STABLE_SECONDS" -DefaultValue 3

    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    if ($null -eq $identity.User) {
        Throw-Failure "cannot resolve the current Windows user SID"
    }
    $script:CurrentSid = $identity.User.Value
    $script:TaskName = "kk-studio-environment-daemon-$($script:CurrentSid)"
    $script:TaskDescription =
        "Managed by $($script:ManagedBy); schema=1; ownerSid=$($script:CurrentSid)"

    $script:HomePath =
        [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    $localAppData =
        [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    Assert-AbsoluteWindowsPath -Value $script:HomePath -Name "current user profile"
    Assert-AbsoluteWindowsPath -Value $localAppData -Name "current LocalApplicationData"
    $script:InstallRoot = Join-Path $localAppData "kk-studio\daemon"
    $script:InstalledJar = Join-Path $script:InstallRoot "kk-studio-daemon.jar"
    $script:RepoRoot = Resolve-RepositoryRoot
    $script:BuiltJar =
        Join-Path $script:RepoRoot "harness\daemon\target\kk-studio-daemon.jar"
    Assert-ScheduledTasksAvailable
}

function Invoke-WithoutJavaOptionEnvironment {
    param([Parameter(Mandatory = $true)][scriptblock] $Action)
    $names = @("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")
    $saved = @{}
    foreach ($name in $names) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        [Environment]::SetEnvironmentVariable($name, $null, "Process")
    }
    try {
        & $Action
    }
    finally {
        foreach ($name in $names) {
            [Environment]::SetEnvironmentVariable($name, $saved[$name], "Process")
        }
    }
}

function Assert-Jdk21 {
    param([Parameter(Mandatory = $true)][string] $Candidate)
    Assert-AbsoluteWindowsPath -Value $Candidate -Name "JDK 21 home"
    $resolved = (Get-Item -LiteralPath $Candidate).FullName
    $java = Join-Path $resolved "bin\java.exe"
    $javac = Join-Path $resolved "bin\javac.exe"
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
        Throw-Failure "JDK 21 home must contain bin\java.exe: $resolved"
    }
    if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) {
        Throw-Failure "JDK 21 home must contain bin\javac.exe: $resolved"
    }

    $output = Invoke-WithoutJavaOptionEnvironment {
        $text = & $java -version 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            Throw-Failure "cannot execute $java"
        }
        return $text
    }
    if ($output -notmatch 'version\s+"21(?:[.\-+][^"]*)?"') {
        $firstLine = ($output -split "\r?\n", 2)[0]
        Throw-Failure "JDK 21 is required (found: $firstLine)"
    }
    return $resolved
}

function Resolve-JavaHome {
    param([AllowEmptyString()][string] $ExplicitJavaHome)
    if (-not [string]::IsNullOrEmpty($ExplicitJavaHome)) {
        Assert-NoControlCharacters -Value $ExplicitJavaHome -Name "-JavaHome"
        return Assert-Jdk21 -Candidate $ExplicitJavaHome
    }
    foreach ($candidate in @($env:JAVA_HOME_21, $env:JAVA_HOME)) {
        if (-not [string]::IsNullOrEmpty($candidate)) {
            Assert-NoControlCharacters -Value $candidate -Name "JAVA_HOME_21/JAVA_HOME"
            return Assert-Jdk21 -Candidate $candidate
        }
    }
    $javaCommand = Get-Command "java.exe" -CommandType Application `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $javaCommand) {
        $bin = Split-Path -Parent $javaCommand.Source
        return Assert-Jdk21 -Candidate (Split-Path -Parent $bin)
    }
    Throw-Failure (
        "JDK 21 not found: set JAVA_HOME_21 or JAVA_HOME, pass -JavaHome, " +
        "or put java.exe on PATH"
    )
}

function Resolve-Executable {
    param(
        [AllowEmptyString()][string] $Value,
        [Parameter(Mandatory = $true)][string] $DefaultName,
        [Parameter(Mandatory = $true)][string] $OptionName
    )
    $candidate = if ([string]::IsNullOrEmpty($Value)) { $DefaultName } else { $Value }
    Assert-NoControlCharacters -Value $candidate -Name $OptionName
    if ($candidate -match "^[A-Za-z]:[\\/]" -or
        $candidate -match "^\\\\[^\\/]+[\\/][^\\/]+(?:[\\/]|$)") {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            Throw-Failure "$OptionName executable does not exist: $candidate"
        }
        return (Get-Item -LiteralPath $candidate).FullName
    }
    $resolved = Get-Command $candidate -CommandType Application `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $resolved) {
        Throw-Failure "cannot resolve $candidate; pass $OptionName"
    }
    return $resolved.Source
}

function Resolve-InstallInputs {
    Assert-GatewayUri -Value $GatewayUri
    Assert-RequiredValue -Value $RegistrationTokenFile `
        -Name "-RegistrationTokenFile"
    if ($script:InvocationParameters.ContainsKey("JavaHome")) {
        Assert-RequiredValue -Value $JavaHome -Name "-JavaHome"
    }
    if ($script:InvocationParameters.ContainsKey("Note")) {
        Assert-Note -Value $Note
    }
    if ($script:InvocationParameters.ContainsKey("BashExecutable")) {
        Assert-RequiredValue -Value $BashExecutable -Name "-BashExecutable"
    }
    if ($script:InvocationParameters.ContainsKey("LspBridgeCommand")) {
        Assert-RequiredValue -Value $LspBridgeCommand -Name "-LspBridgeCommand"
    }
    if ($script:InvocationParameters.ContainsKey("JavapExecutable")) {
        Assert-RequiredValue -Value $JavapExecutable -Name "-JavapExecutable"
    }

    $script:ResolvedTokenFile = Assert-RegistrationTokenFile `
        -Path $RegistrationTokenFile -ExpectedOwnerSid $script:CurrentSid
    if ($script:InvocationParameters.ContainsKey("DataDir")) {
        Assert-AbsoluteWindowsPath -Value $DataDir -Name "-DataDir"
        $script:ResolvedDataDir = [IO.Path]::GetFullPath($DataDir)
    }
    else {
        $script:ResolvedDataDir = Join-Path $script:HomePath ".kk-studio"
    }

    $script:SelectedJavaHome = Resolve-JavaHome -ExplicitJavaHome $JavaHome
    $script:SelectedJava = Join-Path $script:SelectedJavaHome "bin\java.exe"
    $script:SelectedBash = Resolve-Executable -Value $BashExecutable `
        -DefaultName "bash.exe" -OptionName "-BashExecutable"
    if ($script:InvocationParameters.ContainsKey("JavapExecutable")) {
        $script:SelectedJavap = Resolve-Executable -Value $JavapExecutable `
            -DefaultName "javap.exe" -OptionName "-JavapExecutable"
    }
    else {
        $script:SelectedJavap =
            Join-Path $script:SelectedJavaHome "bin\javap.exe"
        if (-not (Test-Path -LiteralPath $script:SelectedJavap -PathType Leaf)) {
            Throw-Failure (
                "the selected JDK has no bin\javap.exe: $($script:SelectedJavaHome)"
            )
        }
    }
}

function Invoke-DaemonBuild {
    $maven = Get-Command "mvn.cmd" -CommandType Application `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $maven) {
        Throw-Failure "missing command: mvn.cmd"
    }
    $savedJavaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Process")
    Push-Location $script:RepoRoot
    try {
        [Environment]::SetEnvironmentVariable(
            "JAVA_HOME",
            $script:SelectedJavaHome,
            "Process"
        )
        Write-Host (
            "==> Cleaning and packaging harness/daemon " +
            "(JAVA_HOME=$($script:SelectedJavaHome))"
        )
        & $maven.Source -B -ntp -pl harness/daemon -am clean package |
            Out-Host
        if ($LASTEXITCODE -ne 0) {
            Throw-Failure "Maven daemon build failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
        [Environment]::SetEnvironmentVariable(
            "JAVA_HOME",
            $savedJavaHome,
            "Process"
        )
    }
}

function Assert-BuiltJar {
    if (-not (Test-Path -LiteralPath $script:BuiltJar -PathType Leaf) -or
        (Get-Item -LiteralPath $script:BuiltJar).Length -le 0) {
        Throw-Failure "built daemon JAR not found or empty: $($script:BuiltJar)"
    }
    $output = Invoke-WithoutJavaOptionEnvironment {
        $text = & $script:SelectedJava -jar $script:BuiltJar --version 2>&1 |
            Out-String
        if ($LASTEXITCODE -ne 0) {
            Throw-Failure "built daemon JAR failed its --version check"
        }
        return $text
    }
    if (-not $output.Trim().StartsWith(
        "kk-studio-daemon ",
        [StringComparison]::Ordinal
    )) {
        Throw-Failure "unexpected daemon --version output: $($output.Trim())"
    }
}

function Stage-BuiltJar {
    if (-not (Test-Path -LiteralPath $script:InstallRoot -PathType Container)) {
        New-Item -ItemType Directory -Path $script:InstallRoot -Force | Out-Null
    }
    $script:StagedJar = Join-Path $script:InstallRoot (
        ".kk-studio-daemon.jar.tmp.$PID.$([Guid]::NewGuid().ToString('N'))"
    )
    Copy-Item -LiteralPath $script:BuiltJar -Destination $script:StagedJar
}

function Prepare-StagedJar {
    Invoke-DaemonBuild
    Assert-BuiltJar
    Stage-BuiltJar
}

function Publish-StagedJar {
    Move-Item -LiteralPath $script:StagedJar `
        -Destination $script:InstalledJar -Force
    $script:StagedJar = $null
}

function ConvertTo-WindowsCommandLineArgument {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string] $Argument
    )
    if ($Argument.Length -gt 0 -and $Argument -notmatch '[\s"]') {
        return $Argument
    }

    $builder = [Text.StringBuilder]::new()
    [void] $builder.Append('"')
    $backslashes = 0
    foreach ($character in $Argument.ToCharArray()) {
        if ($character -eq '\') {
            $backslashes++
            continue
        }
        if ($character -eq '"') {
            if ($backslashes -gt 0) {
                [void] $builder.Append([char] '\', (2 * $backslashes))
            }
            [void] $builder.Append('\')
            [void] $builder.Append('"')
            $backslashes = 0
            continue
        }
        if ($backslashes -gt 0) {
            [void] $builder.Append([char] '\', $backslashes)
            $backslashes = 0
        }
        [void] $builder.Append($character)
    }
    if ($backslashes -gt 0) {
        [void] $builder.Append([char] '\', (2 * $backslashes))
    }
    [void] $builder.Append('"')
    return $builder.ToString()
}

function ConvertTo-WindowsCommandLine {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]] $Arguments
    )
    $serialized = foreach ($argument in $Arguments) {
        ConvertTo-WindowsCommandLineArgument -Argument $argument
    }
    return ($serialized -join " ")
}

function New-DaemonArgumentList {
    $arguments = @(
        "-jar",
        $script:InstalledJar,
        "--gateway-uri",
        $GatewayUri,
        "--registration-token-file",
        $script:ResolvedTokenFile,
        "--data-dir",
        $script:ResolvedDataDir
    )
    if ($script:InvocationParameters.ContainsKey("Note")) {
        $arguments += @("--note", $Note)
    }
    $arguments += @("--bash-executable", $script:SelectedBash)
    if ($script:InvocationParameters.ContainsKey("LspBridgeCommand")) {
        $arguments += @("--lsp-bridge-command", $LspBridgeCommand)
    }
    $arguments += @("--javap-executable", $script:SelectedJavap)
    return $arguments
}

function New-DaemonScheduledTaskDefinition {
    param(
        [Parameter(Mandatory = $true)][string] $JavaExecutable,
        [Parameter(Mandatory = $true)][string[]] $DaemonArguments,
        [Parameter(Mandatory = $true)][string] $WorkingDirectory,
        [Parameter(Mandatory = $true)][string] $UserSid,
        [Parameter(Mandatory = $true)][string] $Description
    )
    $serializedArguments =
        ConvertTo-WindowsCommandLine -Arguments $DaemonArguments
    $action = New-ScheduledTaskAction -Execute $JavaExecutable `
        -Argument $serializedArguments -WorkingDirectory $WorkingDirectory
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $UserSid
    $principal = New-ScheduledTaskPrincipal -UserId $UserSid `
        -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet `
        -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -MultipleInstances IgnoreNew `
        -RestartCount 3 `
        -RestartInterval (New-TimeSpan -Minutes 1)
    return New-ScheduledTask -Action $action -Trigger $trigger `
        -Principal $principal -Settings $settings -Description $Description
}

function Find-DaemonTask {
    # A filtered Get-ScheduledTask query reports a missing task as an error. Enumerate first so
    # absence is represented by $null while genuine scheduler/CIM failures still terminate.
    return Get-ScheduledTask -ErrorAction Stop |
        Where-Object {
            $_.TaskPath -eq "\" -and $_.TaskName -eq $script:TaskName
        } |
        Select-Object -First 1
}

function Assert-ManagedTask {
    param([Parameter(Mandatory = $true)] $Task)
    if ($Task.Description -ne $script:TaskDescription) {
        Throw-Failure (
            "refusing to touch unmanaged Scheduled Task $($script:TaskName) " +
            "(missing exact ownership marker)"
        )
    }
}

function Get-RequiredManagedTask {
    $task = Find-DaemonTask
    if ($null -eq $task) {
        Throw-Failure "no managed installation found: $($script:TaskName)"
    }
    Assert-ManagedTask -Task $task
    return $task
}

function Wait-TaskNotRunning {
    for ($attempt = 0; $attempt -le $script:VerifyTimeoutSeconds; $attempt++) {
        $task = Find-DaemonTask
        if ($null -eq $task) {
            return
        }
        Assert-ManagedTask -Task $task
        if ([string] $task.State -notin @("Running", "Queued")) {
            return
        }
        if ($attempt -lt $script:VerifyTimeoutSeconds) {
            Start-Sleep -Seconds 1
        }
    }
    Throw-Failure (
        "$($script:TaskName) is still Running after stop; no managed file was replaced"
    )
}

function Stop-DaemonTaskIfRunning {
    param([Parameter(Mandatory = $true)] $Task)
    # Refresh after a potentially long Maven build. A task replaced by another owner during the
    # build must never be stopped or overwritten based on the stale object passed by the caller.
    $Task = Find-DaemonTask
    if ($null -eq $Task) {
        return
    }
    Assert-ManagedTask -Task $Task
    if ([string] $Task.State -in @("Running", "Queued")) {
        Stop-ScheduledTask -TaskName $script:TaskName -TaskPath "\"
        Wait-TaskNotRunning
    }
}

function Start-AndVerifyDaemonTask {
    $task = Get-RequiredManagedTask
    Start-ScheduledTask -TaskName $script:TaskName -TaskPath "\"
    for ($attempt = 0; $attempt -le $script:VerifyTimeoutSeconds; $attempt++) {
        $task = Get-RequiredManagedTask
        if ($null -ne $task -and [string] $task.State -eq "Running") {
            for ($stable = 0; $stable -lt $script:VerifyStableSeconds; $stable++) {
                Start-Sleep -Seconds 1
                $task = Find-DaemonTask
                if ($null -eq $task -or [string] $task.State -ne "Running") {
                    Throw-Failure (
                        "$($script:TaskName) did not stay Running for " +
                        "$($script:VerifyStableSeconds)s after start"
                    )
                }
            }
            return
        }
        if ($attempt -lt $script:VerifyTimeoutSeconds) {
            Start-Sleep -Seconds 1
        }
    }
    Throw-Failure "$($script:TaskName) is not Running after start"
}

function Assert-NoInstallOptions {
    param([Parameter(Mandatory = $true)][string] $ForCommand)
    foreach ($name in $script:InstallParameterNames) {
        if ($script:InvocationParameters.ContainsKey($name)) {
            Throw-Failure "$ForCommand accepts no install options: -$name"
        }
    }
}

function Invoke-Install {
    if (-not $script:InvocationParameters.ContainsKey("GatewayUri")) {
        Throw-Failure "install requires -GatewayUri"
    }
    if (-not $script:InvocationParameters.ContainsKey("RegistrationTokenFile")) {
        Throw-Failure "install requires -RegistrationTokenFile"
    }
    Initialize-HostContext
    Resolve-InstallInputs

    $existing = Find-DaemonTask
    if ($null -ne $existing) {
        Assert-ManagedTask -Task $existing
    }

    Prepare-StagedJar
    $definition = New-DaemonScheduledTaskDefinition `
        -JavaExecutable $script:SelectedJava `
        -DaemonArguments (New-DaemonArgumentList) `
        -WorkingDirectory $script:HomePath `
        -UserSid $script:CurrentSid `
        -Description $script:TaskDescription

    if ($null -ne $existing) {
        Stop-DaemonTaskIfRunning -Task $existing
    }
    $managedBeforePublish = Find-DaemonTask
    if ($null -ne $managedBeforePublish) {
        Assert-ManagedTask -Task $managedBeforePublish
    }
    Publish-StagedJar
    $managedBeforeRegistration = Find-DaemonTask
    if ($null -ne $managedBeforeRegistration) {
        Assert-ManagedTask -Task $managedBeforeRegistration
    }
    Register-ScheduledTask -TaskName $script:TaskName `
        -InputObject $definition -Force | Out-Null
    Start-AndVerifyDaemonTask

    Write-Host "==> $($script:TaskName) is Running"
    Write-Host "Task:      $($script:TaskName)"
    Write-Host "JAR:       $($script:InstalledJar)"
    Write-Host "Data dir:  $($script:ResolvedDataDir)"
    Write-Host "Next:      .\scripts\daemon\install.ps1 status"
    return 0
}

function Invoke-Upgrade {
    Assert-NoInstallOptions -ForCommand "upgrade"
    Initialize-HostContext
    $task = Get-RequiredManagedTask
    $script:SelectedJavaHome = Resolve-JavaHome -ExplicitJavaHome ""
    $script:SelectedJava = Join-Path $script:SelectedJavaHome "bin\java.exe"

    Prepare-StagedJar
    Stop-DaemonTaskIfRunning -Task $task
    $managedBeforePublish = Get-RequiredManagedTask
    Publish-StagedJar
    Start-AndVerifyDaemonTask

    Write-Host "==> $($script:TaskName) is Running"
    Write-Host "Task:      $($script:TaskName)"
    Write-Host "JAR:       $($script:InstalledJar)"
    return 0
}

function Invoke-Status {
    Assert-NoInstallOptions -ForCommand "status"
    Initialize-HostContext
    $task = Find-DaemonTask
    if ($null -eq $task) {
        [Console]::Error.WriteLine(
            "kk-studio daemon is not installed: $($script:TaskName)"
        )
        return 1
    }
    try {
        Assert-ManagedTask -Task $task
    }
    catch {
        [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
        return 1
    }

    $info = Get-ScheduledTaskInfo -TaskName $script:TaskName -TaskPath "\"
    $task | Select-Object TaskName, State, Description | Format-List | Out-Host
    $info | Select-Object LastRunTime, LastTaskResult, NextRunTime |
        Format-List | Out-Host
    Write-Host (
        "Task Scheduler does not capture daemon stdout/stderr; " +
        "task state is not gateway health."
    )
    if ([string] $task.State -eq "Running") {
        return 0
    }
    [Console]::Error.WriteLine(
        "$($script:TaskName) is installed but not Running"
    )
    return 3
}

function Invoke-Uninstall {
    Assert-NoInstallOptions -ForCommand "uninstall"
    Initialize-HostContext
    $task = Get-RequiredManagedTask
    Stop-DaemonTaskIfRunning -Task $task
    $task = Get-RequiredManagedTask
    Unregister-ScheduledTask -TaskName $script:TaskName `
        -TaskPath "\" -Confirm:$false
    if (Test-Path -LiteralPath $script:InstalledJar -PathType Leaf) {
        Remove-Item -LiteralPath $script:InstalledJar -Force
    }

    Write-Host "Removed task: $($script:TaskName)"
    Write-Host "Removed JAR:  $($script:InstalledJar)"
    Write-Host (
        "Preserved: the registration token file and daemon data directory " +
        "(default %USERPROFILE%\.kk-studio)"
    )
    return 0
}

function Invoke-Main {
    if ($Help) {
        foreach ($name in $script:InstallParameterNames) {
            if ($script:InvocationParameters.ContainsKey($name)) {
                Throw-Failure "-Help cannot be combined with -$name"
            }
        }
        Write-Usage
        return 0
    }
    if ([string]::IsNullOrEmpty($Command)) {
        Write-Usage
        return 2
    }

    try {
        switch ($Command) {
            "install" { return (Invoke-Install) }
            "upgrade" { return (Invoke-Upgrade) }
            "status" { return (Invoke-Status) }
            "uninstall" { return (Invoke-Uninstall) }
        }
    }
    finally {
        if (-not [string]::IsNullOrEmpty($script:StagedJar) -and
            (Test-Path -LiteralPath $script:StagedJar)) {
            Remove-Item -LiteralPath $script:StagedJar -Force `
                -ErrorAction SilentlyContinue
        }
    }
}

if ($MyInvocation.InvocationName -ne ".") {
    try {
        $exitCode = Invoke-Main
        exit $exitCode
    }
    catch {
        [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
        exit 1
    }
}
