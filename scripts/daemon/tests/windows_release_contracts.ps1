#Requires -Version 5.1
<#
Native release, source, and management contracts for scripts/daemon/install.ps1.

Dot-sourced by test_daemon_install_windows.ps1, so this file only defines
Test-ReleaseInstallerContracts, which the parent suite runs in both the portable ProcessOnly leg and
the Windows leg. Every case re-enters through the real Invoke-Main with one previous release already
installed and managed. Every host capability the test must not really use is a function
mock declared inside the test function, so PowerShell dynamic scoping shadows the production
helper for this test only: no mock leaks into the suite, no Scheduled Task is queried or
mutated, and no request leaves the machine, while the real Get-ReleaseJar, Prepare-StagedJar,
Get-FileHash, Publish-StagedJar, and Assert-BuiltJar still do all file, checksum, and staging work
against temporary directories. ASCII-only, because Windows PowerShell 5.1 decodes a BOM-less
script with the ANSI code page.
#>

function Test-ReleaseInstallerContracts {
    $productionInputs = (Get-Command Resolve-InstallInputs).ScriptBlock
    $calls = [Collections.Generic.List[string]]::new()
    $releases = [Collections.Generic.List[object]]::new()
    $downloads = [Collections.Generic.List[object]]::new()
    $probes = [Collections.Generic.List[object]]::new()

    $sandbox = Join-Path ([IO.Path]::GetTempPath()) ("kk-studio-release-contracts-" + [Guid]::NewGuid().ToString("N"))
    $profile = Join-Path $sandbox "profile"
    $install = Join-Path $profile "AppData/Local/kk-studio"
    $previousJar = "kk-studio-daemon 2.4.5 (previous release)"
    $sourceJar = "kk-studio-daemon 0.0.0-SOURCE"
    $latestTag = "v2.4.6"
    $gateway = "wss://studio.example.invalid/api/harness/environment-daemon/v1"
    $bash = "bash.exe"
    $GatewayUri = $gateway
    $tokenFile = (Join-Path $install "config/daemon.token")
    $dataDirectory = (Join-Path $profile ".kk-studio")
    $daemonConfig = @("--gateway-uri", $gateway, "--registration-token-file", $tokenFile, "--data-dir", $dataDirectory)
    $forbidden = @("Register-ScheduledTask", "Unregister-ScheduledTask", "Start-ScheduledTask", "Stop-ScheduledTask",
        "Get-ScheduledTask", "New-DaemonScheduledTaskDefinition", "Resolve-RepositoryRoot", "Invoke-DaemonBuild")

    # A JDK 21 home whose bin/java.exe is a real file: upgrade re-derives Java from the task.
    $state = [pscustomobject] @{
        Sid = "S-1-5-21-1600000000-fixture"
        TaskName = ""
        TaskDescription = ""
        InstallRoot = (Join-Path $install "daemon")
        InstalledJar = (Join-Path $install "daemon/kk-studio-daemon.jar")
        ConfigRoot = (Join-Path $install "config")
        JavaHome = (Join-Path $sandbox "jdk21")
        SelectedJava = ""
        Task = $null
        Definition = $null
        TokenReference = $null
        LatestTag = $latestTag
        AssetAction = $null
        ChecksumAction = $null
        FailUri = $null
        Probe = [pscustomobject] @{ Stdout = "kk-studio-daemon 2.4.6"; Stderr = ""; ExitCode = 0 }
    }
    $state.TaskName = "kk-studio-environment-daemon-$($state.Sid)"
    $state.TaskDescription = "Managed by $script:ManagedBy; schema=1; ownerSid=$($state.Sid)"
    $state.SelectedJava = Join-Path $state.JavaHome "bin\java.exe"
    New-Item -ItemType Directory -Path (Split-Path -Parent $state.SelectedJava) -Force | Out-Null
    New-Item -ItemType Directory -Path $state.InstallRoot -Force | Out-Null
    [IO.File]::WriteAllText($state.SelectedJava, "java fixture")

    function Get-ReleaseUri {
        # The production download base for one tag.
        param([string] $Tag, [switch] $Checksum)
        $asset = "kk-studio-daemon-$Tag.jar"
        if ($Checksum) { $asset = "$asset.sha256" }
        return "https://github.com/fengwk/kk-studio/releases/download/$Tag/$asset"
    }

    function New-ManagedTaskFixture {
        # The task as the scheduler reports it: ownership marker, one direct java.exe action.
        return [pscustomobject] @{
            TaskName = $state.TaskName; TaskPath = "\"; State = "Running"
            Description = $state.TaskDescription
            DaemonArguments = $daemonConfig
            Actions = @([pscustomobject] @{
                Execute = $state.SelectedJava
                Arguments = (ConvertTo-WindowsCommandLine -Arguments (@("-jar", "kk-studio-daemon.jar") +
                        (ConvertTo-DaemonEncodedArguments -Arguments $daemonConfig)))
                WorkingDirectory = $state.InstallRoot
            })
        }
    }

    function New-ChecksumFile {
        # The default line carries the real Get-FileHash, so verification is a real comparison.
        param([string] $OutFile, [string] $Hash = "", [string] $AssetName = "")
        $jar = $OutFile.Substring(0, $OutFile.Length - ".sha256".Length)
        if ([string]::IsNullOrEmpty($Hash)) { $Hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash }
        if ([string]::IsNullOrEmpty($AssetName)) { $AssetName = (Split-Path -Leaf $jar) }
        [IO.File]::WriteAllText($OutFile, "$Hash  $AssetName`n")
    }

    function Reset-ReleaseFixture {
        # Back to "one previous release installed and managed", with default download/probe behavior.
        $calls.Clear(); $releases.Clear(); $downloads.Clear(); $probes.Clear()
        $script:InvocationParameters = @{}
        $script:RepoRoot = $null; $script:BuiltJar = $null
        $script:StagedJar = $null; $script:DownloadDirectory = $null
        $script:ResolvedReleaseTag = $null
        $state.Task = New-ManagedTaskFixture
        $state.Definition = $null
        $state.LatestTag = $latestTag
        $state.FailUri = $null
        $state.Probe = [pscustomobject] @{ Stdout = "kk-studio-daemon 2.4.6"; Stderr = ""; ExitCode = 0 }
        $state.AssetAction = { param($Uri, $OutFile) [IO.File]::WriteAllText($OutFile, "kk-studio-daemon 2.4.6") }
        $state.ChecksumAction = { param($Uri, $OutFile) New-ChecksumFile -OutFile $OutFile }
        [IO.File]::WriteAllText($state.InstalledJar, $previousJar)
    }

    function Assert-UpgradeRefused {
        # The shared pre-publish control: old JAR kept, nothing staged, task untouched, no temp left.
        param([string] $Message)
        Assert-Equal -Expected $previousJar -Actual ([IO.File]::ReadAllText($state.InstalledJar)) `
            -Message "$Message (managed JAR untouched)"
        Assert-Equal -Expected 0 -Actual @(Get-ChildItem -LiteralPath $state.InstallRoot -Filter ".kk-studio-daemon.jar.tmp.*").Count `
            -Message "$Message (no staged JAR left beside the installation)"
        Assert-True -Condition ($calls -notcontains "Stop-DaemonTaskIfRunning" -and $calls -notcontains "Start-AndVerifyDaemonTask") `
            -Message "$Message (the task is neither stopped nor started)"
        if ($downloads.Count -gt 0) {
            Assert-True -Condition (-not (Test-Path -LiteralPath (Split-Path -Parent $downloads[0].OutFile))) `
                -Message "$Message (temporary download directory removed)"
        }
    }

    # ---- mocks: declared here, so they shadow the production helpers for this test only ----

    function Initialize-HostContext {
        # No real profile, LocalApplicationData, or scheduler availability check.
        $script:CurrentSid = $state.Sid; $script:TaskName = $state.TaskName
        $script:TaskDescription = $state.TaskDescription; $script:HomePath = $profile
        $script:InstallRoot = $state.InstallRoot; $script:InstalledJar = $state.InstalledJar
        $script:ConfigRoot = $state.ConfigRoot
        $script:VerifyTimeoutSeconds = 0; $script:VerifyStableSeconds = 0
        $calls.Add("Initialize-HostContext")
    }

    function Resolve-InstallInputs {
        # Only the interactive Windows input resolution is faked; install itself still runs.
        $script:ResolvedTokenFile = $tokenFile; $script:ResolvedDataDir = $dataDirectory
        $script:SelectedBash = $bash; $script:SelectedJava = $state.SelectedJava
        $calls.Add("Resolve-InstallInputs")
    }

    function Get-RequiredManagedTask { $calls.Add("Get-RequiredManagedTask"); return $state.Task }
    function Find-DaemonTask { $calls.Add("Find-DaemonTask"); return $state.Task }
    function Start-AndVerifyDaemonTask { $calls.Add("Start-AndVerifyDaemonTask") }

    function Stop-DaemonTaskIfRunning {
        param([Parameter(Mandatory = $true)] $Task)
        $calls.Add("Stop-DaemonTaskIfRunning")
    }

    function Assert-Jdk21 {
        # No Windows path checks and no java.exe execution.
        param([Parameter(Mandatory = $true)][string] $Candidate)
        $calls.Add("Assert-Jdk21")
        Assert-Equal -Expected $state.JavaHome -Actual $Candidate `
            -Message "upgrade validates the Java home selected from the existing action"
        return $state.JavaHome
    }

    function Resolve-JavaHome {
        param([AllowEmptyString()][string] $ExplicitJavaHome)
        $calls.Add("Resolve-JavaHome")
        return $state.JavaHome
    }

    function Invoke-NativeProcess {
        # The only process a release runs is the artifact --version probe.
        param([string] $Executable, [string[]] $Arguments, [string] $WorkingDirectory)
        $probes.Add([pscustomobject] @{
                Executable = $Executable; Arguments = @($Arguments); WorkingDirectory = $WorkingDirectory
            })
        return $state.Probe
    }

    function Invoke-RestMethod {
        [CmdletBinding()]
        param([Parameter(Mandatory = $true)][string] $Uri, [hashtable] $Headers)
        $calls.Add("Invoke-RestMethod")
        $releases.Add([pscustomobject] @{ Uri = $Uri; Headers = $Headers })
        return [pscustomobject] @{ tag_name = $state.LatestTag }
    }

    function Invoke-WebRequest {
        [CmdletBinding()]
        param([switch] $UseBasicParsing, [string] $Uri, [string] $OutFile)
        $calls.Add("Invoke-WebRequest")
        $downloads.Add([pscustomobject] @{ Uri = $Uri; OutFile = $OutFile })
        if ($Uri -eq $state.FailUri) { throw "fixture download failure: $Uri" }
        if ($Uri -like "*.sha256") { & $state.ChecksumAction $Uri $OutFile }
        else { & $state.AssetAction $Uri $OutFile }
        return [pscustomobject] @{ StatusCode = 200 }
    }

    function Resolve-RepositoryRoot {
        # A download-based install never needs a checkout; the source case shadows this.
        throw "Resolve-RepositoryRoot must not run on the release path"
    }

    function Invoke-DaemonBuild { throw "Invoke-DaemonBuild must not run on the release path" }

    function New-DaemonScheduledTaskDefinition {
        param([string] $JavaExecutable, [string[]] $DaemonArguments, [string] $WorkingDirectory)
        $calls.Add("New-DaemonScheduledTaskDefinition")
        $state.Definition = [pscustomobject] @{
            JavaExecutable = $JavaExecutable; DaemonArguments = @($DaemonArguments); WorkingDirectory = $WorkingDirectory
        }
        return $state.Definition
    }

    function Register-ScheduledTask {
        [CmdletBinding()]
        param([string] $TaskName, $InputObject, [switch] $Force)
        $calls.Add("Register-ScheduledTask")
        $state.Definition = $InputObject
    }

    function Unregister-ScheduledTask {
        [CmdletBinding(SupportsShouldProcess = $true)]
        param([string] $TaskName, [string] $TaskPath)
        $calls.Add("Unregister-ScheduledTask")
    }

    function Get-ScheduledTask { throw "the real Task Scheduler must not be used here" }
    function Start-ScheduledTask { throw "the real task must not be started by this test" }
    function Stop-ScheduledTask { throw "the real task must not be stopped by this test" }

    function Get-ScheduledTaskInfo { param([string] $TaskName, [string] $TaskPath)
        return [pscustomobject] @{ LastRunTime = "2026-01-01"; LastTaskResult = 0; NextRunTime = "2026-01-01" }
    }

    function Test-SourceUpgrade {
        # The only path allowed to resolve a checkout and run a build, and only for the opt-in.
        $repo = Join-Path $sandbox "checkout"
        New-Item -ItemType Directory -Path $repo | Out-Null
        function Resolve-RepositoryRoot { return $repo }
        function Invoke-DaemonBuild {
            $calls.Add("Invoke-DaemonBuild")
            New-Item -ItemType Directory -Path (Split-Path -Parent $script:BuiltJar) -Force | Out-Null
            [IO.File]::WriteAllText($script:BuiltJar, $sourceJar)
        }
        $FromSource = $true
        $state.Probe.Stdout = $sourceJar
        $script:InvocationParameters = @{ FromSource = $true }
        $expectedJar = Join-Path $repo "harness\daemon\target\kk-studio-daemon.jar"
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "an explicit -FromSource upgrade succeeds"
        Assert-Equal -Expected $expectedJar -Actual $script:BuiltJar -Message "the source build uses the daemon module target JAR"
        Assert-True -Condition ($calls -contains "Invoke-DaemonBuild") -Message "the local build runs"
        Assert-True -Condition ($releases.Count -eq 0 -and $downloads.Count -eq 0) -Message "the source path resolves no release and downloads nothing"
        Assert-SequenceEqual -Expected (@("-jar", "kk-studio-daemon.jar") + (ConvertTo-DaemonEncodedArguments -Arguments @("--version"))) -Actual $probes[0].Arguments `
            -Message "the source probe encodes --version and names the JAR in ASCII"
        Assert-Equal -Expected $expectedJar -Actual (Join-Path $probes[0].WorkingDirectory (Split-Path -Leaf $expectedJar)) `
            -Message "the source probe runs the JAR from its build directory"
        Assert-Equal -Expected $sourceJar -Actual ([IO.File]::ReadAllText($state.InstalledJar)) `
            -Message "the locally built JAR is published to the managed JAR"
    }

    function Test-TokenPublicationContracts {
        # Real input resolution retains a SecureString; only publication is mocked (NTFS ACL
        # persistence is covered separately on Windows). Failures must keep the old bytes.
        $Command = "install"
        $GatewayUri = $gateway
        $failure = ""
        New-Item -ItemType Directory -Path $state.ConfigRoot -Force | Out-Null
        function Resolve-InstallInputs {
            try { & $productionInputs }
            finally { $state.TokenReference = $script:PendingToken }
        }
        function Resolve-JavaHome {
            param($ExplicitJavaHome)
            if ($failure -eq "java") { throw "fixture java failure" }
            return $state.JavaHome
        }
        function Resolve-Executable {
            param($Value, $DefaultName, $OptionName)
            if ($failure -eq "bash") { throw "fixture bash failure" }
            return $bash
        }
        function Find-DaemonTask {
            $calls.Add("Find-DaemonTask")
            if ($failure -eq "ownership-refresh" -and
                @($calls | Where-Object { $_ -eq "Find-DaemonTask" }).Count -eq 2) {
                $state.Task.Description = "foreign task"
            }
            return $state.Task
        }
        function New-DaemonScheduledTaskDefinition {
            param($JavaExecutable, $DaemonArguments, $WorkingDirectory, $UserSid, $Description)
            $calls.Add("New-DaemonScheduledTaskDefinition")
            if ($failure -eq "definition") { throw "fixture definition failure" }
            return [pscustomobject] @{ DaemonArguments = $DaemonArguments }
        }
        function Stop-DaemonTaskIfRunning {
            param($Task)
            $calls.Add("Stop-DaemonTaskIfRunning")
            if ($failure -eq "stop") { throw "fixture stop failure" }
        }
        function Save-RegistrationToken {
            param([Security.SecureString] $Token)
            # Early publication must fail even on the success path.
            Assert-True -Condition ($probes.Count -eq 1 -and
                $calls -contains "New-DaemonScheduledTaskDefinition" -and
                $calls -contains "Stop-DaemonTaskIfRunning") `
                -Message "token publication waits for JAR, definition and stopped task"
            Assert-ManagedTask -Task $state.Task
            $calls.Add("Save-RegistrationToken")
            [IO.File]::WriteAllText($tokenFile, "replacement fixture")
            return $tokenFile
        }
        foreach ($case in @(
            @{ Failure = "token"; Error = "control characters" },
            @{ Failure = "java"; Error = "fixture java failure" },
            @{ Failure = "bash"; Error = "fixture bash failure" },
            @{ Failure = "ownership"; Error = "unmanaged Scheduled Task" },
            @{ Failure = "download"; Error = "fixture download failure" },
            @{ Failure = "checksum"; Error = "checksum mismatch" },
            @{ Failure = "version"; Error = "does not match resolved release tag" },
            @{ Failure = "definition"; Error = "fixture definition failure" },
            @{ Failure = "stop"; Error = "fixture stop failure" },
            @{ Failure = "ownership-refresh"; Error = "unmanaged Scheduled Task" },
            @{ Failure = ""; Error = "" }
        )) {
            Reset-ReleaseFixture
            $failure = $case.Failure
            $script:RegistrationToken = "replacement fixture"
            if ($failure -eq "token") { $script:RegistrationToken = "invalid`nfixture" }
            $script:InvocationParameters = @{ GatewayUri = $gateway; RegistrationToken = "replacement fixture" }
            [IO.File]::WriteAllText($tokenFile, "previous fixture")
            if ($failure -eq "ownership") { $state.Task.Description = "foreign task" }
            if ($failure -eq "download") { $state.FailUri = Get-ReleaseUri -Tag $latestTag }
            if ($failure -eq "checksum") {
                $state.ChecksumAction = { param($Uri, $OutFile) New-ChecksumFile -OutFile $OutFile -Hash ("0" * 64) }
            }
            if ($failure -eq "version") { $state.Probe.Stdout = "kk-studio-daemon 9.9.9" }
            if ($failure -eq "") {
                Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "successful reinstall publishes a token"
                Assert-Equal -Expected "replacement fixture" -Actual ([IO.File]::ReadAllText($tokenFile)) `
                    -Message "preflight success permits token replacement"
            } else {
                Assert-Throws -Action { Invoke-Main } -ExpectedMessage $case.Error `
                    -Message "$failure fails before credential publication"
                Assert-Equal -Expected "previous fixture" -Actual ([IO.File]::ReadAllText($tokenFile)) `
                    -Message "$failure preserves the running installation's token"
                Assert-Equal -Expected $previousJar -Actual ([IO.File]::ReadAllText($state.InstalledJar)) `
                    -Message "$failure preserves the old JAR"
                Assert-True -Condition ($calls -notcontains "Save-RegistrationToken") `
                    -Message "$failure never enters token persistence"
            }
            Assert-Equal -Expected $null -Actual $script:PendingToken -Message "$failure clears pending secret"
            Assert-True -Condition ([string]::IsNullOrEmpty($script:RegistrationToken)) `
                -Message "$failure clears inline secret"
            if ($null -ne $state.TokenReference) {
                Assert-Throws -Action {
                    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($state.TokenReference)
                    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
                } -ExpectedMessage "disposed" `
                    -Message "$failure disposes the retained SecureString"
            }
            Assert-True -Condition (-not $script:InvocationParameters.ContainsKey("RegistrationToken")) `
                -Message "$failure removes the retained credential parameter"
        }
    }

    $savedScriptState = @{}
    foreach ($name in @("InvocationParameters", "VerifyTimeoutSeconds", "VerifyStableSeconds", "RepoRoot", "BuiltJar",
            "HomePath", "InstallRoot", "InstalledJar", "CurrentSid", "TaskName", "TaskDescription", "SelectedJavaHome",
            "SelectedJava", "SelectedBash", "ResolvedTokenFile", "ResolvedDataDir", "ResolvedLspConfig", "StagedJar",
            "DownloadDirectory", "ConfigRoot", "ResolvedReleaseTag", "PendingToken", "RegistrationToken")) {
        $variable = Get-Variable -Name $name -Scope Script -ErrorAction SilentlyContinue
        $savedScriptState[$name] = if ($null -eq $variable) { $null } else { $variable.Value }
    }

    try {
        # ---- install, with the shipped default command and nothing installed yet ----
        # $Command is deliberately not assigned before this call, so the shipped default runs.
        Reset-ReleaseFixture
        $state.Task = $null
        Assert-Equal -Expected "install" -Actual $Command -Message "the default command is install"
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "a first install succeeds"
        Assert-True -Condition (@($calls | Where-Object { $_ -in @("Resolve-InstallInputs", "New-DaemonScheduledTaskDefinition", "Register-ScheduledTask", "Start-AndVerifyDaemonTask") }).Count -eq 4) `
            -Message "install resolves inputs, defines, registers, and starts the task"
        Assert-Equal -Expected "kk-studio-daemon 2.4.6" -Actual ([IO.File]::ReadAllText($state.InstalledJar)) `
            -Message "install publishes the checksum-verified release asset"
        Assert-True -Condition ($state.Definition.DaemonArguments -contains `
            ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($dataDirectory)))) `
            -Message "the registered task carries the resolved token and data arguments"

        # ---- upgrade resolves the latest official release exactly once ----
        $Command = "upgrade"
        Reset-ReleaseFixture
        $argumentsBefore = $state.Task.Actions[0].Arguments
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "upgrade succeeds"
        Assert-Equal -Expected 1 -Actual $releases.Count -Message "the latest tag resolves once"
        Assert-Equal -Expected "https://api.github.com/repos/fengwk/kk-studio/releases/latest" -Actual $releases[0].Uri `
            -Message "the official latest release API is used"
        Assert-Equal -Expected 2 -Actual $downloads.Count -Message "the JAR and its checksum are the only downloads"
        Assert-Equal -Expected (Get-ReleaseUri -Tag $latestTag) -Actual $downloads[0].Uri -Message "the JAR comes from the resolved tag"
        Assert-Equal -Expected (Get-ReleaseUri -Tag $latestTag -Checksum) -Actual $downloads[1].Uri -Message "the checksum comes from the same tag"
        Assert-Equal -Expected 1 -Actual $probes.Count -Message "the verified artifact is probed once"
        Assert-Equal -Expected $state.SelectedJava -Actual $probes[0].Executable -Message "the probe runs the Java selected from the task action"
        Assert-Equal -Expected (Split-Path -Parent $downloads[0].OutFile) -Actual $probes[0].WorkingDirectory `
            -Message "the probe runs next to the ASCII JAR file name"
        Assert-SequenceEqual -Expected (@("-jar", "kk-studio-daemon-$latestTag.jar") + (ConvertTo-DaemonEncodedArguments -Arguments @("--version"))) `
            -Actual $probes[0].Arguments -Message "the probe names the JAR in ASCII and encodes --version"
        Assert-True -Condition ($calls.IndexOf("Invoke-WebRequest") -lt $calls.IndexOf("Stop-DaemonTaskIfRunning")) `
            -Message "the artifact is verified before the task is stopped"
        Assert-True -Condition (@($calls | Where-Object { $_ -in $forbidden }).Count -eq 0) `
            -Message "upgrade reuses the task and owns no other host capability"
        Assert-Equal -Expected "kk-studio-daemon 2.4.6" -Actual ([IO.File]::ReadAllText($state.InstalledJar)) `
            -Message "the published JAR is the verified release asset"
        Assert-Equal -Expected $null -Actual $script:StagedJar -Message "the staged JAR is published and cleared"
        Assert-True -Condition (-not (Test-Path -LiteralPath (Split-Path -Parent $downloads[0].OutFile))) `
            -Message "the temporary download directory is removed from disk"
        Assert-Equal -Expected $argumentsBefore -Actual $state.Task.Actions[0].Arguments -Message "the task command line is preserved verbatim"
        Assert-SequenceEqual -Expected $daemonConfig -Actual $state.Task.DaemonArguments `
            -Message "the gateway, token file, and data directory arguments survive"

        # ---- a pinned version never queries the release API ----
        Reset-ReleaseFixture
        $Version = "v0.9.1"
        $script:InvocationParameters = @{ Version = $Version }
        # Distinct bytes per tag, so the published JAR proves which asset was downloaded.
        $state.AssetAction = { param($Uri, $OutFile) [IO.File]::WriteAllText($OutFile, "kk-studio-daemon 0.9.1") }
        $state.Probe.Stdout = "kk-studio-daemon 0.9.1"
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "a pinned upgrade succeeds"
        Assert-True -Condition ($releases.Count -eq 0) -Message "a pinned version resolves no latest release"
        Assert-Equal -Expected (Get-ReleaseUri -Tag $Version) -Actual $downloads[0].Uri -Message "the pinned JAR is downloaded"
        Assert-Equal -Expected (Get-ReleaseUri -Tag $Version -Checksum) -Actual $downloads[1].Uri -Message "the pinned checksum is downloaded"
        Assert-Equal -Expected "kk-studio-daemon 0.9.1" -Actual ([IO.File]::ReadAllText($state.InstalledJar)) `
            -Message "the pinned asset is published"
        $Version = ""

        # ---- an unsafe tag is refused before any download, checkout, or stop ----
        Reset-ReleaseFixture
        $Version = "v1.0.0/../../etc"
        $script:InvocationParameters = @{ Version = $Version }
        Assert-Throws -Action { Invoke-Main } -ExpectedMessage "release tag must match" -Message "an unsafe pinned tag is refused"
        Assert-UpgradeRefused -Message "an unsafe pinned tag"
        $Version = ""

        Reset-ReleaseFixture
        $state.LatestTag = "v2.4.6`n"
        Assert-Throws -Action { Invoke-Main } -ExpectedMessage "release tag must match" `
            -Message "a latest tag with a trailing newline is refused"
        Assert-UpgradeRefused -Message "an unsafe latest tag"

        # ---- the checksum file must describe exactly this asset, and nothing else ----
        Reset-ReleaseFixture
        $state.ChecksumAction = { param($Uri, $OutFile) New-ChecksumFile -OutFile $OutFile -Hash ("0" * 64) }
        Assert-Throws -Action { Invoke-Main } -ExpectedMessage "release checksum mismatch" -Message "a checksum that does not match the JAR is refused"
        Assert-UpgradeRefused -Message "a checksum mismatch"
        Reset-ReleaseFixture
        $state.ChecksumAction = { param($Uri, $OutFile) New-ChecksumFile -OutFile $OutFile -AssetName "kk-studio-daemon-other.jar" }
        Assert-Throws -Action { Invoke-Main } -ExpectedMessage "expected exactly one matching asset" -Message "a checksum naming a foreign asset is refused"
        Assert-UpgradeRefused -Message "a foreign checksum file"
        Reset-ReleaseFixture
        $state.ChecksumAction = {
            param($Uri, $OutFile)
            New-ChecksumFile -OutFile $OutFile
            [IO.File]::AppendAllText($OutFile, "$('a' * 64)  second.jar`n")
        }
        Assert-Throws -Action { Invoke-Main } -ExpectedMessage "expected exactly one matching asset" -Message "a multi-line checksum file is refused"
        Assert-UpgradeRefused -Message "a multi-line checksum file"

        # ---- a failed download leaves the old JAR and cleans its temporary directory ----
        foreach ($request in @(@{ Uri = (Get-ReleaseUri -Tag $latestTag); What = "the JAR" },
                @{ Uri = (Get-ReleaseUri -Tag $latestTag -Checksum); What = "its checksum" })) {
            Reset-ReleaseFixture
            $state.FailUri = $request.Uri
            Assert-Throws -Action { Invoke-Main } -ExpectedMessage "fixture download failure" -Message "a failed download of $($request.What) is reported"
            Assert-UpgradeRefused -Message "a failed download of $($request.What)"
        }

        # ---- a JAR that cannot answer --version is refused before the task is stopped ----
        Reset-ReleaseFixture
        $state.Probe = [pscustomobject] @{ Stdout = ""; Stderr = ""; ExitCode = 23 }
        Assert-Throws -Action { Invoke-Main } -ExpectedMessage "exit code 23" -Message "a JAR that cannot run --version is refused"
        Assert-UpgradeRefused -Message "a failing artifact probe"

        # A valid checksum/identity prefix cannot hide a release version mismatch.
        foreach ($banner in @("kk-studio-daemon 9.9.9", "kk-studio-daemon 2.4.6 extra",
                " kk-studio-daemon 2.4.6")) {
            Reset-ReleaseFixture
            $state.Probe.Stdout = $banner
            Assert-Throws -Action { Invoke-Main } -ExpectedMessage "does not match resolved release tag" `
                -Message "release stdout must match the exact resolved version"
            Assert-UpgradeRefused -Message "a mismatched release version"
        }

        # Broken actions fail closed: no other Java can validate a task left with a bad path.
        foreach ($actions in @(@(), @([pscustomobject] @{ Execute = "" }),
                @([pscustomobject] @{ Execute = (Join-Path $sandbox "missing-java.exe") }),
                @($state.Task.Actions[0], $state.Task.Actions[0]))) {
            Reset-ReleaseFixture
            $state.Task.Actions = $actions
            Assert-Throws -Action { Invoke-Main } -ExpectedMessage "exactly one existing Java" `
                -Message "upgrade rejects a missing or ambiguous Java action"
            Assert-UpgradeRefused -Message "an invalid Java action"
            Assert-Equal -Expected 0 -Actual $downloads.Count -Message "invalid actions fail before downloading"
            Assert-True -Condition ($calls -notcontains "Resolve-JavaHome") `
                -Message "invalid actions never fall back to a different Java"
        }
        Reset-ReleaseFixture
        $otherExecutable = Join-Path $state.JavaHome "bin/not-java.exe"
        [IO.File]::WriteAllText($otherExecutable, "not java fixture")
        $state.Task.Actions[0].Execute = $otherExecutable
        Assert-Throws -Action { Invoke-Main } -ExpectedMessage "validated JDK 21" `
            -Message "an existing non-Java action cannot borrow the JDK version check"
        Assert-UpgradeRefused -Message "a mismatched Java executable"

        # ---- status is read-only, whether or not a managed task exists ----
        $Command = "status"
        Reset-ReleaseFixture
        $state.Task = $null
        Assert-Equal -Expected 1 -Actual (Invoke-Main) -Message "status reports an absent managed task"
        Reset-ReleaseFixture
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "status reports a Running managed task"
        Assert-True -Condition (@($calls | Where-Object { $_ -in ($forbidden + "Invoke-WebRequest") }).Count -eq 0) `
            -Message "status only reads the task and the managed JAR"
        Assert-Equal -Expected $previousJar -Actual ([IO.File]::ReadAllText($state.InstalledJar)) `
            -Message "status leaves the managed JAR alone"

        # ---- uninstall stops and unregisters before it removes the JAR ----
        $Command = "uninstall"
        Reset-ReleaseFixture
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "uninstall succeeds"
        Assert-True -Condition ($calls.IndexOf("Stop-DaemonTaskIfRunning") -lt $calls.IndexOf("Unregister-ScheduledTask")) `
            -Message "uninstall stops the task before unregistering it"
        Assert-True -Condition (-not (Test-Path -LiteralPath $state.InstalledJar)) -Message "uninstall removes the managed JAR"
        Assert-True -Condition ($calls -notcontains "Start-AndVerifyDaemonTask") -Message "uninstall never restarts the task"

        # ---- -FromSource is the explicit opt-in that builds locally ----
        $Command = "upgrade"
        Reset-ReleaseFixture
        Test-SourceUpgrade
        Test-TokenPublicationContracts
    }
    finally {
        foreach ($name in $savedScriptState.Keys) {
            Set-Variable -Name $name -Scope Script -Value $savedScriptState[$name]
        }
        foreach ($request in $downloads) {
            Remove-Item -LiteralPath (Split-Path -Parent $request.OutFile) -Recurse -Force `
                -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $sandbox -Recurse -Force -ErrorAction SilentlyContinue
    }
}
