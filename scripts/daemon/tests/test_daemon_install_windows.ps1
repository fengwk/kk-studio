#Requires -Version 5.1
<#
Native, non-mutating Windows contracts for scripts/daemon/install.ps1.

The suite dot-sources production helpers, constructs a ScheduledTasks definition without
registering it, and sends serialized arguments through CreateProcess -> JDK 21 -> Java argv[].
#>

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$installer = Join-Path (Split-Path -Parent $PSScriptRoot) "install.ps1"
. $installer

$script:Assertions = 0

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][bool] $Condition,
        [Parameter(Mandatory = $true)][string] $Message
    )
    $script:Assertions++
    if (-not $Condition) {
        throw "assertion failed: $Message"
    }
}

function Assert-Equal {
    param(
        [AllowNull()] $Expected,
        [AllowNull()] $Actual,
        [Parameter(Mandatory = $true)][string] $Message
    )
    $script:Assertions++
    if ($Expected -ne $Actual) {
        throw (
            "assertion failed: $Message`n" +
            "expected: <$Expected>`nactual:   <$Actual>"
        )
    }
}

function Assert-SequenceEqual {
    param(
        [Parameter(Mandatory = $true)][object[]] $Expected,
        [Parameter(Mandatory = $true)][object[]] $Actual,
        [Parameter(Mandatory = $true)][string] $Message
    )
    Assert-Equal -Expected $Expected.Count -Actual $Actual.Count `
        -Message "$Message (length)"
    for ($index = 0; $index -lt $Expected.Count; $index++) {
        Assert-Equal -Expected $Expected[$index] -Actual $Actual[$index] `
            -Message "$Message (index $index)"
    }
}

function Assert-Throws {
    param(
        [Parameter(Mandatory = $true)][scriptblock] $Action,
        [Parameter(Mandatory = $true)][string] $ExpectedMessage,
        [Parameter(Mandatory = $true)][string] $Message
    )
    $script:Assertions++
    try {
        & $Action
    }
    catch {
        if ($_.Exception.Message -notlike "*$ExpectedMessage*") {
            throw (
                "assertion failed: $Message`n" +
                "expected error containing: <$ExpectedMessage>`n" +
                "actual: <$($_.Exception.Message)>"
            )
        }
        return
    }
    throw "assertion failed: $Message (no exception)"
}

function Test-QuotingGoldenCases {
    # These values exercise the exact backslash-before-quote and trailing-backslash rules.
    $cases = @(
        @("", '""'),
        @("plain", "plain"),
        @("two words", '"two words"'),
        @("中文", "中文"),
        @('a"b', '"a\"b"'),
        @('C:\Program Files\Java\', '"C:\Program Files\Java\\"'),
        @('before\\"after', '"before\\\\\"after"')
    )
    foreach ($case in $cases) {
        $actual = ConvertTo-WindowsCommandLineArgument -Argument $case[0]
        Assert-Equal -Expected $case[1] -Actual $actual `
            -Message "Windows quoting for $($case[0])"
    }
}

function Test-JavaArgumentRoundTrip {
    # ProcessStartInfo.Arguments becomes the one CreateProcess command line consumed by java.exe.
    $java = Get-Command "java.exe" -CommandType Application `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $java) {
        throw "native argv test requires JDK 21 java.exe on PATH"
    }
    $version = & $java.Source -version 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or
        $version -notmatch 'version\s+"21(?:[.\-+][^"]*)?"') {
        throw "native argv test requires JDK 21 java.exe"
    }

    $tempDirectory = Join-Path ([IO.Path]::GetTempPath()) (
        "kk-studio-daemon-argv-$([Guid]::NewGuid().ToString('N'))"
    )
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    try {
        $source = Join-Path $tempDirectory "ArgumentEcho.java"
        $sourceText = @'
import java.nio.charset.StandardCharsets;
import java.util.Base64;

class ArgumentEcho {
  public static void main(String[] args) {
    for (String arg : args) {
      System.out.println(
          Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
    }
  }
}
'@
        [IO.File]::WriteAllText(
            $source,
            $sourceText,
            [Text.UTF8Encoding]::new($false)
        )

        $expected = @(
            "",
            "plain",
            "two words",
            "中文参数",
            'embedded"quote',
            'C:\Program Files\Java\',
            'two\\slashes',
            'slashes\\"before-quote',
            "ampersand&pipe|percent%caret^"
        )
        $commandLine = ConvertTo-WindowsCommandLine -Arguments (@($source) + $expected)
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $java.Source
        $startInfo.Arguments = $commandLine
        $startInfo.WorkingDirectory = $tempDirectory
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true

        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        Assert-True -Condition $process.Start() -Message "java argv probe starts"
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        Assert-Equal -Expected 0 -Actual $process.ExitCode `
            -Message "java argv probe exits successfully: $stderr"

        $withoutFinalNewline = $stdout.TrimEnd([char[]] @("`r", "`n"))
        $encoded = [regex]::Split($withoutFinalNewline, "\r?\n")
        $actual = @(
            foreach ($line in $encoded) {
                [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($line))
            }
        )
        Assert-SequenceEqual -Expected $expected -Actual $actual `
            -Message "CreateProcess to JDK argv round-trip"
    }
    finally {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force `
            -ErrorAction SilentlyContinue
    }
}

function Set-OwnerOnlyReadAcl {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)]
        [System.Security.Principal.SecurityIdentifier] $Owner
    )
    $acl = [System.Security.AccessControl.FileSecurity]::new()
    $acl.SetOwner($Owner)
    $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule(
        [System.Security.AccessControl.FileSystemAccessRule]::new(
            $Owner,
            [System.Security.AccessControl.FileSystemRights]::Read,
            [System.Security.AccessControl.AccessControlType]::Allow
        )
    )
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Test-RegistrationTokenAcl {
    # The installer must prove private metadata without reading even the fixture bytes.
    $tempDirectory = Join-Path ([IO.Path]::GetTempPath()) (
        "kk-studio-daemon-acl-$([Guid]::NewGuid().ToString('N'))"
    )
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    try {
        $tokenPath = Join-Path $tempDirectory "daemon.token"
        [IO.File]::WriteAllText(
            $tokenPath,
            "public-test-fixture",
            [Text.UTF8Encoding]::new($false)
        )
        $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
        Set-OwnerOnlyReadAcl -Path $tokenPath -Owner $identity.User
        $validated = Assert-RegistrationTokenFile `
            -Path $tokenPath -ExpectedOwnerSid $identity.User.Value
        Assert-Equal -Expected (Get-Item -LiteralPath $tokenPath).FullName `
            -Actual $validated -Message "owner-only token ACL is accepted"

        $world = [System.Security.Principal.SecurityIdentifier]::new(
            [System.Security.Principal.WellKnownSidType]::WorldSid,
            $null
        )
        $acl = Get-Acl -LiteralPath $tokenPath
        $acl.AddAccessRule(
            [System.Security.AccessControl.FileSystemAccessRule]::new(
                $world,
                [System.Security.AccessControl.FileSystemRights]::Read,
                [System.Security.AccessControl.AccessControlType]::Allow
            )
        )
        Set-Acl -LiteralPath $tokenPath -AclObject $acl
        Assert-Throws -Action {
            Assert-RegistrationTokenFile `
                -Path $tokenPath -ExpectedOwnerSid $identity.User.Value
        } -ExpectedMessage "another SID" -Message "foreign Allow ACE is rejected"

        Set-OwnerOnlyReadAcl -Path $tokenPath -Owner $identity.User
        $acl = Get-Acl -LiteralPath $tokenPath
        $acl.AddAccessRule(
            [System.Security.AccessControl.FileSystemAccessRule]::new(
                $world,
                [System.Security.AccessControl.FileSystemRights]::Read,
                [System.Security.AccessControl.AccessControlType]::Deny
            )
        )
        Set-Acl -LiteralPath $tokenPath -AclObject $acl
        Assert-Throws -Action {
            Assert-RegistrationTokenFile `
                -Path $tokenPath -ExpectedOwnerSid $identity.User.Value
        } -ExpectedMessage "deny-read ACE" -Message "deny-read ACE is rejected"

        Set-OwnerOnlyReadAcl -Path $tokenPath -Owner $identity.User
        $acl = Get-Acl -LiteralPath $tokenPath
        $acl.SetAccessRuleProtection($false, $true)
        Set-Acl -LiteralPath $tokenPath -AclObject $acl
        Assert-Throws -Action {
            Assert-RegistrationTokenFile `
                -Path $tokenPath -ExpectedOwnerSid $identity.User.Value
        } -ExpectedMessage "inheritance must be disabled" `
            -Message "inherited ACL is rejected"
    }
    finally {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force `
            -ErrorAction SilentlyContinue
    }
}

function Test-MissingScheduledTaskLookup {
    # Query only: a fresh install needs an absent task to become $null, not a terminating error.
    Import-Module ScheduledTasks -ErrorAction Stop
    $savedTaskName = $script:TaskName
    try {
        $script:TaskName =
            "kk-studio-missing-$([Guid]::NewGuid().ToString('N'))"
        $task = Find-DaemonTask
        Assert-True -Condition ($null -eq $task) `
            -Message "missing Scheduled Task returns null"
    }
    finally {
        $script:TaskName = $savedTaskName
    }
}

function Test-PureValidation {
    Assert-GatewayUri -Value "wss://studio.example.invalid/api/harness/environment-daemon/v1"
    Assert-GatewayUri -Value "ws://127.0.0.1:8080/path?x=1&y=2"
    Assert-Throws -Action { Assert-GatewayUri -Value "https://example.invalid" } `
        -ExpectedMessage "ws or wss" -Message "HTTP gateway is rejected"
    Assert-Throws -Action { Assert-GatewayUri -Value "wss://" } `
        -ExpectedMessage "absolute ws/wss" -Message "hostless gateway is rejected"

    Assert-Note -Value "trusted host note"
    Assert-Throws -Action { Assert-Note -Value " padded" } `
        -ExpectedMessage "surrounding whitespace" -Message "padded note is rejected"
    Assert-Throws -Action { Assert-Note -Value ("x" * 513) } `
        -ExpectedMessage "512" -Message "long note is rejected"

    Assert-AbsoluteWindowsPath -Value "C:\Fixture\token.txt" -Name "fixture"
    Assert-AbsoluteWindowsPath -Value "\\server\share\token.txt" -Name "fixture"
    Assert-Throws -Action {
        Assert-AbsoluteWindowsPath -Value "relative\token.txt" -Name "fixture"
    } -ExpectedMessage "absolute drive or UNC" -Message "relative path is rejected"
    Assert-Throws -Action {
        Assert-NoControlCharacters -Value "line1`nline2" -Name "fixture"
    } -ExpectedMessage "control characters" -Message "newline is rejected"
}

function Test-ScheduledTaskDefinition {
    Import-Module ScheduledTasks -ErrorAction Stop
    $java = Get-Command "java.exe" -CommandType Application |
        Select-Object -First 1
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $description =
        "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$sid"
    $arguments = @(
        "-jar",
        "C:\Fixture Root\AppData\Local\kk-studio\daemon\kk-studio-daemon.jar",
        "--gateway-uri",
        "wss://studio.example.invalid/api/harness/environment-daemon/v1?x=1&y=2",
        "--registration-token-file",
        "C:\Fixture Root\.config\kk-studio\daemon token.txt",
        "--data-dir",
        "C:\Fixture Root\.kk-studio",
        "--bash-executable",
        "C:\Program Files\Git\bin\bash.exe",
        "--javap-executable",
        "C:\Program Files\Java\jdk-21\bin\javap.exe"
    )
    $workingDirectory = [Environment]::GetFolderPath(
        [Environment+SpecialFolder]::UserProfile
    )

    $definition = New-DaemonScheduledTaskDefinition `
        -JavaExecutable $java.Source `
        -DaemonArguments $arguments `
        -WorkingDirectory $workingDirectory `
        -UserSid $sid `
        -Description $description

    Assert-Equal -Expected 1 -Actual @($definition.Actions).Count `
        -Message "one direct action"
    $action = @($definition.Actions)[0]
    Assert-Equal -Expected $java.Source -Actual $action.Execute `
        -Message "action executes java.exe directly"
    Assert-Equal -Expected (ConvertTo-WindowsCommandLine -Arguments $arguments) `
        -Actual $action.Arguments -Message "action uses serialized logical argv"
    Assert-Equal -Expected $workingDirectory -Actual $action.WorkingDirectory `
        -Message "action working directory"

    Assert-Equal -Expected $sid -Actual $definition.Principal.UserId `
        -Message "principal belongs to current SID"
    Assert-True -Condition (
        [string] $definition.Principal.LogonType -in @("Interactive", "InteractiveToken", "3")
    ) -Message "principal uses interactive logon"
    Assert-True -Condition (
        [string] $definition.Principal.RunLevel -in @("Limited", "0")
    ) -Message "principal uses limited run level"

    Assert-Equal -Expected $description -Actual $definition.Description `
        -Message "exact ownership description"
    Assert-Equal -Expected 3 -Actual $definition.Settings.RestartCount `
        -Message "restart count"
    Assert-True -Condition (
        [string] $definition.Settings.RestartInterval -in @("PT1M", "00:01:00")
    ) -Message "one-minute restart interval"
    Assert-True -Condition (
        [string] $definition.Settings.ExecutionTimeLimit -in @("PT0S", "00:00:00")
    ) -Message "unbounded execution time"
    Assert-True -Condition (-not $definition.Settings.DisallowStartIfOnBatteries) `
        -Message "task may start on battery"
    Assert-True -Condition (-not $definition.Settings.StopIfGoingOnBatteries) `
        -Message "task remains running on battery"
    Assert-True -Condition (
        [string] $definition.Settings.MultipleInstances -in @("IgnoreNew", "2")
    ) -Message "new overlapping instances are ignored"

    Assert-Equal -Expected 1 -Actual @($definition.Triggers).Count `
        -Message "one logon trigger"
    $trigger = @($definition.Triggers)[0]
    Assert-Equal -Expected $sid -Actual $trigger.UserId `
        -Message "logon trigger is scoped to current SID"
}

try {
    Test-QuotingGoldenCases
    Test-JavaArgumentRoundTrip
    Test-RegistrationTokenAcl
    Test-MissingScheduledTaskLookup
    Test-PureValidation
    Test-ScheduledTaskDefinition
    Write-Host "Windows daemon installer native contracts passed ($script:Assertions assertions)."
    exit 0
}
catch {
    [Console]::Error.WriteLine($_.Exception.ToString())
    exit 1
}
