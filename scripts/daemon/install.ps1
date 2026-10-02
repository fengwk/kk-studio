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
    [string] $Command = "install",

    [switch] $Help,
    [switch] $FromSource,
    [AllowEmptyString()]
    [string] $Version,
    [AllowEmptyString()]
    [string] $GatewayUri,
    [AllowEmptyString()]
    [string] $RegistrationTokenFile,
    [AllowEmptyString()]
    [string] $RegistrationToken,
    [AllowEmptyString()]
    [string] $JavaHome,
    [AllowEmptyString()]
    [string] $DataDir,
    [AllowEmptyString()]
    [string] $Note,
    [AllowEmptyString()]
    [string] $BashExecutable,
    [AllowEmptyString()]
    [string] $LspConfig
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
    "RegistrationToken",
    "JavaHome",
    "DataDir",
    "Note",
    "BashExecutable",
    "LspConfig"
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
$script:SelectedBash = $null
$script:ResolvedTokenFile = $null
$script:ResolvedDataDir = $null
$script:ResolvedLspConfig = $null
$script:StagedJar = $null
$script:DownloadDirectory = $null
$script:ConfigRoot = $null
$script:PendingToken = $null
$script:ResolvedReleaseTag = $null

function Write-Usage {
    @'
Usage: .\install.ps1 [install|upgrade|status|uninstall] [options]

Install, upgrade, inspect, or remove the kk-studio Environment Daemon as a
per-user Windows Scheduled Task. The default command is install. By default,
download the latest official fengwk/kk-studio GitHub Release (no checkout needed).

Commands:
  install    Download, verify, install/update the managed JAR and task,
             start it, and verify the task stays Running.
  upgrade    Require an existing managed task, replace only the JAR, then
             restart and verify. Preserve all task arguments, token and data.
  status     Print task state and recent Task Scheduler information.
             Exit 0 when Running, 1 when not installed, 3 otherwise.
  uninstall  Stop and unregister the managed task, then remove its JAR.
             The registration token file and daemon data are preserved.
  -Help      Print this help. `install -Help` is also accepted.

Install/upgrade options:
  -Version <vTAG>                   Pin a release tag matching ^v[0-9A-Za-z._-]+$.
                                    Otherwise resolve latest once for JAR + SHA.
  -FromSource                      Explicitly build a local checkout with Maven.
                                    Cannot be combined with -Version.

Install options (not accepted by upgrade/status/uninstall):
  -GatewayUri <uri>                 Absolute ws:// or wss:// URI; prompt if omitted.
  -RegistrationToken <text>         Persist a private token file (never daemon argv).
                                    Mutually exclusive with -RegistrationTokenFile.
                                    If neither is given, prompt with AsSecureString.
  -RegistrationTokenFile <path>     Absolute nonempty regular file.
                                    The owner must be the current user, ACL
                                    inheritance must be disabled, and no other
                                    SID may have an Allow ACE. Token text is
                                    never read or printed by this installer.
                                    Inline/prompt tokens are written atomically to
                                    %LOCALAPPDATA%\kk-studio\config\daemon.token,
                                    owned by the current user with protected ACL.
                                    Unsafe existing storage directories are rejected.
  -JavaHome <dir>                   Optional JDK 21 home. Default order:
                                    JAVA_HOME_21, JAVA_HOME, then PATH.
  -DataDir <path>                   Optional absolute daemon data directory
                                    (default: %USERPROFILE%\.kk-studio).
  -Note <text>                      Optional trimmed single-line note, at most
                                    512 characters.
  -BashExecutable <path>            Optional bash.exe used by process.exec.
                                    Defaults to bash.exe on PATH; Git for
                                    Windows or a compatible Bash is required.
  -LspConfig <path>                 Optional absolute path to a JSON file
                                    declaring pre-installed language servers.
                                    Omitted by default, which disables LSP.

Environment:
  DAEMON_VERIFY_TIMEOUT_SECONDS=30  Seconds to wait for task state changes.
  DAEMON_VERIFY_STABLE_SECONDS=3    Seconds the task must remain Running.
                                    Both values are non-negative integers.

Managed layout:
  %LOCALAPPDATA%\kk-studio\daemon\kk-studio-daemon.jar
  Task: kk-studio-environment-daemon-<current-user-SID>
  Management: re-download this same raw main install.ps1 and invoke upgrade,
  status or uninstall; no installed management binary or repository is needed.

Windows host semantics:
  The task uses an AtLogOn trigger, Interactive logon, and Limited run level.
  It runs only while that user has an interactive login.
  It runs with its working directory at the managed install root, so the JAR is
  named by its ASCII file name and every application argument is passed as one
  Base64 token that only the daemon decodes.
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

function Assert-LspConfigFile {
    param([AllowEmptyString()][string] $Path)
    Assert-RequiredValue -Value $Path -Name "-LspConfig"
    Assert-AbsoluteWindowsPath -Value $Path -Name "-LspConfig"
    if ($Path -like "*~*") {
        Throw-Failure "-LspConfig must not contain '~'"
    }
    if ($Path -match '[\$%]') {
        Throw-Failure "-LspConfig must not contain environment-variable placeholders"
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Throw-Failure "-LspConfig must be an existing readable regular file"
    }
    try {
        $stream = [System.IO.File]::Open(
            (Get-Item -LiteralPath $Path).FullName,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite
        )
        $stream.Dispose()
    }
    catch {
        Throw-Failure "-LspConfig must be an existing readable regular file"
    }
    return (Get-Item -LiteralPath $Path).FullName
}

function Assert-NoReparseAncestors {
    param([Parameter(Mandatory = $true)][string] $Path)
    $candidate = [IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrEmpty($candidate)) {
        if (Test-Path -LiteralPath $candidate) {
            $item = Get-Item -LiteralPath $candidate -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                Throw-Failure "token storage must not traverse a reparse point"
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
        Throw-Failure "token storage must be a directory"
    }
    $acl = Get-Acl -LiteralPath $Path
    if ((Convert-AccountNameToSid -AccountName $acl.Owner) -ne $script:CurrentSid -or
        -not $acl.AreAccessRulesProtected) {
        Throw-Failure "token directory must be current-user owned with protected ACL inheritance"
    }
    $canWrite = $false
    foreach ($rule in $acl.GetAccessRules(
        $true, $true, [System.Security.Principal.SecurityIdentifier]
    )) {
        if ($rule.AccessControlType -eq
            [System.Security.AccessControl.AccessControlType]::Allow) {
            if ($rule.IdentityReference.Value -ne $script:CurrentSid) {
                Throw-Failure "token directory must not grant access to another SID"
            }
            if (($rule.FileSystemRights -band
                [System.Security.AccessControl.FileSystemRights]::WriteData) -ne 0) {
                $canWrite = $true
            }
        } else {
            Throw-Failure "token directory must not contain deny ACEs"
        }
    }
    if (-not $canWrite) {
        Throw-Failure "token directory must be writable by its owner"
    }
}

function Assert-SecureRegistrationToken {
    param([Parameter(Mandatory = $true)][Security.SecureString] $Token)
    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Token)
        Assert-RequiredValue `
            -Value ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)) `
            -Name "registration token"
    }
    finally {
        if ($pointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
    }
}

function Save-RegistrationToken {
    param([Parameter(Mandatory = $true)][Security.SecureString] $Token)
    # No secret enters process arguments or diagnostics. The private directory is checked
    # before creating a file, and publication replaces only an already validated target.
    if ($Token.Length -eq 0) {
        Throw-Failure "registration token must not be empty"
    }
    Assert-NoReparseAncestors -Path $script:ConfigRoot
    foreach ($directory in @((Split-Path -Parent $script:ConfigRoot), $script:ConfigRoot)) {
        if (-not (Test-Path -LiteralPath $directory)) {
            New-Item -ItemType Directory -Path $directory | Out-Null
            Set-Acl -LiteralPath $directory -AclObject (New-PrivateAcl -Directory)
        }
        Assert-PrivateDirectory -Path $directory
    }
    $target = Join-Path $script:ConfigRoot "daemon.token"
    if (Test-Path -LiteralPath $target) {
        $null = Assert-RegistrationTokenFile -Path $target -ExpectedOwnerSid $script:CurrentSid
    }
    $temporary = Join-Path $script:ConfigRoot ([Guid]::NewGuid().ToString("N") + ".tmp")
    $pointer = [IntPtr]::Zero
    $plain = $null
    try {
        $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew)
        $stream.Dispose()
        Set-Acl -LiteralPath $temporary -AclObject (New-PrivateAcl)
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Token)
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        Assert-RequiredValue -Value $plain -Name "registration token"
        [IO.File]::WriteAllText($temporary, $plain, [Text.UTF8Encoding]::new($false))
        $null = Assert-RegistrationTokenFile -Path $temporary -ExpectedOwnerSid $script:CurrentSid
        Assert-PrivateDirectory -Path $script:ConfigRoot
        if (Test-Path -LiteralPath $target) {
            $null = Assert-RegistrationTokenFile -Path $target -ExpectedOwnerSid $script:CurrentSid
            [IO.File]::Replace($temporary, $target, $null)
        } else {
            [IO.File]::Move($temporary, $target)
        }
        return Assert-RegistrationTokenFile -Path $target -ExpectedOwnerSid $script:CurrentSid
    }
    finally {
        $plain = $null
        if ($pointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
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
    $script:ConfigRoot = Join-Path $localAppData "kk-studio\config"
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
    if ($script:InvocationParameters.ContainsKey("RegistrationToken") -and
        $script:InvocationParameters.ContainsKey("RegistrationTokenFile")) {
        Throw-Failure "-RegistrationToken and -RegistrationTokenFile are mutually exclusive"
    }
    if (-not $script:InvocationParameters.ContainsKey("GatewayUri")) {
        $script:GatewayUri = Read-Host "Gateway URI (ws:// or wss://)"
    }
    Assert-GatewayUri -Value $GatewayUri
    if ($script:InvocationParameters.ContainsKey("JavaHome")) {
        Assert-RequiredValue -Value $JavaHome -Name "-JavaHome"
    }
    if ($script:InvocationParameters.ContainsKey("Note")) {
        Assert-Note -Value $Note
    }
    if ($script:InvocationParameters.ContainsKey("BashExecutable")) {
        Assert-RequiredValue -Value $BashExecutable -Name "-BashExecutable"
    }
    if ($script:InvocationParameters.ContainsKey("LspConfig")) {
        $script:ResolvedLspConfig = Assert-LspConfigFile -Path $LspConfig
    }

    if ($script:InvocationParameters.ContainsKey("RegistrationTokenFile")) {
        $script:ResolvedTokenFile = Assert-RegistrationTokenFile `
            -Path $RegistrationTokenFile -ExpectedOwnerSid $script:CurrentSid
    } else {
        $script:PendingToken = if ($script:InvocationParameters.ContainsKey("RegistrationToken")) {
            Assert-RequiredValue -Value $RegistrationToken -Name "registration token"
            ConvertTo-SecureString -String $RegistrationToken -AsPlainText -Force
        } else {
            Read-Host "Registration token" -AsSecureString
        }
        $script:RegistrationToken = $null
        $script:InvocationParameters.Remove("RegistrationToken")
        $script:PSBoundParameters.Remove("RegistrationToken") | Out-Null
        Assert-SecureRegistrationToken -Token $script:PendingToken
        # Only the future path enters the task definition; credential bytes are not published
        # until all preflight checks pass and the previous task has stopped.
        $script:ResolvedTokenFile = Join-Path $script:ConfigRoot "daemon.token"
    }
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
        Throw-Failure "built daemon JAR failed its --version check (exit code $($result.ExitCode))"
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
    if ($FromSource) {
        $script:ResolvedReleaseTag = $null
        $script:RepoRoot = Resolve-RepositoryRoot
        $script:BuiltJar =
            Join-Path $script:RepoRoot "harness\daemon\target\kk-studio-daemon.jar"
        Invoke-DaemonBuild
    } else {
        Get-ReleaseJar
    }
    Assert-BuiltJar
    Stage-BuiltJar
}

function Assert-ReleaseTag {
    param([AllowEmptyString()][string] $Tag)
    if ($Tag -cnotmatch "\Av[0-9A-Za-z._-]+\z") {
        Throw-Failure "-Version/release tag must match ^v[0-9A-Za-z._-]+$"
    }
}

function Get-ReleaseJar {
    # PS 5.1 otherwise negotiates obsolete TLS on some Windows installations.
    $savedProtocol = [Net.ServicePointManager]::SecurityProtocol
    try {
        [Net.ServicePointManager]::SecurityProtocol =
            $savedProtocol -bor [Net.SecurityProtocolType]::Tls12
        $tag = $Version
        if (-not $script:InvocationParameters.ContainsKey("Version")) {
            $release = Invoke-RestMethod `
                -Uri "https://api.github.com/repos/fengwk/kk-studio/releases/latest" `
                -Headers @{ Accept = "application/vnd.github+json"; "User-Agent" = "kk-studio-installer" }
            $tag = $release.tag_name
        }
        Assert-ReleaseTag -Tag $tag
        $script:ResolvedReleaseTag = $tag
        $asset = "kk-studio-daemon-$tag.jar"
        $baseUri = "https://github.com/fengwk/kk-studio/releases/download/$tag"
        $script:DownloadDirectory = Join-Path ([IO.Path]::GetTempPath()) (
            "kk-studio-release-" + [Guid]::NewGuid().ToString("N")
        )
        New-Item -ItemType Directory -Path $script:DownloadDirectory | Out-Null
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
    $arguments = @(
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
    if ($script:InvocationParameters.ContainsKey("LspConfig")) {
        $arguments += @("--lsp-config", $script:ResolvedLspConfig)
    }
    # The task runs with its working directory at InstallRoot, so the JAR is referenced by
    # its fixed ASCII file name and the absolute install path never enters the command line.
    return @("-jar", "kk-studio-daemon.jar") +
        (ConvertTo-DaemonEncodedArguments -Arguments $arguments)
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
        -WorkingDirectory $script:InstallRoot `
        -UserSid $script:CurrentSid `
        -Description $script:TaskDescription

    if ($null -ne $existing) {
        Stop-DaemonTaskIfRunning -Task $existing
    }
    $managedBeforePublish = Find-DaemonTask
    if ($null -ne $managedBeforePublish) {
        Assert-ManagedTask -Task $managedBeforePublish
    }
    if ($null -ne $script:PendingToken) {
        $null = Save-RegistrationToken -Token $script:PendingToken
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
    Write-Host "Next:      .\install.ps1 status (re-download the same script if needed)"
    return 0
}

function Invoke-Upgrade {
    Assert-NoInstallOptions -ForCommand "upgrade"
    Initialize-HostContext
    $task = Get-RequiredManagedTask
    $actions = @($task.Actions)
    if ($actions.Count -ne 1 -or
        [string]::IsNullOrEmpty($actions[0].Execute) -or
        -not (Test-Path -LiteralPath $actions[0].Execute -PathType Leaf)) {
        Throw-Failure "upgrade requires exactly one existing Java task action"
    }
    $script:SelectedJavaHome = Assert-Jdk21 -Candidate (
        Split-Path -Parent (Split-Path -Parent $actions[0].Execute)
    )
    $script:SelectedJava = Join-Path $script:SelectedJavaHome "bin\java.exe"
    if (-not [string]::Equals(
        [IO.Path]::GetFullPath($actions[0].Execute),
        [IO.Path]::GetFullPath($script:SelectedJava),
        [StringComparison]::OrdinalIgnoreCase
    )) {
        Throw-Failure "task action must execute the validated JDK 21 bin\java.exe"
    }

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
    try {
        if ($Help) {
            foreach ($name in ($script:InstallParameterNames + @("Version", "FromSource"))) {
                if ($script:InvocationParameters.ContainsKey($name)) {
                    Throw-Failure "-Help cannot be combined with -$name"
                }
            }
            Write-Usage
            return 0
        }
        if ($FromSource -and $script:InvocationParameters.ContainsKey("Version")) {
            Throw-Failure "-FromSource and -Version are mutually exclusive"
        }
        if ($script:InvocationParameters.ContainsKey("Version")) {
            Assert-ReleaseTag -Tag $Version
        }
        if ($Command -in @("status", "uninstall") -and
            ($script:InvocationParameters.ContainsKey("Version") -or
             $script:InvocationParameters.ContainsKey("FromSource"))) {
            Throw-Failure "$Command accepts neither -Version nor -FromSource"
        }

        switch ($Command) {
            "install" { return (Invoke-Install) }
            "upgrade" { return (Invoke-Upgrade) }
            "status" { return (Invoke-Status) }
            "uninstall" { return (Invoke-Uninstall) }
        }
    }
    finally {
        if ($null -ne $script:PendingToken) {
            $script:PendingToken.Dispose()
            $script:PendingToken = $null
        }
        $script:RegistrationToken = $null
        $script:InvocationParameters.Remove("RegistrationToken")
        $script:PSBoundParameters.Remove("RegistrationToken") | Out-Null
        $script:ResolvedReleaseTag = $null
        if (-not [string]::IsNullOrEmpty($script:StagedJar) -and
            (Test-Path -LiteralPath $script:StagedJar)) {
            Remove-Item -LiteralPath $script:StagedJar -Force `
                -ErrorAction SilentlyContinue
        }
        if (-not [string]::IsNullOrEmpty($script:DownloadDirectory) -and
            (Test-Path -LiteralPath $script:DownloadDirectory)) {
            Remove-Item -LiteralPath $script:DownloadDirectory -Recurse -Force `
                -ErrorAction SilentlyContinue
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
