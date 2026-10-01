#Requires -Version 5.1
<#
Native, non-mutating Windows contracts for scripts/daemon/install.ps1.

The suite dot-sources production helpers, constructs a ScheduledTasks definition without
registering it, and sends serialized arguments through CreateProcess -> JDK 21 -> Java argv[].
#>

[CmdletBinding()]
param(
    # Portable evidence only: this does not substitute for either Windows CI host.
    [switch] $ProcessOnly
)

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

function Test-JavaProcessCapture {
    # Use production capture even for the preflight: PS 5.1 must not reinterpret stderr.
    $javaName = if ($env:OS -eq "Windows_NT") { "java.exe" } else { "java" }
    $java = Get-Command $javaName -CommandType Application `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $java) {
        throw "native process tests require JDK 21 on PATH"
    }
    $version = Invoke-WithoutJavaOptionEnvironment {
        Invoke-NativeProcess -Executable $java.Source -Arguments @("-version")
    }
    Assert-Equal -Expected 0 -Actual $version.ExitCode -Message "java -version succeeds"
    Assert-Equal -Expected "" -Actual $version.Stdout -Message "java version stdout is empty"
    Assert-True -Condition ($version.Stderr -match 'version\s+"21(?:[.\-+][^"]*)?"') `
        -Message "successful Java 21 version is captured on stderr"
    if ($env:OS -eq "Windows_NT") {
        $javaHome = Split-Path -Parent (Split-Path -Parent $java.Source)
        Assert-Equal -Expected $javaHome -Actual (Assert-Jdk21 -Candidate $javaHome) `
            -Message "production JDK gate accepts stderr version output"
    }

    $tempDirectory = Join-Path ([IO.Path]::GetTempPath()) (
        "kk-studio-daemon-argv-$([Guid]::NewGuid().ToString('N'))"
    )
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    try {
        $source = Join-Path $tempDirectory "ProcessProbe.java"
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot "resources/ProcessProbe.java") `
            -Destination $source

        $expected = @(
            "",
            "plain",
            "two words",
            # Code points preserve genuine Chinese even with PS 5.1's BOM-less script decoding.
            [string]::new([char[]] @(0x4E2D, 0x6587, 0x53C2, 0x6570)),
            'embedded"quote',
            'C:\Program Files\Java\',
            'two\\slashes',
            'slashes\\"before-quote',
            "ampersand&pipe|percent%caret^"
        )
        $result = Invoke-WithoutJavaOptionEnvironment {
            Invoke-NativeProcess -Executable $java.Source `
                -Arguments (@($source, "echo") + $expected)
        }
        Assert-Equal -Expected 0 -Actual $result.ExitCode -Message "java argv probe succeeds"
        Assert-Equal -Expected "" -Actual $result.Stderr -Message "argv probe stderr is separate"
        $withoutFinalNewline = $result.Stdout.TrimEnd([char[]] @("`r", "`n"))
        $encoded = [regex]::Split($withoutFinalNewline, "\r?\n")
        $actual = @(
            foreach ($line in $encoded) {
                [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($line))
            }
        )
        Assert-SequenceEqual -Expected $expected -Actual $actual `
            -Message "CreateProcess to JDK argv round-trip"

        $failed = Invoke-WithoutJavaOptionEnvironment {
            Invoke-NativeProcess -Executable $java.Source -Arguments @($source, "fail")
        }
        Assert-Equal -Expected 23 -Actual $failed.ExitCode -Message "native nonzero exit is observable"
        Assert-Equal -Expected "stdout before failure" -Actual $failed.Stdout `
            -Message "failure retains stdout"
        Assert-Equal -Expected "stderr before failure" -Actual $failed.Stderr `
            -Message "failure retains stderr without a PowerShell exception"

        $flood = Invoke-WithoutJavaOptionEnvironment {
            Invoke-NativeProcess -Executable $java.Source -Arguments @($source, "flood")
        }
        Assert-Equal -Expected 0 -Actual $flood.ExitCode -Message "both full pipes complete"
        Assert-Equal -Expected ("o" * (256 * 8192)) -Actual $flood.Stdout `
            -Message "large stdout is complete"
        Assert-Equal -Expected ("e" * (256 * 8192)) -Actual $flood.Stderr `
            -Message "large stderr is complete"

        Test-JarVersionGate -Java $java.Source -Source $source -Directory $tempDirectory
    }
    finally {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force `
            -ErrorAction SilentlyContinue
    }
}

function Test-JarVersionGate {
    param([string] $Java, [string] $Source, [string] $Directory)
    # Real JARs prove stderr cannot satisfy the stdout identity check or hide a nonzero exit.
    $bin = Split-Path -Parent $Java
    $suffix = if ($env:OS -eq "Windows_NT") { ".exe" } else { "" }
    $compiled = Invoke-WithoutJavaOptionEnvironment {
        Invoke-NativeProcess -Executable (Join-Path $bin "javac$suffix") `
            -Arguments @("-d", $Directory, $Source)
    }
    Assert-Equal -Expected 0 -Actual $compiled.ExitCode -Message "compile process fixture"
    $savedJava = $script:SelectedJava
    $savedJar = $script:BuiltJar
    $names = @("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")
    $savedOptions = @{}
    try {
        $script:SelectedJava = $Java
        foreach ($name in $names) {
            $savedOptions[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
            [Environment]::SetEnvironmentVariable($name, "-not-a-valid-java-option", "Process")
        }
        foreach ($mode in @("success", "fail", "stderr-only", "wrong-stdout")) {
            [IO.File]::WriteAllText((Join-Path $Directory "version-mode.txt"), $mode)
            $script:BuiltJar = Join-Path $Directory "$mode.jar"
            $packed = Invoke-WithoutJavaOptionEnvironment {
                Invoke-NativeProcess -Executable (Join-Path $bin "jar$suffix") -Arguments @(
                    "--create", "--file", $script:BuiltJar, "--main-class", "ProcessProbe",
                    "-C", $Directory, "ProcessProbe.class", "-C", $Directory, "version-mode.txt"
                )
            }
            Assert-Equal -Expected 0 -Actual $packed.ExitCode -Message "package $mode fixture"
            if ($mode -eq "success") {
                Assert-BuiltJar
            }
            elseif ($mode -eq "fail") {
                Assert-Throws -Action { Assert-BuiltJar } -ExpectedMessage "exit code 23" `
                    -Message "valid stdout cannot hide a failing JAR"
            }
            else {
                Assert-Throws -Action { Assert-BuiltJar } -ExpectedMessage "unexpected daemon" `
                    -Message "$mode cannot satisfy the stdout identity gate"
            }
            foreach ($name in $names) {
                Assert-Equal -Expected "-not-a-valid-java-option" `
                    -Actual ([Environment]::GetEnvironmentVariable($name, "Process")) `
                    -Message "$name restored after $mode check"
            }
        }
    }
    finally {
        $script:SelectedJava = $savedJava
        $script:BuiltJar = $savedJar
        foreach ($name in $savedOptions.Keys) {
            $value = if ($null -eq $savedOptions[$name]) {
                [NullString]::Value
            } else {
                $savedOptions[$name]
            }
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

function Test-JdkVersionGate {
    # Isolate process outcomes from host/path lookup; real Windows JDK execution is tested above.
    function Assert-AbsoluteWindowsPath { param($Value, $Name) }
    function Invoke-NativeProcess {
        param($Executable, $Arguments)
        Assert-SequenceEqual -Expected @("-version") -Actual $Arguments `
            -Message "JDK gate requests the native version probe"
        return $probeResult
    }
    $directory = Join-Path ([IO.Path]::GetTempPath()) (
        "kk-studio-jdk-gate-$([Guid]::NewGuid().ToString('N'))"
    )
    New-Item -ItemType Directory -Path (Join-Path $directory "bin") | Out-Null
    try {
        foreach ($name in @("java.exe", "javac.exe")) {
            [IO.File]::WriteAllText((Join-Path $directory "bin/$name"), "fixture")
        }
        $probeResult = [pscustomobject] @{
            Stdout = ""
            Stderr = 'openjdk version "21.0.12.1"'
            ExitCode = 0
        }
        Assert-Equal -Expected $directory -Actual (Assert-Jdk21 -Candidate $directory) `
            -Message "JDK gate accepts stderr with a successful exit"
        $probeResult.Stderr = 'openjdk version "17.0.1"'
        Assert-Throws -Action { Assert-Jdk21 -Candidate $directory } `
            -ExpectedMessage "JDK 21 is required" -Message "other major versions are rejected"
        $probeResult.Stdout = 'openjdk version "21.0.12.1"'
        $probeResult.Stderr = "native failure"
        $probeResult.ExitCode = 23
        Assert-Throws -Action { Assert-Jdk21 -Candidate $directory } `
            -ExpectedMessage "exit code 23" -Message "a valid version cannot hide native failure"
    }
    finally {
        Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
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

    Assert-Throws -Action {
        Assert-LspConfigFile -Path "relative\config.json"
    } -ExpectedMessage "absolute drive or UNC" -Message "relative lsp-config is rejected"
    Assert-Throws -Action {
        Assert-LspConfigFile -Path "C:\path\~dir\config.json"
    } -ExpectedMessage "~" -Message "tilde in lsp-config is rejected"
    Assert-Throws -Action {
        Assert-LspConfigFile -Path "C:\path\%VAR%\config.json"
    } -ExpectedMessage "environment-variable placeholders" -Message "percent placeholder is rejected"
    Assert-Throws -Action {
        Assert-LspConfigFile -Path "C:\path\`$VAR\config.json"
    } -ExpectedMessage "environment-variable placeholders" -Message "dollar placeholder is rejected"
    Assert-Throws -Action {
        Assert-LspConfigFile -Path "C:\nonexistent\config.json"
    } -ExpectedMessage "existing readable regular file" -Message "missing lsp-config is rejected"

    $tempLspConfig = [IO.Path]::GetTempFileName()
    try {
        $validated = Assert-LspConfigFile -Path $tempLspConfig
        Assert-Equal -Expected (Get-Item -LiteralPath $tempLspConfig).FullName `
            -Actual $validated -Message "valid lsp config file is accepted"
    }
    finally {
        Remove-Item -LiteralPath $tempLspConfig -Force -ErrorAction SilentlyContinue
    }
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
        "--lsp-config",
        "C:\Fixture Root\lsp-config.json"
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
    Test-JavaProcessCapture
    Test-JdkVersionGate
    if (-not $ProcessOnly) {
        Test-RegistrationTokenAcl
        Test-MissingScheduledTaskLookup
        Test-PureValidation
        Test-ScheduledTaskDefinition
    }
    $scope = if ($ProcessOnly) { "Portable process" } else { "Windows daemon installer native" }
    Write-Host "$scope contracts passed ($script:Assertions assertions)."
    exit 0
}
catch {
    [Console]::Error.WriteLine($_.Exception.ToString())
    exit 1
}
