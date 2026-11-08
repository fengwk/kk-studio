#Requires -Version 5.1
<#
.SYNOPSIS
Installs, updates, inspects or removes the kk-studio Environment Daemon of exactly one Studio
card for the current Windows user.

.DESCRIPTION
The canonical environment UUID scopes the installation directory, the scheduled task, the
operation lock and the staged work directory; two cards of the same user never share a service,
a program directory or an operation lock. The daemon runs as a per-user Scheduled Task while the
installing user is logged in. The task executes the frozen bundled node.exe directly: no cmd.exe,
PowerShell runner, wrapper script, environment file or registry entry is created.
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0)]
    [ValidateSet("install", "update", "status", "uninstall", "help")]
    [string] $Command = "install",

    [AllowEmptyString()]
    [string] $EnvironmentId,
    [AllowEmptyString()]
    [string] $Version,
    [AllowEmptyString()]
    [string] $ConfigFile,
    [AllowEmptyString()]
    [string] $TokenFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:InvocationParameters = @{}
foreach ($key in $PSBoundParameters.Keys) {
    $script:InvocationParameters[$key] = $PSBoundParameters[$key]
}

$script:ManagedBy = "scripts/daemon/install.ps1"
$script:InstallParameterNames = @("EnvironmentId", "Version", "ConfigFile", "TokenFile")
$script:UpdateParameterNames = @("EnvironmentId", "Version")
$script:EnvironmentIdPattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
$script:VersionPattern = "^[0-9A-Za-z._+-]{1,64}$"
$script:VerifyTimeoutSeconds = 30
$script:VerifyStableSeconds = 3
$script:HomePath = $null
$script:InstallRoot = $null
$script:EnvironmentsRoot = $null
$script:EnvironmentRoot = $null
$script:InstalledLib = $null
$script:InstalledNode = $null
$script:InstalledConfig = $null
$script:InstalledToken = $null
$script:WorkDirectory = $null
$script:LastOperation = $null
$script:CurrentSid = $null
$script:TargetEnvironmentId = $null
$script:TargetVersion = $null
$script:TaskName = $null
$script:TaskDescription = $null
$script:DownloadedArchive = $null
$script:StagedLib = $null
$script:DownloadDirectory = $null
$script:ManagementDirectory = $null
$script:ResolvedReleaseTag = $null
$script:OperationId = $null
$script:Phase = "1/7 检查环境与输入"
$script:PhaseCode = "input"
$script:HelperProcess = $null
$script:HelperHeld = $false
$script:Published = $false
$script:StopAttempted = $false
$script:CleanupFailed = $false
$script:WorkOwned = $false
# The core preflight prints one trusted, value-free line for an invalid configuration;
# only that bounded marker is surfaced, never raw diagnostics that could echo secrets.
$script:ConfigFailureMarker = "Invalid daemon configuration: "
$script:ConfigFailureMaxLength = 512
$script:EnvelopeScript = @'
const fs = require("node:fs");
let value;
try { value = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); } catch { process.exit(2); }
if (value === null || typeof value !== "object" || Array.isArray(value)) process.exit(2);
const keys = Object.keys(value).sort().join(",");
if (keys !== "daemonConfig,environmentId,version") process.exit(2);
if (typeof value.environmentId !== "string" || value.environmentId !== process.argv[2]) process.exit(3);
if (typeof value.version !== "string" || !/^(0|[1-9][0-9]*)$/.test(value.version)) process.exit(2);
if (value.daemonConfig === null || typeof value.daemonConfig !== "object" ||
    Array.isArray(value.daemonConfig)) process.exit(2);
'@

function Write-Usage {
    @'
Usage: .\install.ps1 install -EnvironmentId <UUID> -Version <version> -ConfigFile <absolute daemon.json> -TokenFile <absolute daemon.token>
       .\install.ps1 update -EnvironmentId <UUID> -Version <version>
       .\install.ps1 status -EnvironmentId <UUID>
       .\install.ps1 uninstall -EnvironmentId <UUID>
       .\install.ps1 help

One Studio card is one installation. The canonical -EnvironmentId UUID scopes the task, the
program directory and the operation lock; two cards of the same user stay independent.

install is the only bootstrap/overwrite command. update replaces only the program and the task
definition of an existing installation: it reads the current daemon.json, re-validates it after
the task has stopped and never accepts or writes a configuration or token. The official release
must be named explicitly by -Version; no mutable "latest" release is resolved.

Fixed layout under %USERPROFILE%\.kk-studio\environments\<environment-id>:
  daemon.json, daemon.token, daemon.lock, install.lock, lib, ide, skills, .work,
  last-operation.json
Task: kk-studio-daemon-<current-user-SID>-<environment-id>

Inputs must be sibling, nonempty regular files with current-user-only protected ACLs. Token
bytes are copied only; they never enter task arguments or environment variables.
No user Node is required: the release archive bundles a frozen node.exe runtime.

Installation operations are serialized by a kernel lock (install.lock) held by a separate
temporary helper copy: no PID guessing, stale lock directory or loaded library is reused. A busy
card is reported, not waited on. There is no backup, no historical program directory, no
operation log history and no automatic rollback: a failure after publication can leave the
service unavailable and is recovered by running install again. .work is the only staging area
and last-operation.json holds at most one bounded diagnostic record without secrets.

The task executes the frozen runtime node.exe directly with only main.js and --config.
It uses AtLogOn, Interactive logon and Limited run level: only while this user is logged in.
Task Scheduler does not capture daemon stdout/stderr; task state is not connection health.
RestartCount/RestartInterval are best effort settings, not Windows Service/systemd supervision.

Uninstall stops and removes only this card's task and program; configuration, credentials,
skills and other user data remain. It does not delete the Studio card.
Status exits 0 when Running, 1 when absent, 3 otherwise.
DAEMON_VERIFY_TIMEOUT_SECONDS=30 and DAEMON_VERIFY_STABLE_SECONDS=3 are nonnegative integers.
'@ | Write-Host
}

function Throw-Failure {
    param(
        [Parameter(Mandatory = $true)][string] $Message,
        [ValidateSet("安装", "更新", "卸载")][string] $Action = "安装"
    )
    $next = if ($Action -eq "卸载") { "处理后，重新复制并执行卸载命令" } else { "修正后，重新复制并执行对应命令" }
    $phase = if ([string]::IsNullOrEmpty($script:PhaseCode)) { "unknown" } else { $script:PhaseCode }
    throw [System.InvalidOperationException]::new(
        "${Action}失败 [阶段 ${phase}]：${Message}`n下一步：按上面的原因${next}。"
    )
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

function Assert-EnvironmentId {
    param([AllowEmptyString()][string] $Value)
    Assert-RequiredValue -Value $Value -Name "-EnvironmentId"
    if ($Value -cnotmatch $script:EnvironmentIdPattern) {
        Throw-Failure "-EnvironmentId must be a canonical lowercase UUID (value not echoed)"
    }
    $script:TargetEnvironmentId = $Value
}

function Assert-ReleaseTag {
    param([AllowEmptyString()][string] $Tag)
    if ($Tag -cnotmatch "\Av[0-9A-Za-z._+-]{1,64}\z") {
        Throw-Failure "invalid release tag"
    }
}

function Assert-Version {
    param([AllowEmptyString()][string] $Value)
    Assert-RequiredValue -Value $Value -Name "-Version"
    if ($Value -cnotmatch $script:VersionPattern) {
        Throw-Failure "invalid version"
    }
    Assert-ReleaseTag -Tag "v$Value"
    $script:TargetVersion = $Value
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

function ConvertTo-PowerShellLiteral {
    param([Parameter(Mandatory = $true)][string] $Value)
    return "'" + $Value.Replace("'", "''") + "'"
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
    Assert-EnvironmentId -Value $EnvironmentId
    # 在线更新作业传入自己的 operationId，使固定 last-operation.json 能与发起它的控制命令对应；
    # 非法值忽略，人工命令始终使用本地生成的身份。
    $script:OperationId = [Guid]::NewGuid().ToString()
    if (-not [string]::IsNullOrEmpty($env:KK_STUDIO_UPDATE_OPERATION_ID) -and
        $env:KK_STUDIO_UPDATE_OPERATION_ID -cmatch $script:EnvironmentIdPattern) {
        $script:OperationId = $env:KK_STUDIO_UPDATE_OPERATION_ID
    }
    $script:TaskName =
        "kk-studio-daemon-$($script:CurrentSid)-$($script:TargetEnvironmentId)"
    $script:TaskDescription =
        "Managed by $($script:ManagedBy); ownerSid=$($script:CurrentSid); environmentId=$($script:TargetEnvironmentId)"

    $script:HomePath =
        [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    Assert-AbsoluteWindowsPath -Value $script:HomePath -Name "current user profile"
    $script:InstallRoot = Join-Path $script:HomePath ".kk-studio"
    $script:EnvironmentsRoot = Join-Path $script:InstallRoot "environments"
    $script:EnvironmentRoot = Join-Path $script:EnvironmentsRoot $script:TargetEnvironmentId
    $script:InstalledLib = Join-Path $script:EnvironmentRoot "lib"
    $script:InstalledNode = Join-Path $script:InstalledLib "runtime\code-server\lib\node.exe"
    $script:InstalledConfig = Join-Path $script:EnvironmentRoot "daemon.json"
    $script:InstalledToken = Join-Path $script:EnvironmentRoot "daemon.token"
    $script:WorkDirectory = Join-Path $script:EnvironmentRoot ".work"
    $script:LastOperation = Join-Path $script:EnvironmentRoot "last-operation.json"
    Assert-ScheduledTasksAvailable
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

function Assert-ManagedLayout {
    param([bool] $HasTask)
    if (-not $HasTask -and (Test-Path -LiteralPath $script:InstalledLib)) {
        Throw-Failure ("拒绝覆盖无受管任务的程序目录（$($script:InstalledLib)）。未自动回滚，未知文件未变更。" +
            "`n检查：Get-ChildItem -LiteralPath $($script:EnvironmentRoot)")
    }
    foreach ($directory in @($script:InstallRoot, $script:EnvironmentsRoot,
        $script:EnvironmentRoot, $script:InstalledLib, $script:WorkDirectory)) {
        Assert-NoReparseAncestors -Path $directory
        if (Test-Path -LiteralPath $directory) { Assert-PrivateDirectory -Path $directory }
    }
    foreach ($file in @($script:InstalledConfig, $script:InstalledToken, $script:LastOperation)) {
        if (Test-Path -LiteralPath $file) {
            $null = Assert-PrivateFile -Path $file -ExpectedOwnerSid $script:CurrentSid
        }
    }
}

function ConvertTo-WindowsCommandLineArgument {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string] $Argument
    )
    # Always quote. The Scheduled Task argument string and the independently generated update
    # script must match exactly, and an unquoted bypass would diverge on spaces or metacharacters.
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

function New-DaemonArgumentList {
    # The task runs `node.exe main.js --config <path>`; main.js is an ASCII leaf resolved
    # against the working directory, so a non-ASCII install path never enters argv.
    # Node receives Unicode arguments directly; no pre-encoding is applied.
    return @("main.js", "--config", $script:InstalledConfig)
}

function New-DaemonScheduledTaskDefinition {
    param(
        [Parameter(Mandatory = $true)][string] $NodeExecutable,
        [Parameter(Mandatory = $true)][string[]] $DaemonArguments,
        [Parameter(Mandatory = $true)][string] $WorkingDirectory,
        [Parameter(Mandatory = $true)][string] $UserSid,
        [Parameter(Mandatory = $true)][string] $Description
    )
    $serializedArguments =
        ConvertTo-WindowsCommandLine -Arguments $DaemonArguments
    $action = New-ScheduledTaskAction -Execute $NodeExecutable `
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
    # 所有权同时核对当前用户、卡片 UUID（精确描述）与本卡片的受管程序/配置路径；
    # 同名但指向其它程序的任务一律拒绝，且绝不改写它。真实 CIM 任务只有 Actions 数组，
    # 必须恰好一项，Principal.UserId 也必须解析为当前用户 SID。
    $actions = @()
    $actionsProperty = $Task.PSObject.Properties['Actions']
    if ($null -ne $actionsProperty -and $null -ne $actionsProperty.Value) {
        $actions = @($actionsProperty.Value)
    }
    $foreign = $Task.TaskName -ne $script:TaskName -or
        $Task.Description -ne $script:TaskDescription
    if (-not $foreign) {
        if ($actions.Count -ne 1 -or $null -eq $actions[0]) {
            $foreign = $true
        }
        else {
            $action = $actions[0]
            $execute = [string] $action.Execute
            $arguments = [string] $action.Arguments
            $workingDirectory = [string] $action.WorkingDirectory
            if ($execute.IndexOf($script:InstalledNode, [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
                $arguments.IndexOf("main.js", [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
                $arguments.IndexOf($script:InstalledConfig, [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
                -not $workingDirectory.Equals($script:InstalledLib, [StringComparison]::OrdinalIgnoreCase)) {
                $foreign = $true
            }
        }
    }
    if (-not $foreign) {
        $principal = $null
        $principalProperty = $Task.PSObject.Properties['Principal']
        if ($null -ne $principalProperty) { $principal = $principalProperty.Value }
        $principalUserId = $null
        if ($null -ne $principal) {
            $userIdProperty = $principal.PSObject.Properties['UserId']
            if ($null -ne $userIdProperty) { $principalUserId = [string] $userIdProperty.Value }
        }
        $principalSid = $null
        if (-not [string]::IsNullOrEmpty($principalUserId)) {
            try {
                $principalSid = Convert-AccountNameToSid -AccountName $principalUserId
            }
            catch {
                $principalSid = $null
            }
        }
        if ($principalSid -ne $script:CurrentSid) { $foreign = $true }
    }
    if ($foreign) {
        $name = ConvertTo-PowerShellLiteral -Value $script:TaskName
        Throw-Failure ("拒绝操作非受管计划任务 \$($script:TaskName)（缺少精确所有权标记）。未自动回滚，未知文件未变更。" +
            "`n检查：Get-ScheduledTask -TaskName $name -TaskPath '\' | Format-List TaskName, State, Description" +
            "`n不要伪造所有权标记，也不要删除未知程序或数据。")
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
    Throw-Failure "$($script:TaskName) 停止后仍在运行，未替换任何受管文件。" -Action "安装"
}

function Stop-DaemonTaskIfRunning {
    param([AllowNull()] $Task)
    # Refresh after potentially long preflight checks. A task replaced by another owner during the
    # preflight must never be stopped or overwritten based on the stale object passed by the caller.
    $Task = Find-DaemonTask
    if ($null -eq $Task) {
        return
    }
    Assert-ManagedTask -Task $Task
    if ([string] $Task.State -in @("Running", "Queued")) {
        $script:StopAttempted = $true
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
                        "服务启动失败：$($script:TaskName) 启动后未持续运行 $($script:VerifyStableSeconds) 秒。"
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
    Throw-Failure "服务启动失败：$($script:TaskName) 启动后未进入 Running。"
}

function Assert-OptionSet {
    param(
        [Parameter(Mandatory = $true)][string] $ForCommand,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]] $Allowed
    )
    foreach ($name in $script:InstallParameterNames) {
        if ($script:InvocationParameters.ContainsKey($name) -and $Allowed -notcontains $name) {
            Throw-Failure "$ForCommand does not accept -$name"
        }
    }
}

# 配置信封检查：旧平铺 JSON 与其它卡片的配置都必须在停止服务前被拒绝。退出码 3 专门表示
# UUID 不匹配；daemonConfig 的内部规则由发行归档的 --check-config 负责。
function Assert-ConfigEnvelope {
    param(
        [Parameter(Mandatory = $true)][string] $NodeExecutable,
        [Parameter(Mandatory = $true)][string] $ConfigPath
    )
    $result = Invoke-NativeProcess -Executable $NodeExecutable `
        -Arguments @("-e", $script:EnvelopeScript, $ConfigPath, $script:TargetEnvironmentId)
    if ($result.ExitCode -eq 0) { return }
    if ($result.ExitCode -eq 3) {
        Throw-Failure "配置属于另一张环境卡片；未改动现有安装。"
    }
    Throw-Failure "配置格式不是当前版本；未改动现有安装。"
}

function Assert-EarlyEnvelope {
    param([Parameter(Mandatory = $true)][string] $ConfigPath)
    $content = [IO.File]::ReadAllText($ConfigPath)
    if ($content -notmatch '"environmentId"' -or
        $content -notmatch '"daemonConfig"' -or
        $content -notmatch '"version"') {
        Throw-Failure "配置格式不是当前版本；未改动现有安装。"
    }
}

function Get-TrustedConfigFailureDetail {
    # Surface only the core's fixed, value-free marker line. Every other outcome (e.g. a
    # stack trace) stays generic so nothing can echo a secret.
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

function Invoke-CheckConfig {
    param(
        [Parameter(Mandatory = $true)][string] $ConfigPath,
        [Parameter(Mandatory = $true)][string] $StagedLib
    )
    $node = Join-Path $StagedLib "runtime\code-server\lib\node.exe"
    $result = Invoke-NativeProcess -Executable $node `
        -Arguments @("main.js", "--check-config", $ConfigPath) `
        -WorkingDirectory $StagedLib
    if ($result.ExitCode -eq 0 -and
        $result.Stdout.TrimEnd([char[]] @("`r", "`n")) -ceq "Daemon configuration is valid") {
        return
    }
    $detail = Get-TrustedConfigFailureDetail -Stdout $result.Stdout -Stderr $result.Stderr
    if ($null -ne $detail) {
        Throw-Failure "配置校验失败：$detail 现有安装未改动。"
    }
    Throw-Failure (
        "配置校验失败：--check-config 未通过（退出码 $($result.ExitCode)），且输出无法识别。现有安装未改动。"
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
}

function Get-ManagedProxyArguments {
    param([string] $Uri)
    $arguments = @{}
    if ($env:KK_DAEMON_INSTALL_PROXY_ENV -ne "1") { return $arguments }

    $target = [Uri] $Uri
    $direct = [string]::IsNullOrEmpty($env:HTTPS_PROXY)
    if (-not $direct -and -not [string]::IsNullOrEmpty($env:NO_PROXY)) {
        foreach ($item in $env:NO_PROXY.Split(",")) {
            $rule = $item.Trim()
            # Release URLs use DNS names; numeric address and CIDR rules cannot match them.
            if ($rule -notmatch '^(\*|(?:\*\.)?\.?[a-zA-Z0-9.-]+)(?::([0-9]+))?$') { continue }
            $hostRule = $Matches[1]
            if ($Matches[2] -and [int] $Matches[2] -ne $target.Port) { continue }
            if ($hostRule -eq "*") { $direct = $true; break }
            $hostRule = $hostRule -replace '^\*\.', '' -replace '^\.', ''
            if ($target.Host -ieq $hostRule -or $target.Host.EndsWith(".$hostRule", [StringComparison]::OrdinalIgnoreCase)) {
                $direct = $true
                break
            }
        }
    }
    # PowerShell 5.1 has no -NoProxy; suppress the machine default explicitly.
    [Net.WebRequest]::DefaultWebProxy = $null
    if ($direct) {
        if ($PSVersionTable.PSVersion.Major -ge 6) { $arguments.NoProxy = $true }
    }
    else {
        $arguments.Proxy = $env:HTTPS_PROXY
    }
    return $arguments
}

function Get-ReleaseArchive {
    # PS 5.1 otherwise negotiates obsolete TLS on some Windows installations.
    $savedProtocol = [Net.ServicePointManager]::SecurityProtocol
    $savedProxy = [Net.WebRequest]::DefaultWebProxy
    try {
        [Net.ServicePointManager]::SecurityProtocol =
            $savedProtocol -bor [Net.SecurityProtocolType]::Tls12
        # The generated command always pins the official release; no mutable latest is resolved.
        Assert-Version -Value $Version
        $tag = "v$($script:TargetVersion)"
        $script:ResolvedReleaseTag = $tag
        # The release matrix publishes windows for amd64 only; the fixed asset name is the
        # Windows daemon distribution for this host.
        $asset = "kk-studio-daemon-$tag-windows-amd64.tar.gz"
        $baseUri = "https://github.com/fengwk/kk-studio/releases/download/$tag"
        $proxyArguments = Get-ManagedProxyArguments -Uri $baseUri
        $script:DownloadDirectory = Join-Path ([IO.Path]::GetTempPath()) (
            "kk-studio-release-" + [Guid]::NewGuid().ToString("N")
        )
        New-PrivateDirectory -Path $script:DownloadDirectory
        $script:DownloadedArchive = Join-Path $script:DownloadDirectory $asset
        $script:StagedLib = Join-Path $script:DownloadDirectory "lib"
        $checksumPath = "$($script:DownloadedArchive).sha256"
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "$baseUri/$asset" `
                -OutFile $script:DownloadedArchive @proxyArguments | Out-Null
            Invoke-WebRequest -UseBasicParsing -Uri "$baseUri/$asset.sha256" `
                -OutFile $checksumPath @proxyArguments | Out-Null
        }
        catch {
            if ($_.Exception -is [System.InvalidOperationException]) { throw }
            Throw-Failure "无法下载：正式版本获取失败。请检查网络后重试。"
        }
        $checksum = [IO.File]::ReadAllText($checksumPath)
        $pattern = "\A([0-9a-fA-F]{64})  " + [regex]::Escape($asset) + "\r?\n\z"
        if ($checksum -cnotmatch $pattern) {
            Throw-Failure "校验失败：SHA256 文件格式无效。"
        }
        $expectedHash = $Matches[1]
        $actualHash = (Get-FileHash -LiteralPath $script:DownloadedArchive -Algorithm SHA256).Hash
        if ($actualHash -ne $expectedHash) {
            Throw-Failure "校验失败：SHA256 与正式版本不一致。"
        }
    }
    finally {
        [Net.ServicePointManager]::SecurityProtocol = $savedProtocol
        [Net.WebRequest]::DefaultWebProxy = $savedProxy
    }
}

function Assert-DownloadedArchive {
    if (-not (Test-Path -LiteralPath $script:DownloadedArchive -PathType Leaf) -or
        (Get-Item -LiteralPath $script:DownloadedArchive).Length -le 0) {
        Throw-Failure "downloaded daemon archive not found or empty: $($script:DownloadedArchive)"
    }
    # Windows 10/11 ship tar.exe; it unpacks the release archive without a user Node.
    $tar = Get-Command "tar.exe" -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $tar) {
        Throw-Failure "tar.exe is required to unpack the daemon release archive"
    }
    New-PrivateDirectory -Path $script:StagedLib
    # Windows bsdtar receives narrow argv. Keep the archive and -C arguments ASCII and let the
    # native working directory carry a Unicode profile path.
    $archiveName = Split-Path -Leaf $script:DownloadedArchive
    $unpack = Invoke-NativeProcess -Executable $tar.Source `
        -Arguments @("-xzf", $archiveName, "-C", "lib") `
        -WorkingDirectory $script:DownloadDirectory
    if ($unpack.ExitCode -ne 0) {
        Throw-Failure "downloaded daemon archive could not be unpacked (exit code $($unpack.ExitCode))"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $script:StagedLib "main.js") -PathType Leaf)) {
        Throw-Failure "downloaded daemon archive is missing main.js"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $script:StagedLib "native\lock-helper.js") -PathType Leaf)) {
        Throw-Failure "downloaded daemon archive is missing the install lock helper"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $script:StagedLib "node_modules\koffi") -PathType Container)) {
        Throw-Failure "downloaded daemon archive is missing the helper native dependency"
    }
    $node = Join-Path $script:StagedLib "runtime\code-server\lib\node.exe"
    if (-not (Test-Path -LiteralPath $node -PathType Leaf)) {
        Throw-Failure "downloaded daemon archive is missing the bundled node.exe runtime"
    }
    # Probe the archive's own frozen runtime exactly as the task runs it: the ASCII leaf
    # main.js is resolved against the working directory, so a non-ASCII install path can
    # never corrupt the argument. Node receives the Unicode argv directly.
    $result = Invoke-NativeProcess -Executable $node `
        -Arguments @("main.js", "--version") `
        -WorkingDirectory $script:StagedLib
    if ($result.ExitCode -ne 0) {
        Throw-Failure "downloaded daemon archive failed its --version check (exit code $($result.ExitCode))"
    }
    $output = $result.Stdout
    if ($output.TrimEnd([char[]] @("`r", "`n")) -cne
        ("kk-studio-daemon " + $script:TargetVersion)) {
        Throw-Failure "daemon --version does not match resolved release tag"
    }
}

function Prepare-StagedArchive {
    # Copy inputs to the private download directory, independent of any live installation.
    # Revalidate after the download; never copy from a path that became unsafe during preflight.
    $null = Assert-PrivateFile -Path $ConfigFile -ExpectedOwnerSid $script:CurrentSid -MaximumBytes 1048576
    $null = Assert-PrivateFile -Path $TokenFile -ExpectedOwnerSid $script:CurrentSid -MaximumBytes 65536
    Copy-PrivateFile -Source $ConfigFile -Destination (Join-Path $script:DownloadDirectory "daemon.json")
    Copy-PrivateFile -Source $TokenFile -Destination (Join-Path $script:DownloadDirectory "daemon.token")
    $stagedConfig = Join-Path $script:DownloadDirectory "daemon.json"
    $node = Join-Path $script:StagedLib "runtime\code-server\lib\node.exe"
    Assert-ConfigEnvelope -NodeExecutable $node -ConfigPath $stagedConfig
    Invoke-CheckConfig -ConfigPath $stagedConfig -StagedLib $script:StagedLib
}

function New-ManagementCopy {
    param([Parameter(Mandatory = $true)][string] $SourceRoot)
    $directory = Join-Path ([IO.Path]::GetTempPath()) (
        "kk-studio-managed-" + [Guid]::NewGuid().ToString("N"))
    New-PrivateDirectory -Path $directory
    try {
        Copy-Item -LiteralPath (Join-Path $SourceRoot "runtime\code-server\lib\node.exe") `
            -Destination (Join-Path $directory "node.exe")
        Copy-Item -LiteralPath (Join-Path $SourceRoot "package.json") `
            -Destination (Join-Path $directory "package.json")
        Copy-Item -LiteralPath (Join-Path $SourceRoot "delay.js") `
            -Destination (Join-Path $directory "delay.js")
        New-PrivateDirectory -Path (Join-Path $directory "native")
        Copy-Item -Path (Join-Path $SourceRoot "native\*.js") `
            -Destination (Join-Path $directory "native")
        New-PrivateDirectory -Path (Join-Path $directory "node_modules\koffi")
        Copy-Item -Path (Join-Path $SourceRoot "node_modules\koffi\*") `
            -Destination (Join-Path $directory "node_modules\koffi") -Recurse
        New-PrivateDirectory -Path (Join-Path $directory "node_modules\@koromix\koffi-win32-x64")
        Copy-Item -Path (Join-Path $SourceRoot "node_modules\@koromix\koffi-win32-x64\*") `
            -Destination (Join-Path $directory "node_modules\@koromix\koffi-win32-x64") -Recurse
        if (-not (Test-Path -LiteralPath (Join-Path $directory "native\lock-helper.js") -PathType Leaf) -or
            -not (Test-Path -LiteralPath (Join-Path $directory "node.exe") -PathType Leaf)) {
            Throw-Failure "独立管理副本不完整。"
        }
        $script:ManagementDirectory = $directory
        return $directory
    }
    catch {
        if (Test-Path -LiteralPath $directory) {
            Remove-Item -LiteralPath $directory -Recurse -Force
        }
        throw
    }
}

function Start-InstallLockHelper {
    param([Parameter(Mandatory = $true)][string] $HelperRoot)
    $node = Join-Path $HelperRoot "node.exe"
    $helper = Join-Path $HelperRoot "native\lock-helper.js"
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $node
    $startInfo.Arguments = ConvertTo-WindowsCommandLine -Arguments @(
        $helper, "hold", $script:EnvironmentRoot)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            Throw-Failure "无法启动安装操作锁 helper。"
        }
        $ready = $process.StandardOutput.ReadLineAsync()
        if (-not $ready.Wait(60000)) {
            Throw-Failure "安装操作锁 helper 没有响应；本次未改动任何受管文件。"
        }
        $line = $ready.Result
        # An immediately failing helper closes stdout without printing; wait briefly so the
        # stable busy/failure exit code can be mapped instead of reporting a protocol error.
        if ($null -eq $line) { [void] $process.WaitForExit(5000) }
        if ($process.HasExited) {
            $code = $process.ExitCode
            if ($code -eq 75) {
                Throw-Failure "该环境的另一个安装操作正在执行；本次未改动任何受管文件。"
            }
            Throw-Failure "无法取得安装操作锁；本次未改动任何受管文件。"
        }
        if ($line -cne "locked") {
            Throw-Failure "无法取得安装操作锁；本次未改动任何受管文件。"
        }
        $script:HelperProcess = $process
        $script:HelperHeld = $true
    }
    catch {
        if (-not $process.HasExited) {
            try { $process.Kill() } catch { }
        }
        $process.Dispose()
        throw
    }
}

function Stop-InstallLockHelper {
    if ($null -eq $script:HelperProcess) {
        return
    }
    $process = $script:HelperProcess
    $script:HelperProcess = $null
    $script:HelperHeld = $false
    try {
        $process.StandardInput.Close()
        if (-not $process.WaitForExit($script:VerifyTimeoutSeconds * 1000)) {
            # 受管发布已完成；helper 未退出是清理问题，必须报告但不能假装已释放。
            try { $process.Kill() } catch { }
            [void] $process.WaitForExit(5000)
            [Console]::Error.WriteLine("安装操作锁 helper 未能正常退出；锁由进程退出释放。")
            $script:CleanupFailed = $true
        }
        elseif ($process.ExitCode -ne 0) {
            [Console]::Error.WriteLine("安装操作锁 helper 未能正常释放锁。")
            $script:CleanupFailed = $true
        }
    }
    finally {
        $process.Dispose()
    }
}

function Invoke-LockWait {
    param([Parameter(Mandatory = $true)][string] $HelperRoot)
    $node = Join-Path $HelperRoot "node.exe"
    $helper = Join-Path $HelperRoot "native\lock-helper.js"
    $result = Invoke-NativeProcess -Executable $node -Arguments @(
        $helper, "wait", $script:EnvironmentRoot, [string] $script:VerifyTimeoutSeconds)
    switch ($result.ExitCode) {
        0 { return }
        76 { Throw-Failure "服务停止后仍持有运行锁，未替换或删除任何受管文件。" }
        default { Throw-Failure "无法确认服务已释放运行锁，未替换或删除任何受管文件。" }
    }
}

function Write-LastOperation {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("succeeded", "failed")][string] $Result,
        [AllowNull()][string] $ErrorCode
    )
    if (-not $script:HelperHeld) { return $false }
    if (-not (Test-Path -LiteralPath $script:EnvironmentRoot -PathType Container)) { return $false }
    $targetVersion = $null
    if (-not [string]::IsNullOrEmpty($script:TargetVersion)) {
        $targetVersion = $script:TargetVersion
    }
    $record = [ordered] @{
        operationId = $script:OperationId
        type = $Command
        targetVersion = $targetVersion
        phase = $script:PhaseCode
        result = $Result
        time = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        errorCode = $ErrorCode
    }
    $json = ($record | ConvertTo-Json -Compress)
    if ($json.Length -gt 4096) {
        [Console]::Error.WriteLine("无法写入本机诊断记录：记录超出上限。")
        return $false
    }
    $temporary = Join-Path $script:EnvironmentRoot (
        ".last-operation.json.tmp." + [Guid]::NewGuid().ToString("N"))
    try {
        $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew)
        $stream.Dispose()
        Set-Acl -LiteralPath $temporary -AclObject (New-PrivateAcl)
        [IO.File]::WriteAllText($temporary, $json + "`n", [Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $script:LastOperation) {
            $null = Assert-PrivateFile -Path $script:LastOperation -ExpectedOwnerSid $script:CurrentSid
            [IO.File]::Replace($temporary, $script:LastOperation, [NullString]::Value)
        }
        else {
            [IO.File]::Move($temporary, $script:LastOperation)
        }
        return $true
    }
    catch {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
        [Console]::Error.WriteLine("无法写入本机诊断记录：$($script:LastOperation)")
        return $false
    }
}

function Remove-ManagedTemporaryDirectory {
    param([AllowNull()][string] $Path)
    if ([string]::IsNullOrEmpty($Path) -or -not (Test-Path -LiteralPath $Path)) { return }
    $root = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if ($full -eq $root -or -not $full.StartsWith($root + "\", [StringComparison]::OrdinalIgnoreCase)) {
        [Console]::Error.WriteLine("拒绝删除非临时目录：$Path")
        $script:CleanupFailed = $true
        return
    }
    try {
        Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop
    }
    catch {
        [Console]::Error.WriteLine("无法清理临时目录：$full")
        $script:CleanupFailed = $true
    }
}

function Stage-WorkProgram {
    if (Test-Path -LiteralPath $script:WorkDirectory) {
        Assert-PrivateDirectory -Path $script:WorkDirectory
        Remove-Item -LiteralPath $script:WorkDirectory -Recurse -Force
    }
    New-PrivateDirectory -Path $script:WorkDirectory
    $archive = Join-Path $script:WorkDirectory "archive.tar.gz"
    [IO.File]::Copy($script:DownloadedArchive, $archive, $true)
    $lib = Join-Path $script:WorkDirectory "lib"
    New-PrivateDirectory -Path $lib
    $tar = Get-Command "tar.exe" -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $tar) {
        Throw-Failure "tar.exe is required to unpack the daemon release archive"
    }
    # Windows bsdtar receives narrow argv. Never pass the Unicode/spaced user-profile archive or
    # destination paths here; make the working directory native and use ASCII relative arguments.
    $unpack = Invoke-NativeProcess -Executable $tar.Source `
        -Arguments @("-xzf", "archive.tar.gz", "-C", "lib") -WorkingDirectory $script:WorkDirectory
    if ($unpack.ExitCode -ne 0) {
        Throw-Failure "无法在卡片暂存区解包发行归档（exit code $($unpack.ExitCode)）。"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $lib "main.js") -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $lib "runtime\code-server\lib\node.exe") -PathType Leaf)) {
        Throw-Failure "暂存程序不完整。"
    }
    return $lib
}

function Publish-Program {
    param([Parameter(Mandatory = $true)][string] $StagedLib)
    $script:Published = $true
    if (Test-Path -LiteralPath $script:InstalledLib) {
        Assert-PrivateDirectory -Path $script:InstalledLib
        Remove-Item -LiteralPath $script:InstalledLib -Recurse -Force
    }
    Move-Item -LiteralPath $StagedLib -Destination $script:InstalledLib
    Set-Acl -LiteralPath $script:InstalledLib -AclObject (New-PrivateAcl -Directory)
}

function Stop-DaemonTaskAndWait {
    param([Parameter(Mandatory = $true)][string] $HelperRoot)
    Stop-DaemonTaskIfRunning -Task (Find-DaemonTask)
    $script:PhaseCode = "wait-lock"
    Invoke-LockWait -HelperRoot $HelperRoot
}

function Invoke-InstallCommon {
    param([Parameter(Mandatory = $true)][ValidateSet("install", "update")][string] $Operation)
    Initialize-HostContext
    Assert-Version -Value $Version
    if ($Operation -eq "install") {
        Assert-OptionSet -ForCommand "install" -Allowed $script:InstallParameterNames
        Resolve-InstallInputs
        Assert-EarlyEnvelope -ConfigPath $ConfigFile
    }
    else {
        Assert-OptionSet -ForCommand "update" -Allowed $script:UpdateParameterNames
        if (-not (Test-Path -LiteralPath $script:EnvironmentRoot -PathType Container)) {
            Throw-Failure "未安装：update 只能更新已有的受管安装。"
        }
        if (-not (Test-Path -LiteralPath $script:InstalledLib -PathType Container)) {
            Throw-Failure "未安装：缺少受管程序目录。"
        }
        foreach ($file in @($script:InstalledConfig, $script:InstalledToken)) {
            if (-not (Test-Path -LiteralPath $file)) {
                Throw-Failure "缺少当前环境配置或凭据；update 不会创建它们。"
            }
            $null = Assert-PrivateFile -Path $file -ExpectedOwnerSid $script:CurrentSid
        }
        Assert-EarlyEnvelope -ConfigPath $script:InstalledConfig
    }

    $existing = Find-DaemonTask
    if ($null -ne $existing) { Assert-ManagedTask -Task $existing }
    Assert-ManagedLayout -HasTask ($null -ne $existing)

    $script:Phase = "2/7 解析版本"
    $script:PhaseCode = "version"
    Get-ReleaseArchive
    $script:Phase = "3/7 下载归档"
    $script:PhaseCode = "download"
    Assert-DownloadedArchive
    $script:Phase = "4/7 校验解包配置"
    $script:PhaseCode = "verify"
    if ($Operation -eq "install") {
        Prepare-StagedArchive
    }
    else {
        Assert-ConfigEnvelope -NodeExecutable (Join-Path $script:StagedLib "runtime\code-server\lib\node.exe") `
            -ConfigPath $script:InstalledConfig
    }
    $helperRoot = New-ManagementCopy -SourceRoot $script:StagedLib
    $definition = New-DaemonScheduledTaskDefinition `
        -NodeExecutable $script:InstalledNode -DaemonArguments (New-DaemonArgumentList) `
        -WorkingDirectory $script:InstalledLib `
        -UserSid $script:CurrentSid -Description $script:TaskDescription

    # Recheck ownership and target safety after all potentially long-running preflight work.
    $existing = Find-DaemonTask
    if ($null -ne $existing) { Assert-ManagedTask -Task $existing }
    Assert-ManagedLayout -HasTask ($null -ne $existing)

    $script:Phase = "5/7 取得操作锁"
    $script:PhaseCode = "lock"
    # The native lock helper only protects its final directory; create and verify the whole managed
    # directory chain before it so existing inherited parents are rejected and first install has
    # private ACLs. .work and service transitions still happen only after the lock is held.
    foreach ($directory in @($script:InstallRoot, $script:EnvironmentsRoot, $script:EnvironmentRoot)) {
        New-PrivateDirectory -Path $directory
    }
    Start-InstallLockHelper -HelperRoot $helperRoot
    $script:WorkOwned = $true
    Assert-ManagedLayout -HasTask ($null -ne (Find-DaemonTask))

    $script:Phase = "6/7 暂存并停止发布启动"
    $script:PhaseCode = "stage"
    $stagedLib = Stage-WorkProgram
    # Staging and its expected entries are verified before the old task is stopped. A preparation
    # failure must therefore leave the running service, config, token and old program untouched.
    $script:PhaseCode = "stop"
    Stop-DaemonTaskAndWait -HelperRoot $helperRoot

    # Final checks after the service is stopped: install validates the exact snapshot that will be
    # published; update re-validates the current configuration against the new program.
    $script:PhaseCode = "validate"
    if ($Operation -eq "install") {
        $stagedConfig = Join-Path $script:DownloadDirectory "daemon.json"
        Assert-ConfigEnvelope -NodeExecutable (Join-Path $stagedLib "runtime\code-server\lib\node.exe") `
            -ConfigPath $stagedConfig
        Invoke-CheckConfig -ConfigPath $stagedConfig -StagedLib $stagedLib
    }
    else {
        Assert-ConfigEnvelope -NodeExecutable (Join-Path $stagedLib "runtime\code-server\lib\node.exe") `
            -ConfigPath $script:InstalledConfig
        Invoke-CheckConfig -ConfigPath $script:InstalledConfig -StagedLib $stagedLib
    }

    $script:PhaseCode = "publish"
    Publish-Program -StagedLib $stagedLib
    if ($Operation -eq "install") {
        Publish-PrivateFile -Source (Join-Path $script:DownloadDirectory "daemon.json") `
            -Destination $script:InstalledConfig
        Publish-PrivateFile -Source (Join-Path $script:DownloadDirectory "daemon.token") `
            -Destination $script:InstalledToken
    }
    $existing = Find-DaemonTask
    if ($null -ne $existing) { Assert-ManagedTask -Task $existing }
    Register-ScheduledTask -TaskName $script:TaskName -TaskPath "\" -InputObject $definition -Force | Out-Null
    $script:Phase = "7/7 稳定性验证"
    $script:PhaseCode = "start"
    Start-AndVerifyDaemonTask
    $script:PhaseCode = "done"
}

function Invoke-Install {
    Assert-OptionSet -ForCommand "install" -Allowed $script:InstallParameterNames
    Invoke-InstallCommon -Operation "install"
    Write-Host "KK Studio 环境安装完成，服务已启动。"
    Write-Host ""
    Write-Host "请返回 Studio，确认环境状态为 READY。"
    return 0
}

function Invoke-Update {
    Assert-OptionSet -ForCommand "update" -Allowed $script:UpdateParameterNames
    Invoke-InstallCommon -Operation "update"
    Write-Host "KK Studio 环境更新完成：程序/任务定义已替换；配置与凭据未改动。"
    Write-Host ""
    Write-Host "请返回 Studio，确认环境状态为 READY。"
    return 0
}

function Invoke-Status {
    Assert-OptionSet -ForCommand "status" -Allowed @()
    Initialize-HostContext
    $task = Find-DaemonTask
    if ($null -eq $task) {
        [Console]::Error.WriteLine(
            "未安装：找不到受管计划任务 $($script:TaskName)"
        )
        return 1
    }
    try {
        Assert-ManagedTask -Task $task
    }
    catch {
        [Console]::Error.WriteLine($_.Exception.Message)
        return 1
    }

    $info = Get-ScheduledTaskInfo -TaskName $script:TaskName -TaskPath "\"
    $task | Select-Object TaskName, State, Description | Format-List | Out-Host
    $info | Select-Object LastRunTime, LastTaskResult, NextRunTime |
        Format-List | Out-Host
    if (Test-Path -LiteralPath $script:LastOperation -PathType Leaf) {
        $null = Assert-PrivateFile -Path $script:LastOperation -ExpectedOwnerSid $script:CurrentSid
        Get-Content -LiteralPath $script:LastOperation
    }
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
    Assert-OptionSet -ForCommand "uninstall" -Allowed @()
    Initialize-HostContext
    if (-not (Test-Path -LiteralPath $script:EnvironmentRoot -PathType Container)) {
        Write-Host "KK Studio 环境未安装受管服务。配置、凭据与用户数据均已保留。"
        return 0
    }
    $task = Find-DaemonTask
    $hasLib = Test-Path -LiteralPath $script:InstalledLib -PathType Container
    # 只有配置/数据而没有任务或程序时无事可做；任务已被移除但 lib 仍残留时仍要完成清理。
    if ($null -eq $task -and -not $hasLib) {
        Write-Host "KK Studio 环境未安装受管服务。配置、凭据与用户数据均已保留。"
        return 0
    }
    if ($null -ne $task) {
        Assert-ManagedTask -Task $task
        Assert-ManagedLayout -HasTask $true
    }
    else {
        # lib 残留没有任务可核对，但仍必须是本卡片私有目录布局；绝不删除未知路径。
        Assert-ManagedLayout -HasTask $true
    }
    if (-not $hasLib) {
        Throw-Failure "找不到当前安装程序，无法取得独立管理副本；未删除任何文件。" -Action "卸载"
    }
    # 从当前安装取得独立管理副本后再删除 lib；helper 不加载即将删除的模块。
    $helperRoot = New-ManagementCopy -SourceRoot $script:InstalledLib
    $script:Phase = "2/3 取得操作锁"
    $script:PhaseCode = "lock"
    Start-InstallLockHelper -HelperRoot $helperRoot
    $script:WorkOwned = $true
    $script:Phase = "3/3 停止并移除服务"
    $script:PhaseCode = "stop"
    Stop-DaemonTaskAndWait -HelperRoot $helperRoot
    $script:PhaseCode = "publish"
    if ($null -ne (Find-DaemonTask)) {
        Unregister-ScheduledTask -TaskName $script:TaskName -TaskPath "\" -Confirm:$false
    }
    Remove-Item -LiteralPath $script:InstalledLib -Recurse -Force
    $script:PhaseCode = "done"
    Write-Host "KK Studio 环境已卸载，受管服务和程序已移除。"
    Write-Host ""
    Write-Host "配置、凭据、技能与用户数据已保留。"
    return 0
}

function Remove-WorkDirectory {
    if (-not (Test-Path -LiteralPath $script:WorkDirectory)) { return }
    try {
        Assert-PrivateDirectory -Path $script:WorkDirectory
        Remove-Item -LiteralPath $script:WorkDirectory -Recurse -Force -ErrorAction Stop
    }
    catch {
        # 清理失败必须被看见：成功结果不能建立在未清理的受管暂存之上。
        $script:CleanupFailed = $true
        [Console]::Error.WriteLine("受管暂存未能完全清理：$($script:WorkDirectory)")
    }
}

function Invoke-Main {
    $code = 0
    $failure = $null
    try {
        switch ($Command) {
            "install" { $code = Invoke-Install }
            "update" { $code = Invoke-Update }
            "status" { $code = Invoke-Status }
            "uninstall" { $code = Invoke-Uninstall }
            "help" {
                Assert-OptionSet -ForCommand "help" -Allowed @()
                Write-Usage
                $code = 0
            }
        }
        if ($script:HelperHeld) {
            # 先清理受管暂存，再写成功结果；任何真实清理或记录错误都不能宣称成功。
            Remove-WorkDirectory
            if ($script:CleanupFailed) {
                [void] (Write-LastOperation -Result "failed" -ErrorCode "cleanup")
                [Console]::Error.WriteLine("受管暂存未能完全清理；未自动回滚。")
                $code = 1
            }
            elseif (-not (Write-LastOperation -Result "succeeded" -ErrorCode $null)) {
                $code = 1
            }
        }
    }
    catch {
        $failure = $_.Exception.Message
        if ($script:HelperHeld) {
            [void] (Write-LastOperation -Result "failed" -ErrorCode $script:PhaseCode)
            # 保留真实锁状态：finally 仍要清理本次暂存并释放 helper。
            if ($script:Published) {
                $failure += "`n受管程序或服务已开始替换；未自动回滚、未保留备份目录。"
            }
            elseif ($script:StopAttempted) {
                $failure += "`n受管程序尚未替换；已尝试停止服务，请检查服务状态。"
            }
        }
    }
    finally {
        # 只有本次真正持有操作锁时才清理 .work；status/help 绝不动卡片文件。
        if ($script:WorkOwned) { Remove-WorkDirectory }
        $script:WorkOwned = $false
        Stop-InstallLockHelper
        Remove-ManagedTemporaryDirectory -Path $script:ManagementDirectory
        Remove-ManagedTemporaryDirectory -Path $script:DownloadDirectory
        if ($script:CleanupFailed) {
            [Console]::Error.WriteLine("受管临时文件未能完全清理。")
            if ($null -eq $failure -and $code -eq 0) { $code = 1 }
        }
    }
    if ($null -ne $failure) {
        throw [System.InvalidOperationException]::new($failure)
    }
    return $code
}

if ($MyInvocation.InvocationName -ne ".") {
    try {
        $exitCode = Invoke-Main
        exit $exitCode
    }
    catch {
        [Console]::Error.WriteLine($_.Exception.Message)
        exit 1
    }
}
