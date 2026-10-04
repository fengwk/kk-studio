#Requires -Version 5.1
<#
.SYNOPSIS
Installs (default), inspects, or removes the kk-studio Environment Daemon
for the current Windows user.

.DESCRIPTION
The daemon runs as a per-user Scheduled Task while the installing user is logged in.
It is not a Windows Service. The task executes java.exe directly: no cmd.exe,
PowerShell runner, wrapper script, environment file, or registry entry is created.
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0)]
    [ValidateSet("install", "status", "uninstall", "help")]
    [string] $Command = "install",

    [AllowEmptyString()]
    [string] $ConfigFile,
    [AllowEmptyString()]
    [string] $TokenFile,
    [AllowEmptyString()]
    [string] $JavaHome
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:InvocationParameters = @{}
foreach ($key in $PSBoundParameters.Keys) {
    $script:InvocationParameters[$key] = $PSBoundParameters[$key]
}

$script:ManagedBy = "scripts/daemon/install.ps1"
$script:InstallParameterNames = @("ConfigFile", "TokenFile", "JavaHome")
$script:VerifyTimeoutSeconds = 30
$script:VerifyStableSeconds = 3
$script:HomePath = $null
$script:InstallRoot = $null
$script:InstalledJar = $null
$script:InstalledConfig = $null
$script:InstalledToken = $null
$script:CurrentSid = $null
$script:TaskName = $null
$script:TaskDescription = $null
$script:SelectedJavaHome = $null
$script:SelectedJava = $null
$script:BuiltJar = $null
$script:DownloadDirectory = $null
$script:ResolvedReleaseTag = $null
$script:BackupDirectory = $null
# The core preflight prints one trusted, value-free line for an invalid configuration;
# only that bounded marker is surfaced, never raw diagnostics that could echo secrets.
$script:ConfigFailureMarker = "Invalid daemon configuration: "
$script:ConfigFailureMaxLength = 512

function Write-Usage {
    @'
Usage: .\install.ps1 install -ConfigFile <absolute daemon.json> -TokenFile <absolute daemon.token> [-JavaHome <absolute JDK21>]
       .\install.ps1 status|uninstall|help

Only the latest official fengwk/kk-studio GitHub release is installed.
Inputs must be sibling, nonempty regular files with current-user-only protected ACLs.
Token bytes are copied only; they never enter task arguments or environment variables.
JDK 21 discovery: JAVA_HOME_21, JAVA_HOME, PATH. The read-only preflight resolves the
configured (or default) Bash from the daemon config; no PATH Bash is assumed here.

Fixed layout: %USERPROFILE%\.kk-studio\daemon.json, daemon.token, lib, logs, backups.
Task: kk-studio-environment-daemon-<current-user-SID>
Install replaces program/config/token and restarts, preserving runtime data.
Private backups precede replacement. Failures require manual restoration; no automatic rollback.
Uninstall is idempotent and preserves config/token/data/backups.
Status exits 0 when Running, 1 when absent, 3 otherwise.

The task executes java.exe directly with only --config (UTF-8 Base64 machine encoding).
It uses AtLogOn, Interactive logon and Limited run level: only while this user is logged in.
Task Scheduler does not capture daemon stdout/stderr. Task state is not connection health.
RestartCount/RestartInterval are best effort settings, not Windows Service/systemd supervision.
DAEMON_VERIFY_TIMEOUT_SECONDS=30 and DAEMON_VERIFY_STABLE_SECONDS=3 are nonnegative integers.
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

function Assert-PrivateFile {
    param(
        [AllowEmptyString()][string] $Path,
        [Parameter(Mandatory = $true)][string] $ExpectedOwnerSid,
        [string] $Name = "managed file",
        [long] $MaximumBytes = 536870912
    )
    Assert-AbsoluteWindowsPath -Value $Path -Name "$Name"
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Throw-Failure "$Name must be an existing regular file"
    }

    Assert-NoReparseAncestors -Path $Path
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        Throw-Failure "$Name must not be a reparse point"
    }
    if ($item.Length -le 0 -or $item.Length -gt $MaximumBytes) {
        Throw-Failure "$Name must be nonempty and bounded"
    }

    # Metadata only: token bytes are never opened or read.
    $acl = Get-Acl -LiteralPath $item.FullName
    $ownerSid = Convert-AccountNameToSid -AccountName $acl.Owner
    if ($ownerSid -ne $ExpectedOwnerSid) {
        Throw-Failure "$Name must be owned by the current user"
    }
    if (-not $acl.AreAccessRulesProtected) {
        Throw-Failure "$Name ACL inheritance must be disabled"
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
                Throw-Failure "$Name must not grant access to another SID"
            }
            if (($rule.FileSystemRights -band
                [System.Security.AccessControl.FileSystemRights]::ReadData) -ne 0) {
                $currentUserCanRead = $true
            }
        }
        elseif (($rule.FileSystemRights -band
            [System.Security.AccessControl.FileSystemRights]::ReadData) -ne 0) {
            Throw-Failure "$Name must not contain a deny-read ACE"
        }
    }
    if (-not $currentUserCanRead) {
        Throw-Failure "$Name must be readable by its owner"
    }
    return $item.FullName
}

function Assert-NoReparseAncestors {
    param([Parameter(Mandatory = $true)][string] $Path)
    $candidate = [IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrEmpty($candidate)) {
        if (Test-Path -LiteralPath $candidate) {
            $item = Get-Item -LiteralPath $candidate -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                Throw-Failure "managed storage must not traverse a reparse point"
            }
        }
        $candidate = Split-Path -Parent $candidate
    }
}

function New-PrivateAcl {
    param([switch] $Directory)
    $owner = [System.Security.Principal.SecurityIdentifier]::new($script:CurrentSid)
    $acl = if ($Directory) {
        [System.Security.AccessControl.DirectorySecurity]::new()
    } else {
        [System.Security.AccessControl.FileSecurity]::new()
    }
    $acl.SetOwner($owner)
    $acl.SetAccessRuleProtection($true, $false)
    $rule = if ($Directory) {
        [System.Security.AccessControl.FileSystemAccessRule]::new(
            $owner, [System.Security.AccessControl.FileSystemRights]::FullControl,
            [System.Security.AccessControl.InheritanceFlags]"ContainerInherit, ObjectInherit",
            [System.Security.AccessControl.PropagationFlags]::None,
            [System.Security.AccessControl.AccessControlType]::Allow
        )
    } else {
        [System.Security.AccessControl.FileSystemAccessRule]::new(
            $owner, [System.Security.AccessControl.FileSystemRights]::FullControl,
            [System.Security.AccessControl.AccessControlType]::Allow
        )
    }
    $acl.AddAccessRule($rule)
    return $acl
}

function Assert-PrivateDirectory {
    param([Parameter(Mandatory = $true)][string] $Path)
    Assert-NoReparseAncestors -Path $Path
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        Throw-Failure "managed storage must be a directory"
    }
    $acl = Get-Acl -LiteralPath $Path
    if ((Convert-AccountNameToSid -AccountName $acl.Owner) -ne $script:CurrentSid -or
        -not $acl.AreAccessRulesProtected) {
        Throw-Failure "managed directory must be current-user owned with protected ACL inheritance"
    }
    $canWrite = $false
    foreach ($rule in $acl.GetAccessRules(
        $true, $true, [System.Security.Principal.SecurityIdentifier]
    )) {
        if ($rule.AccessControlType -eq
            [System.Security.AccessControl.AccessControlType]::Allow) {
            if ($rule.IdentityReference.Value -ne $script:CurrentSid) {
                Throw-Failure "managed directory must not grant access to another SID"
            }
            if (($rule.FileSystemRights -band
                [System.Security.AccessControl.FileSystemRights]::WriteData) -ne 0) {
                $canWrite = $true
            }
        } else {
            Throw-Failure "managed directory must not contain deny ACEs"
        }
    }
    if (-not $canWrite) {
        Throw-Failure "managed directory must be writable by its owner"
    }
}

function New-PrivateDirectory {
    param([Parameter(Mandatory = $true)][string] $Path)
    Assert-NoReparseAncestors -Path $Path
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path | Out-Null
        Set-Acl -LiteralPath $Path -AclObject (New-PrivateAcl -Directory)
    }
    Assert-PrivateDirectory -Path $Path
}

function Copy-PrivateFile {
    param([string] $Source, [string] $Destination)
    Assert-PrivateDirectory -Path (Split-Path -Parent $Destination)
    # CreateNew prevents accidental replacement. Set the private ACL before copying any bytes.
    $stream = [IO.File]::Open($Destination, [IO.FileMode]::CreateNew)
    $stream.Dispose()
    Set-Acl -LiteralPath $Destination -AclObject (New-PrivateAcl)
    [IO.File]::Copy($Source, $Destination, $true)
    $null = Assert-PrivateFile -Path $Destination -ExpectedOwnerSid $script:CurrentSid
}

function Publish-PrivateFile {
    param([string] $Source, [string] $Destination)
    $parent = Split-Path -Parent $Destination
    Assert-PrivateDirectory -Path $parent
    $temporary = Join-Path $parent (".publish-" + [Guid]::NewGuid().ToString("N"))
    try {
        Copy-PrivateFile -Source $Source -Destination $temporary
        if (Test-Path -LiteralPath $Destination) {
            $null = Assert-PrivateFile -Path $Destination -ExpectedOwnerSid $script:CurrentSid
            [IO.File]::Replace($temporary, $Destination, [NullString]::Value)
        } else {
            [IO.File]::Move($temporary, $Destination)
        }
        $null = Assert-PrivateFile -Path $Destination -ExpectedOwnerSid $script:CurrentSid
    }
    finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Assert-ManagedLayout {
    param([bool] $HasTask)
    if (-not $HasTask -and (Test-Path -LiteralPath $script:InstalledJar)) {
        $path = ConvertTo-PowerShellLiteral -Value $script:InstalledJar
        Throw-Failure ("Unowned program conflict: $($script:InstalledJar). No managed task exists.`n" +
            "Inspect: Get-Item -LiteralPath $path; Get-FileHash -LiteralPath $path`n" +
            (Get-ConflictBackupCommands) + "`n" +
            "After inspection, preserve the unknown program: Move-Item -LiteralPath $path " +
            "-Destination (Join-Path `$backup 'kk-studio-daemon.jar'). " +
            "Do not delete unknown artifacts/data. Then retry install.")
    }
    foreach ($directory in @($script:InstallRoot, (Split-Path -Parent $script:InstalledJar),
        (Join-Path $script:InstallRoot "logs"), (Join-Path $script:InstallRoot "backups"))) {
        Assert-NoReparseAncestors -Path $directory
        if (Test-Path -LiteralPath $directory) { Assert-PrivateDirectory -Path $directory }
    }
    foreach ($file in @($script:InstalledJar, $script:InstalledConfig, $script:InstalledToken)) {
        if (Test-Path -LiteralPath $file) {
            $null = Assert-PrivateFile -Path $file -ExpectedOwnerSid $script:CurrentSid
        }
    }
}

function ConvertTo-PowerShellLiteral {
    param([Parameter(Mandatory = $true)][string] $Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function Get-ConflictBackupCommands {
    # Print executable recovery instructions, never execute them or export an unknown task.
    return ("Create a unique owner-private backup before export/move:`n" +
        "`$backup = Join-Path `$env:USERPROFILE ('kk-studio-conflict-' + [Guid]::NewGuid().ToString('N')); " +
        "New-Item -ItemType Directory -Path `$backup; " +
        "`$owner = [Security.Principal.WindowsIdentity]::GetCurrent().User; " +
        "`$acl = [Security.AccessControl.DirectorySecurity]::new(); " +
        "`$acl.SetOwner(`$owner); `$acl.SetAccessRuleProtection(`$true, `$false); " +
        "`$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(" +
        "`$owner, 'FullControl', 'ContainerInherit, ObjectInherit', 'None', 'Allow')); " +
        "Set-Acl -LiteralPath `$backup -AclObject `$acl")
}

function Backup-ManagedFiles {
    param([AllowNull()] $Task)
    $files = @($script:InstalledJar, $script:InstalledConfig, $script:InstalledToken) |
        Where-Object { Test-Path -LiteralPath $_ }
    if (@($files).Count -eq 0 -and $null -eq $Task) { return }
    $root = Join-Path $script:InstallRoot "backups"
    New-PrivateDirectory -Path $root
    $script:BackupDirectory = Join-Path $root (
        (Get-Date -Format "yyyyMMddTHHmmss") + "-" + [Guid]::NewGuid().ToString("N"))
    try {
        New-PrivateDirectory -Path $script:BackupDirectory
        foreach ($file in $files) {
            $null = Assert-PrivateFile -Path $file -ExpectedOwnerSid $script:CurrentSid
            Copy-PrivateFile -Source $file -Destination (Join-Path $script:BackupDirectory (Split-Path -Leaf $file))
        }
        if ($null -ne $Task) {
            $null = Get-RequiredManagedTask
            $xml = Export-ScheduledTask -TaskName $script:TaskName -TaskPath "\"
            $path = Join-Path $script:BackupDirectory "task.xml"
            $stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew)
            $stream.Dispose()
            Set-Acl -LiteralPath $path -AclObject (New-PrivateAcl)
            [IO.File]::WriteAllText($path, $xml, [Text.UTF8Encoding]::new($false))
        }
    }
    catch {
        Throw-Failure "Backup failed: $($_.Exception.Message). Partial backup: $($script:BackupDirectory). Live installation unchanged; inspect this directory before retrying."
    }
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

function Assert-WindowsHost {
    if ($env:OS -ne "Windows_NT") {
        Throw-Failure "scripts/daemon/install.ps1 supports Windows only"
    }
}

function Assert-ScheduledTasksAvailable {
    foreach ($name in @(
        "Get-ScheduledTask",
        "Get-ScheduledTaskInfo",
        "Export-ScheduledTask",
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
    Assert-AbsoluteWindowsPath -Value $script:HomePath -Name "current user profile"
    $script:InstallRoot = Join-Path $script:HomePath ".kk-studio"
    $script:InstalledJar = Join-Path $script:InstallRoot "lib\kk-studio-daemon.jar"
    $script:InstalledConfig = Join-Path $script:InstallRoot "daemon.json"
    $script:InstalledToken = Join-Path $script:InstallRoot "daemon.token"
    Assert-ScheduledTasksAvailable
}

function Invoke-WithoutJavaOptionEnvironment {
    param([Parameter(Mandatory = $true)][scriptblock] $Action)
    $names = @("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")
    $saved = @{}
    foreach ($name in $names) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        # Preserve an actual .NET null instead of PowerShell converting it to an empty string.
        [Environment]::SetEnvironmentVariable($name, [NullString]::Value, "Process")
    }
    try {
        & $Action
    }
    finally {
        foreach ($name in $names) {
            $value = if ($null -eq $saved[$name]) {
                [NullString]::Value
            } else {
                $saved[$name]
            }
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

function Invoke-NativeProcess {
    param(
        [Parameter(Mandatory = $true)][string] $Executable,
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [string[]] $Arguments,
        [AllowEmptyString()][string] $WorkingDirectory
    )
    # Native stderr is data, not a PowerShell error record (notably on PS 5.1).
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Executable
    $startInfo.Arguments = ConvertTo-WindowsCommandLine -Arguments $Arguments
    if (-not [string]::IsNullOrEmpty($WorkingDirectory)) {
        # Set natively, so a non-ASCII or spaced directory never enters a command line.
        $startInfo.WorkingDirectory = $WorkingDirectory
    }
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            Throw-Failure "cannot start $Executable"
        }
        # Drain both pipes concurrently before waiting, including output larger than a pipe buffer.
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        return [pscustomobject] @{
            Stdout = $stdout.GetAwaiter().GetResult()
            Stderr = $stderr.GetAwaiter().GetResult()
            ExitCode = $process.ExitCode
        }
    }
    finally {
        $process.Dispose()
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

    $result = Invoke-WithoutJavaOptionEnvironment {
        Invoke-NativeProcess -Executable $java -Arguments @("-version")
    }
    if ($result.ExitCode -ne 0) {
        Throw-Failure "cannot execute $java (exit code $($result.ExitCode))"
    }
    $output = $result.Stdout + $result.Stderr
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

function Resolve-InstallInputs {
    $config = Assert-PrivateFile -Path $ConfigFile -ExpectedOwnerSid $script:CurrentSid `
        -Name "-ConfigFile" -MaximumBytes 1048576
    $token = Assert-PrivateFile -Path $TokenFile -ExpectedOwnerSid $script:CurrentSid `
        -Name "-TokenFile" -MaximumBytes 65536
    if (-not [string]::Equals((Split-Path -Parent $config), (Split-Path -Parent $token),
        [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path -Leaf $config) -cne "daemon.json" -or
        (Split-Path -Leaf $token) -cne "daemon.token") {
        Throw-Failure "-ConfigFile and -TokenFile must be siblings named daemon.json and daemon.token"
    }
    if ($script:InvocationParameters.ContainsKey("JavaHome")) {
        Assert-RequiredValue -Value $JavaHome -Name "-JavaHome"
    }
    $script:SelectedJavaHome = Resolve-JavaHome -ExplicitJavaHome $JavaHome
    $script:SelectedJava = Join-Path $script:SelectedJavaHome "bin\java.exe"
}

function Assert-BuiltJar {
    if (-not (Test-Path -LiteralPath $script:BuiltJar -PathType Leaf) -or
        (Get-Item -LiteralPath $script:BuiltJar).Length -le 0) {
        Throw-Failure "downloaded daemon JAR not found or empty: $($script:BuiltJar)"
    }
    # Probe the JAR exactly as the task runs it: ASCII leaf resolved against a working
    # directory, so a non-ASCII checkout path can never corrupt the -jar argument.
    $jarDirectory = Split-Path -Parent $script:BuiltJar
    $jarFileName = Split-Path -Leaf $script:BuiltJar
    $arguments = @("-jar", $jarFileName) +
        (ConvertTo-DaemonEncodedArguments -Arguments @("--version"))
    $result = Invoke-WithoutJavaOptionEnvironment {
        Invoke-NativeProcess -Executable $script:SelectedJava `
            -Arguments $arguments `
            -WorkingDirectory $jarDirectory
    }
    if ($result.ExitCode -ne 0) {
        Throw-Failure "downloaded daemon JAR failed its --version check (exit code $($result.ExitCode))"
    }
    $output = $result.Stdout
    if (-not [string]::IsNullOrEmpty($script:ResolvedReleaseTag) -and
        $output.TrimEnd([char[]] @("`r", "`n")) -cne
        ("kk-studio-daemon " + $script:ResolvedReleaseTag.Substring(1))) {
        Throw-Failure "daemon --version does not match resolved release tag"
    }
    if (-not $output.Trim().StartsWith(
        "kk-studio-daemon ",
        [StringComparison]::Ordinal
    )) {
        Throw-Failure "unexpected daemon --version output"
    }
}

function Get-TrustedConfigFailureDetail {
    # Surface only the core's fixed, value-free marker line. Every other outcome (an older
    # release rejecting the flag, a stack trace) stays generic so nothing can echo a secret.
    param(
        [AllowEmptyString()][string] $Stdout,
        [AllowEmptyString()][string] $Stderr
    )
    foreach ($stream in @($Stderr, $Stdout)) {
        if ([string]::IsNullOrEmpty($stream)) {
            continue
        }
        foreach ($line in ($stream -split "\r?\n")) {
            $candidate = $line.Trim()
            if (-not $candidate.StartsWith($script:ConfigFailureMarker, [StringComparison]::Ordinal)) {
                continue
            }
            if ($candidate.Length -gt $script:ConfigFailureMaxLength -or
                $candidate -match "[\x00-\x1F\x7F]") {
                return $null
            }
            return $candidate
        }
    }
    return $null
}

function Prepare-StagedJar {
    Get-ReleaseJar
    Assert-BuiltJar
    # Copy inputs to the private download directory, independent of any live installation.
    # Revalidate after the download; never copy from a path that became unsafe during preflight.
    $null = Assert-PrivateFile -Path $ConfigFile -ExpectedOwnerSid $script:CurrentSid -MaximumBytes 1048576
    $null = Assert-PrivateFile -Path $TokenFile -ExpectedOwnerSid $script:CurrentSid -MaximumBytes 65536
    Copy-PrivateFile -Source $ConfigFile -Destination (Join-Path $script:DownloadDirectory "daemon.json")
    Copy-PrivateFile -Source $TokenFile -Destination (Join-Path $script:DownloadDirectory "daemon.token")
    $stagedConfig = Join-Path $script:DownloadDirectory "daemon.json"
    $arguments = @("-jar", (Split-Path -Leaf $script:BuiltJar)) +
        (ConvertTo-DaemonEncodedArguments -Arguments @("--check-config", $stagedConfig))
    $result = Invoke-WithoutJavaOptionEnvironment {
        Invoke-NativeProcess -Executable $script:SelectedJava -Arguments $arguments `
            -WorkingDirectory $script:DownloadDirectory
    }
    if ($result.ExitCode -eq 0 -and
        $result.Stdout.TrimEnd([char[]] @("`r", "`n")) -ceq "Daemon configuration is valid") {
        return
    }
    # Prefer the core's trusted, value-free field diagnostic. Never echo the raw streams: an
    # older release rejecting --check-config or an unexpected stack trace must not leak
    # configuration or credential values.
    $detail = Get-TrustedConfigFailureDetail -Stdout $result.Stdout -Stderr $result.Stderr
    if ($null -ne $detail) {
        Throw-Failure "$detail Existing installation unchanged."
    }
    Throw-Failure (
        "release --check-config failed (exit code $($result.ExitCode)) with unrecognized output; " +
        "the downloaded release may predate the unified --config contract. Existing installation unchanged."
    )
}

function Assert-ReleaseTag {
    param([AllowEmptyString()][string] $Tag)
    if ($Tag -cnotmatch "\Av[0-9A-Za-z._-]+\z") {
        Throw-Failure "release tag must match ^v[0-9A-Za-z._-]+$"
    }
}

function Get-ReleaseJar {
    # PS 5.1 otherwise negotiates obsolete TLS on some Windows installations.
    $savedProtocol = [Net.ServicePointManager]::SecurityProtocol
    try {
        [Net.ServicePointManager]::SecurityProtocol =
            $savedProtocol -bor [Net.SecurityProtocolType]::Tls12
        $release = Invoke-RestMethod `
            -Uri "https://api.github.com/repos/fengwk/kk-studio/releases/latest" `
            -Headers @{ Accept = "application/vnd.github+json"; "User-Agent" = "kk-studio-installer" }
        $tag = $release.tag_name
        Assert-ReleaseTag -Tag $tag
        $script:ResolvedReleaseTag = $tag
        $asset = "kk-studio-daemon-$tag.jar"
        $baseUri = "https://github.com/fengwk/kk-studio/releases/download/$tag"
        $script:DownloadDirectory = Join-Path ([IO.Path]::GetTempPath()) (
            "kk-studio-release-" + [Guid]::NewGuid().ToString("N")
        )
        New-PrivateDirectory -Path $script:DownloadDirectory
        $script:BuiltJar = Join-Path $script:DownloadDirectory $asset
        $checksumPath = "$($script:BuiltJar).sha256"
        Invoke-WebRequest -UseBasicParsing -Uri "$baseUri/$asset" `
            -OutFile $script:BuiltJar | Out-Null
        Invoke-WebRequest -UseBasicParsing -Uri "$baseUri/$asset.sha256" `
            -OutFile $checksumPath | Out-Null
        $checksum = [IO.File]::ReadAllText($checksumPath)
        $pattern = "\A([0-9a-fA-F]{64})  " + [regex]::Escape($asset) + "\r?\n\z"
        if ($checksum -cnotmatch $pattern) {
            Throw-Failure "invalid release checksum: expected exactly one matching asset"
        }
        $expectedHash = $Matches[1]
        $actualHash = (Get-FileHash -LiteralPath $script:BuiltJar -Algorithm SHA256).Hash
        if ($actualHash -ne $expectedHash) {
            Throw-Failure "release checksum mismatch"
        }
    }
    finally {
        [Net.ServicePointManager]::SecurityProtocol = $savedProtocol
    }
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
        [AllowEmptyString()]
        [string[]] $Arguments
    )
    $serialized = foreach ($argument in $Arguments) {
        ConvertTo-WindowsCommandLineArgument -Argument $argument
    }
    return ($serialized -join " ")
}

function ConvertTo-DaemonEncodedArguments {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [string[]] $Arguments
    )
    # Windows JDK 21 converts the Unicode command line through the system ANSI code page,
    # losing characters before Java sees argv. Each application value therefore travels as
    # an ASCII UTF-8 Base64 token, decoded once by the daemon (encoding, not encryption).
    $encoded = foreach ($argument in $Arguments) {
        [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($argument))
    }
    return @("--base64-args") + $encoded
}

function New-DaemonArgumentList {
    return @("-jar", "kk-studio-daemon.jar") +
        (ConvertTo-DaemonEncodedArguments -Arguments @("--config", $script:InstalledConfig))
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
        $name = ConvertTo-PowerShellLiteral -Value $script:TaskName
        Throw-Failure ("refusing to touch unmanaged Scheduled Task \$($script:TaskName): " +
            "missing exact ownership marker. Refusing automatic modification of this task.`n" +
            "Inspect: Get-ScheduledTask -TaskName $name -TaskPath '\' | Format-List *`n" +
            (Get-ConflictBackupCommands) + "`n" +
            "Export-ScheduledTask -TaskName $name -TaskPath '\' | Set-Content (Join-Path `$backup 'task.xml')`n" +
            "After reviewing the export, manually Stop-ScheduledTask -TaskName $name -TaskPath '\'; " +
            "Disable-ScheduledTask -TaskName $name -TaskPath '\'; " +
            "Unregister-ScheduledTask -TaskName $name -TaskPath '\' -Confirm:`$false. " +
            "Do not delete its program/data or fabricate an ownership marker. " +
            "Task Scheduler changes take effect immediately; retry install after the conflicting task is removed.")
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
    # Refresh after potentially long preflight checks. A task replaced by another owner during the
    # preflight must never be stopped or overwritten based on the stale object passed by the caller.
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
                Assert-ManagedTask -Task $task
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
    Initialize-HostContext
    $script:BackupDirectory = $null
    $existing = Find-DaemonTask
    if ($null -ne $existing) { Assert-ManagedTask -Task $existing }
    Assert-ManagedLayout -HasTask ($null -ne $existing)
    Resolve-InstallInputs
    Prepare-StagedJar
    $definition = New-DaemonScheduledTaskDefinition `
        -JavaExecutable $script:SelectedJava -DaemonArguments (New-DaemonArgumentList) `
        -WorkingDirectory (Split-Path -Parent $script:InstalledJar) `
        -UserSid $script:CurrentSid -Description $script:TaskDescription

    # Recheck ownership and target safety after all potentially long-running preflight work.
    $existing = Find-DaemonTask
    if ($null -ne $existing) { Assert-ManagedTask -Task $existing }
    Assert-ManagedLayout -HasTask ($null -ne $existing)
    foreach ($directory in @($script:InstallRoot, (Split-Path -Parent $script:InstalledJar),
        (Join-Path $script:InstallRoot "logs"))) {
        New-PrivateDirectory -Path $directory
    }
    Backup-ManagedFiles -Task $existing
    try {
        if ($null -ne $existing) { Stop-DaemonTaskIfRunning -Task $existing }
        $current = Find-DaemonTask
        if ($null -ne $current) { Assert-ManagedTask -Task $current }
        Publish-PrivateFile -Source $script:BuiltJar -Destination $script:InstalledJar
        Publish-PrivateFile -Source (Join-Path $script:DownloadDirectory "daemon.json") -Destination $script:InstalledConfig
        Publish-PrivateFile -Source (Join-Path $script:DownloadDirectory "daemon.token") -Destination $script:InstalledToken
        $current = Find-DaemonTask
        if ($null -ne $current) { Assert-ManagedTask -Task $current }
        Register-ScheduledTask -TaskName $script:TaskName -TaskPath "\" -InputObject $definition -Force | Out-Null
        Start-AndVerifyDaemonTask
    }
    catch {
        $backup = if ($null -eq $script:BackupDirectory) { "none (first install)" } else { $script:BackupDirectory }
        $recovery = if ($null -eq $script:BackupDirectory) {
            "No previous files exist to restore. Inspect these targets and task first; " +
            "use uninstall for a registered managed task, or preserve an unowned lib JAR " +
            "in a unique private backup using the conflict instructions, then retry install."
        } else {
            "Stop the task, manually copy backed-up files to these targets and restore task.xml " +
            "(if present) with Register-ScheduledTask -Xml (Get-Content -Raw -Encoding UTF8 -LiteralPath " +
            (ConvertTo-PowerShellLiteral -Value (Join-Path $script:BackupDirectory "task.xml")) +
            ") -TaskName '$($script:TaskName)' -TaskPath '\' -Force; " +
            "Start-ScheduledTask -TaskName '$($script:TaskName)' -TaskPath '\'."
        }
        Throw-Failure ("Installation failed: $($_.Exception.Message)`nBackup: $backup`n" +
            "Targets: $($script:InstalledJar), $($script:InstalledConfig), $($script:InstalledToken)`n" +
            "Run .\install.ps1 status; inspect Get-ScheduledTaskInfo -TaskName '$($script:TaskName)'. " +
            "Task Scheduler does not capture stdout/stderr. Inspect " +
            "Get-WinEvent -LogName Microsoft-Windows-TaskScheduler/Operational if enabled. " +
            "$recovery No automatic rollback was attempted.")
    }
    Write-Host "==> $($script:TaskName) is Running; confirm Environment READY in Studio"
    Write-Host "Root: $($script:InstallRoot)"
    if ($null -ne $script:BackupDirectory) { Write-Host "Backup: $($script:BackupDirectory)" }
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
    $task = Find-DaemonTask
    if ($null -eq $task) {
        Write-Host "Task absent; preserved all remaining files: $($script:InstallRoot)"
        return 0
    }
    Assert-ManagedTask -Task $task
    Assert-ManagedLayout -HasTask $true
    Stop-DaemonTaskIfRunning -Task $task
    $null = Get-RequiredManagedTask
    Unregister-ScheduledTask -TaskName $script:TaskName -TaskPath "\" -Confirm:$false
    if (Test-Path -LiteralPath $script:InstalledJar) {
        $null = Assert-PrivateFile -Path $script:InstalledJar -ExpectedOwnerSid $script:CurrentSid
        Remove-Item -LiteralPath $script:InstalledJar -Force
    }
    Write-Host "Removed managed task/program; preserved config/token/data/backups: $($script:InstallRoot)"
    return 0
}

function Invoke-Main {
    try {
        switch ($Command) {
            "install" { return (Invoke-Install) }
            "status" { return (Invoke-Status) }
            "uninstall" { return (Invoke-Uninstall) }
            "help" { Assert-NoInstallOptions -ForCommand "help"; Write-Usage; return 0 }
        }
    }
    finally {
        if (-not [string]::IsNullOrEmpty($script:DownloadDirectory) -and
            (Test-Path -LiteralPath $script:DownloadDirectory)) {
            Remove-Item -LiteralPath $script:DownloadDirectory -Recurse -Force
            $script:DownloadDirectory = $null
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
