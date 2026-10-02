#Requires -Version 5.1
<#
Native, non-mutating Windows contracts for scripts/daemon/install.ps1.

The suite dot-sources production helpers, constructs a ScheduledTasks definition without
registering it, and sends arguments through CreateProcess -> JDK 21 -> Java argv[] -> the
production DaemonArguments decoder. The daemon arguments travel the way the installer
ships them: an ASCII relative -jar name and one Base64 token per application argument,
executed from a non-ASCII working directory. This file is deliberately ASCII-only because
Windows PowerShell 5.1 decodes BOM-less scripts with the ANSI code page.
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
$script:ProductionSourceRelativePath =
    "fun/fengwk/kkstudio/harness/daemon/DaemonArguments.java"

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

function New-TextFromCodePoints {
    # Non-ASCII expectations are built from code points because Windows PowerShell 5.1 would
    # otherwise decode a BOM-less script literal through the ANSI code page. The emoji case
    # is a UTF-16 surrogate pair, so UTF-8 encoding of it stays well defined.
    param(
        [Parameter(Mandatory = $true)]
        [int[]] $CodePoints
    )
    return [string]::new([char[]] $CodePoints)
}

function Resolve-ProductionSourceRoot {
    # The decoder under test is production code, so it is compiled from this checkout rather
    # than from a fixture copy that could drift away from the shipped contract.
    if (-not [string]::IsNullOrEmpty($env:KK_STUDIO_REPO_ROOT) -and
        (Test-Path -LiteralPath $env:KK_STUDIO_REPO_ROOT -PathType Container)) {
        return (Get-Item -LiteralPath $env:KK_STUDIO_REPO_ROOT).FullName
    }
    $candidate = Get-Item -LiteralPath $PSScriptRoot
    while ($null -ne $candidate) {
        if (Test-Path -LiteralPath (Join-Path $candidate.FullName ".git")) {
            return $candidate.FullName
        }
        $candidate = $candidate.Parent
    }
    throw "cannot locate the kk-studio repository root; set KK_STUDIO_REPO_ROOT"
}

function Get-ProductionDaemonArgumentsSource {
    $path = Join-Path (Resolve-ProductionSourceRoot) (
        "harness/daemon/src/main/java/$($script:ProductionSourceRelativePath)"
    )
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "production DaemonArguments source not found: $path"
    }
    # The probe imports this exact package, so a relocated class must fail loudly here.
    $package = "package " +
        ((Split-Path -Parent $script:ProductionSourceRelativePath) -replace "[\\/]", ".") +
        ";"
    $text = [IO.File]::ReadAllText($path)
    if ($text -notmatch [regex]::Escape($package)) {
        throw "production DaemonArguments must declare <$package> at $path"
    }
    return $path
}

function ConvertFrom-ProbeEcho {
    # The probe prints one Base64 token per decoded argument, which keeps the assertion
    # transport ASCII and independent of console code pages.
    param([Parameter(Mandatory = $true)][string] $Stdout)
    $lines = [regex]::Split(
        $Stdout.TrimEnd([char[]] @("`r", "`n")),
        "\r?\n"
    )
    return @(
        foreach ($line in $lines) {
            [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($line))
        }
    )
}

function Test-QuotingGoldenCases {
    # These values exercise the exact backslash-before-quote and trailing-backslash rules.
    $chinese = New-TextFromCodePoints -CodePoints 0x4E2D, 0x6587
    $cases = @(
        @("", '""'),
        @("plain", "plain"),
        @("two words", '"two words"'),
        @($chinese, $chinese),
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

function Get-RequiredJavaExecutable {
    $javaName = if ($env:OS -eq "Windows_NT") { "java.exe" } else { "java" }
    $java = Get-Command $javaName -CommandType Application `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $java) {
        throw "native process tests require JDK 21 on PATH"
    }
    return $java.Source
}

function Test-JavaVersionCapture {
    # Use production capture even for the preflight: PS 5.1 must not reinterpret stderr.
    $java = Get-RequiredJavaExecutable
    $version = Invoke-WithoutJavaOptionEnvironment {
        Invoke-NativeProcess -Executable $java -Arguments @("-version")
    }
    Assert-Equal -Expected 0 -Actual $version.ExitCode -Message "java -version succeeds"
    Assert-Equal -Expected "" -Actual $version.Stdout -Message "java version stdout is empty"
    Assert-True -Condition ($version.Stderr -match 'version\s+"21(?:[.\-+][^"]*)?"') `
        -Message "successful Java 21 version is captured on stderr"
    if ($env:OS -eq "Windows_NT") {
        $javaHome = Split-Path -Parent (Split-Path -Parent $java)
        Assert-Equal -Expected $javaHome -Actual (Assert-Jdk21 -Candidate $javaHome) `
            -Message "production JDK gate accepts stderr version output"
    }
}

function New-ProbeFixture {
    # Build the probe from the production decoder inside a non-ASCII directory that also
    # contains a space, so the run reproduces a real user profile path on Windows.
    param([Parameter(Mandatory = $true)][string] $Java)
    $bin = Split-Path -Parent $Java
    $suffix = if ($env:OS -eq "Windows_NT") { ".exe" } else { "" }
    $workspace = Join-Path ([IO.Path]::GetTempPath()) (
        "kk-studio $(New-TextFromCodePoints -CodePoints 0x5B88, 0x62A4) " +
        "probe $([Guid]::NewGuid().ToString('N'))"
    )
    New-Item -ItemType Directory -Path $workspace | Out-Null
    $packageDirectory = Split-Path -Parent $script:ProductionSourceRelativePath
    try {
        New-Item -ItemType Directory -Path (Join-Path $workspace $packageDirectory) `
            -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot "resources/ProcessProbe.java") `
            -Destination (Join-Path $workspace "ProcessProbe.java")
        Copy-Item -LiteralPath (Get-ProductionDaemonArgumentsSource) `
            -Destination (Join-Path $workspace $script:ProductionSourceRelativePath)
        [IO.File]::WriteAllText(
            (Join-Path $workspace "version-mode.txt"),
            "success"
        )

        # javac and jar receive relative ASCII arguments only; the non-ASCII path is the
        # working directory, which is exactly how the installer addresses the managed JAR.
        $compiled = Invoke-WithoutJavaOptionEnvironment {
            Invoke-NativeProcess -Executable (Join-Path $bin "javac$suffix") `
                -Arguments @("-d", ".", "ProcessProbe.java", $script:ProductionSourceRelativePath) `
                -WorkingDirectory $workspace
        }
        Assert-Equal -Expected 0 -Actual $compiled.ExitCode `
            -Message "compile the probe against production DaemonArguments ($($compiled.Stderr))"
        $packed = Invoke-WithoutJavaOptionEnvironment {
            Invoke-NativeProcess -Executable (Join-Path $bin "jar$suffix") -Arguments @(
                "--create", "--file", "probe.jar", "--main-class", "ProcessProbe",
                "-C", ".", "ProcessProbe.class", "-C", ".", $packageDirectory,
                "-C", ".", "version-mode.txt"
            ) -WorkingDirectory $workspace
        }
        Assert-Equal -Expected 0 -Actual $packed.ExitCode `
            -Message "package the probe JAR ($($packed.Stderr))"
        Assert-True -Condition (Test-Path -LiteralPath (Join-Path $workspace "probe.jar")) `
            -Message "probe JAR exists under the non-ASCII working directory"
    }
    catch {
        Remove-Item -LiteralPath $workspace -Recurse -Force -ErrorAction SilentlyContinue
        throw
    }
    return [pscustomobject] @{
        Java = $Java
        Jar = (Join-Path $bin "jar$suffix")
        Workspace = $workspace
        PackageDirectory = $packageDirectory
        ProbeJar = "probe.jar"
    }
}

function Test-NativeAsciiQuoting {
    # The generic Windows serializer stays the only quoting layer for plain ASCII argv, and
    # the decoder must leave unencoded arguments untouched.
    param([Parameter(Mandatory = $true)] $Fixture)
    $payload = @(
        "",
        "plain",
        "two words",
        'embedded"quote',
        "C:\Program Files\Java\",
        "two\\slashes",
        'slashes\\"before-quote',
        "ampersand&pipe|percent%caret^"
    )
    Assert-True -Condition ((($payload -join " ") -notmatch "[^\x00-\x7F]")) `
        -Message "the unencoded argv leg stays ASCII"
    $result = Invoke-WithoutJavaOptionEnvironment {
        Invoke-NativeProcess -Executable $Fixture.Java `
            -Arguments (@("-jar", $Fixture.ProbeJar, "echo") + $payload) `
            -WorkingDirectory $Fixture.Workspace
    }
    Assert-Equal -Expected 0 -Actual $result.ExitCode -Message "ASCII argv probe succeeds"
    Assert-Equal -Expected "" -Actual $result.Stderr -Message "argv probe stderr is separate"
    Assert-SequenceEqual -Expected $payload `
        -Actual @(ConvertFrom-ProbeEcho -Stdout $result.Stdout) `
        -Message "CreateProcess to JDK argv round-trip without encoding"
}

function Test-EncodedArgumentRoundTrip {
    # The installed task carries an ASCII command line: ASCII relative -jar name plus one
    # Base64 token per application argument, started from a non-ASCII working directory.
    param([Parameter(Mandatory = $true)] $Fixture)
    $payload = @(
        "",
        "plain",
        "two words",
        # Chinese and an emoji survive only because they are Base64 before Task Scheduler
        # ever stores the command line.
        (New-TextFromCodePoints -CodePoints 0x4E2D, 0x6587, 0x53C2, 0x6570),
        (New-TextFromCodePoints -CodePoints 0xD83D, 0xDE00),
        'embedded"quote',
        "trailing space ",
        "C:\Program Files\Java\",
        'two\\slashes',
        'slashes\\"before-quote',
        "ampersand&pipe|percent%caret^"
    )
    Assert-True -Condition ((($payload -join " ") -match "[^\x00-\x7F]") -and
        ($payload -contains "") -and
        ($payload -contains "C:\Program Files\Java\") -and
        ($payload -contains "trailing space ")) `
        -Message "the payload really contains non-ASCII, empty, and spaced values"
    $arguments = @("-jar", $Fixture.ProbeJar) +
        (ConvertTo-DaemonEncodedArguments -Arguments (@("echo") + $payload))
    $commandLine = ConvertTo-WindowsCommandLine -Arguments $arguments
    Assert-True -Condition ($commandLine -notmatch "[^\x20-\x7E]") `
        -Message "scheduled command line is printable ASCII only"
    # Only the empty value needs the "" marker to stay a token; nothing else is quoted.
    Assert-True -Condition (($commandLine -replace '""', "") -notmatch '"') `
        -Message "no Base64 token needs command line quoting"

    $result = Invoke-WithoutJavaOptionEnvironment {
        Invoke-NativeProcess -Executable $Fixture.Java -Arguments $arguments `
            -WorkingDirectory $Fixture.Workspace
    }
    Assert-Equal -Expected 0 -Actual $result.ExitCode `
        -Message "encoded probe succeeds ($($result.Stderr))"
    Assert-Equal -Expected "" -Actual $result.Stderr `
        -Message "encoded probe stderr is separate"
    Assert-SequenceEqual -Expected $payload `
        -Actual @(ConvertFrom-ProbeEcho -Stdout $result.Stdout) `
        -Message "Base64 tokens through java -jar and the production decoder"
}

function Test-NativeStreamCapture {
    # Stream separation, nonzero exit propagation, and pipe draining must survive the
    # working directory change; a sequential reader would deadlock on the flood case.
    param([Parameter(Mandatory = $true)] $Fixture)
    $failed = Invoke-WithoutJavaOptionEnvironment {
        Invoke-NativeProcess -Executable $Fixture.Java `
            -Arguments @("-jar", $Fixture.ProbeJar, "fail") `
            -WorkingDirectory $Fixture.Workspace
    }
    Assert-Equal -Expected 23 -Actual $failed.ExitCode -Message "native nonzero exit is observable"
    Assert-Equal -Expected "stdout before failure" -Actual $failed.Stdout `
        -Message "failure retains stdout"
    Assert-Equal -Expected "stderr before failure" -Actual $failed.Stderr `
        -Message "failure retains stderr without a PowerShell exception"

    $flood = Invoke-WithoutJavaOptionEnvironment {
        Invoke-NativeProcess -Executable $Fixture.Java `
            -Arguments @("-jar", $Fixture.ProbeJar, "flood") `
            -WorkingDirectory $Fixture.Workspace
    }
    Assert-Equal -Expected 0 -Actual $flood.ExitCode -Message "both full pipes complete"
    Assert-Equal -Expected ("o" * (256 * 8192)) -Actual $flood.Stdout `
        -Message "large stdout is complete"
    Assert-Equal -Expected ("e" * (256 * 8192)) -Actual $flood.Stderr `
        -Message "large stderr is complete"
}

function Test-JarVersionGate {
    # Real JARs prove stderr cannot satisfy the stdout identity check or hide a nonzero exit,
    # and the gate must keep working from the non-ASCII build directory of this workspace.
    param([Parameter(Mandatory = $true)] $Fixture)
    $savedJava = $script:SelectedJava
    $savedJar = $script:BuiltJar
    $names = @("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")
    $savedOptions = @{}
    try {
        $script:SelectedJava = $Fixture.Java
        foreach ($name in $names) {
            $savedOptions[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
            [Environment]::SetEnvironmentVariable($name, "-not-a-valid-java-option", "Process")
        }
        foreach ($mode in @("success", "fail", "stderr-only", "wrong-stdout")) {
            [IO.File]::WriteAllText(
                (Join-Path $Fixture.Workspace "version-mode.txt"),
                $mode
            )
            $script:BuiltJar = Join-Path $Fixture.Workspace "$mode.jar"
            $packed = Invoke-WithoutJavaOptionEnvironment {
                Invoke-NativeProcess -Executable $Fixture.Jar -Arguments @(
                    "--create", "--file", "$mode.jar", "--main-class", "ProcessProbe",
                    "-C", ".", "ProcessProbe.class", "-C", ".", $Fixture.PackageDirectory,
                    "-C", ".", "version-mode.txt"
                ) -WorkingDirectory $Fixture.Workspace
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

function Get-EncodedDaemonArgumentFixture {
    # Synthesize a resolved install context so the production serializer can be inspected
    # without resolving a real profile, gateway, token file, or Bash executable.
    $savedParameters = $script:InvocationParameters
    $savedGatewayUri = $GatewayUri
    $savedNote = $Note
    $savedTokenFile = $script:ResolvedTokenFile
    $savedDataDir = $script:ResolvedDataDir
    $savedBash = $script:SelectedBash
    $savedLspConfig = $script:ResolvedLspConfig
    $savedInstallRoot = $script:InstallRoot
    try {
        $script:InvocationParameters = @{ Note = $true; LspConfig = $true }
        $GatewayUri =
            "wss://studio.example.invalid/api/harness/environment-daemon/v1?x=1&y=2"
        $Note = New-TextFromCodePoints -CodePoints 0x4E2D, 0x6587
        $script:ResolvedTokenFile = "C:\Fixture Root\.config\kk-studio\daemon token.txt"
        $script:ResolvedDataDir = "C:\Fixture Root\.kk-studio"
        $script:SelectedBash = "C:\Program Files\Git\bin\bash.exe"
        $script:ResolvedLspConfig = "C:\Fixture Root\lsp-config.json"
        # The JAR uses an ASCII relative name, while application arguments use Base64. The
        # non-ASCII install root lives only in the native working directory field.
        $script:InstallRoot = "C:\Fixture Root\AppData\Local\kk-studio\" +
            (New-TextFromCodePoints -CodePoints 0x62A4, 0x7406)
        return [pscustomobject] @{
            Arguments = @(New-DaemonArgumentList)
            InstallRoot = $script:InstallRoot
            Logical = @(
                "--gateway-uri", $GatewayUri,
                "--registration-token-file", $script:ResolvedTokenFile,
                "--data-dir", $script:ResolvedDataDir,
                "--note", $Note,
                "--bash-executable", $script:SelectedBash,
                "--lsp-config", $script:ResolvedLspConfig
            )
        }
    }
    finally {
        $script:InvocationParameters = $savedParameters
        $GatewayUri = $savedGatewayUri
        $Note = $savedNote
        $script:ResolvedTokenFile = $savedTokenFile
        $script:ResolvedDataDir = $savedDataDir
        $script:SelectedBash = $savedBash
        $script:ResolvedLspConfig = $savedLspConfig
        $script:InstallRoot = $savedInstallRoot
    }
}

function Test-EncodedDaemonArgumentList {
    # The task command line must be pure ASCII, quote free, and must never carry the
    # absolute install path, so Task Scheduler cannot re-quote a profile-dependent value.
    $fixture = Get-EncodedDaemonArgumentFixture
    $arguments = $fixture.Arguments
    Assert-True -Condition ($fixture.InstallRoot -match "[^\x00-\x7F]") `
        -Message "the fixture install root is non-ASCII"
    Assert-SequenceEqual -Expected @("-jar", "kk-studio-daemon.jar") `
        -Actual @($arguments[0], $arguments[1]) `
        -Message "JAR is referenced by its ASCII relative file name"
    Assert-Equal -Expected "--base64-args" -Actual $arguments[2] `
        -Message "the encoding flag is the first daemon argument"

    $commandLine = ConvertTo-WindowsCommandLine -Arguments $arguments
    Assert-True -Condition ($commandLine -notmatch "[^\x20-\x7E]") `
        -Message "scheduled command line is printable ASCII only"
    Assert-True -Condition ($commandLine -notmatch '"') `
        -Message "no scheduled argument needs command line quoting"
    Assert-True -Condition ($commandLine -notmatch [regex]::Escape($fixture.InstallRoot)) `
        -Message "the absolute install root never enters the command line"

    $decoded = @(
        foreach ($token in $arguments[3..($arguments.Count - 1)]) {
            [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($token))
        }
    )
    Assert-SequenceEqual -Expected $fixture.Logical -Actual $decoded `
        -Message "one Base64 token per application argument, in order"
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
    # Constructs the real task object from the production arguments and never registers it.
    Import-Module ScheduledTasks -ErrorAction Stop
    $java = Get-Command "java.exe" -CommandType Application |
        Select-Object -First 1
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $description =
        "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$sid"
    $fixture = Get-EncodedDaemonArgumentFixture
    $arguments = $fixture.Arguments
    $installRoot = $fixture.InstallRoot

    $definition = New-DaemonScheduledTaskDefinition `
        -JavaExecutable $java.Source `
        -DaemonArguments $arguments `
        -WorkingDirectory $installRoot `
        -UserSid $sid `
        -Description $description

    Assert-Equal -Expected 1 -Actual @($definition.Actions).Count `
        -Message "one direct action"
    $action = @($definition.Actions)[0]
    Assert-Equal -Expected $java.Source -Actual $action.Execute `
        -Message "action executes java.exe directly"
    Assert-Equal -Expected (ConvertTo-WindowsCommandLine -Arguments $arguments) `
        -Actual $action.Arguments -Message "action uses serialized logical argv"
    Assert-True -Condition ($action.Arguments -notmatch "[^\x20-\x7E]") `
        -Message "task action arguments are printable ASCII only"
    Assert-True -Condition ($action.Arguments -notmatch '"') `
        -Message "task action arguments need no re-quoting"
    Assert-Equal -Expected $installRoot -Actual $action.WorkingDirectory `
        -Message "action working directory is the managed install root"

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
    Test-JavaVersionCapture
    Test-EncodedDaemonArgumentList
    $fixture = New-ProbeFixture -Java (Get-RequiredJavaExecutable)
    try {
        Test-NativeAsciiQuoting -Fixture $fixture
        Test-EncodedArgumentRoundTrip -Fixture $fixture
        Test-NativeStreamCapture -Fixture $fixture
        Test-JarVersionGate -Fixture $fixture
    }
    finally {
        Remove-Item -LiteralPath $fixture.Workspace -Recurse -Force `
            -ErrorAction SilentlyContinue
    }
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
