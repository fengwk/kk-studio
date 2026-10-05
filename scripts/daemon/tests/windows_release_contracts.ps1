#Requires -Version 5.1
<#
Deterministic release/lifecycle contracts. Real downloads are replaced with local bytes,
but checksum, version identity, staged sibling inputs, backups and publication run through
production helpers. Only OS ACL/ScheduledTasks and JAR process outcomes are mocked here.
The parent suite separately exercises real processes/decoder, NTFS ACLs and the real fat JAR.
No task is registered and no network request leaves the machine. ASCII-only for PS 5.1.
#>
function ConvertTo-FixtureText {
    param([Parameter(Mandatory = $true)][int[]] $CodePoints)
    return -join ($CodePoints | ForEach-Object { [char] $_ })
}

function Test-ReleaseInstallerContracts {
    $productionCopy = (Get-Command Copy-PrivateFile).ScriptBlock
    $sandbox = Join-Path ([IO.Path]::GetTempPath()) ("kk-release-" + [Guid]::NewGuid().ToString("N"))
    $calls = [Collections.Generic.List[string]]::new()
    $requests = [Collections.Generic.List[string]]::new()
    $probes = [Collections.Generic.List[object]]::new()
    $state = [pscustomobject] @{
        Task = $null; Failure = ""; LatestTag = "v2.4.6"; Definition = $null
        Download = $null; Backup = $null
    }
    $root = Join-Path $sandbox "profile/.kk-studio"
    $inputRoot = Join-Path $sandbox "inputs"
    $ConfigFile = Join-Path $inputRoot "daemon.json"
    $TokenFile = Join-Path $inputRoot "daemon.token"
    $Command = "install"
    $saved = @{}
    foreach ($name in @("InvocationParameters", "CurrentSid", "TaskName", "TaskDescription", "HomePath",
        "InstallRoot", "InstalledJar", "InstalledConfig", "InstalledToken", "SelectedJava",
        "SelectedJavaHome", "BuiltJar", "DownloadDirectory", "ResolvedReleaseTag",
        "BackupDirectory", "VerifyTimeoutSeconds", "VerifyStableSeconds")) {
        $saved[$name] = (Get-Variable -Name $name -Scope Script).Value
    }

    # Shadow only host boundaries. File operations and installer state transitions remain real.
    function Initialize-HostContext {
        $script:CurrentSid = "S-1-5-21-fixture"
        $script:TaskName = "kk-studio-environment-daemon-fixture"
        $script:TaskDescription = "Managed by $script:ManagedBy; schema=1; ownerSid=$script:CurrentSid"
        $script:HomePath = Join-Path $sandbox "profile"
        $script:InstallRoot = $root
        $script:InstalledJar = Join-Path $root "lib/kk-studio-daemon.jar"
        $script:InstalledConfig = Join-Path $root "daemon.json"
        $script:InstalledToken = Join-Path $root "daemon.token"
    }
    function Assert-AbsoluteWindowsPath { param($Value, $Name) }
    function Assert-PrivateDirectory {
        param($Path)
        if (-not (Test-Path -LiteralPath $Path -PathType Container)) { throw "fixture missing directory" }
    }
    function Set-Acl { param($LiteralPath, $AclObject) }
    function New-PrivateAcl { param([switch] $Directory); return "private ACL fixture" }
    function Assert-PrivateFile {
        param($Path, $ExpectedOwnerSid, $Name, $MaximumBytes)
        # Keep file shape/size constraints faithful; native tests cover real ownership and ACLs.
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "fixture missing file" }
        if ((Get-Item -LiteralPath $Path -Force).Length -le 0) { throw "fixture empty file" }
        return (Get-Item -LiteralPath $Path -Force).FullName
    }
    function Resolve-JavaHome { param($ExplicitJavaHome); return (Join-Path $sandbox "jdk") }
    function Copy-PrivateFile {
        param($Source, $Destination)
        if ((Split-Path -Leaf $Destination) -like ".publish-*" -and
            (($state.Failure -eq "publish-config" -and (Split-Path -Leaf $Source) -eq "daemon.json") -or
             ($state.Failure -eq "publish-token" -and (Split-Path -Leaf $Source) -eq "daemon.token"))) {
            throw "fixture publication failure"
        }
        & $productionCopy -Source $Source -Destination $Destination
    }
    function Find-DaemonTask {
        $calls.Add("find")
        if ($state.Failure -eq "refresh-owner" -and $probes.Count -eq 2) {
            $state.Task.Description = "foreign task"
        }
        return $state.Task
    }
    function New-DaemonScheduledTaskDefinition {
        param($JavaExecutable, $DaemonArguments, $WorkingDirectory, $UserSid, $Description)
        $calls.Add("definition")
        if ($state.Failure -eq "definition") { throw "fixture definition failure" }
        $state.Definition = [pscustomobject] @{
            Arguments = @($DaemonArguments); WorkingDirectory = $WorkingDirectory; Execute = $JavaExecutable
        }
        return $state.Definition
    }
    function Stop-DaemonTaskIfRunning {
        param($Task)
        $calls.Add("stop")
        $state.Backup = $script:BackupDirectory
        if ($Command -eq "install") {
            Assert-True -Condition (Test-Path -LiteralPath (Join-Path $state.Backup "daemon.token")) `
                -Message "backup exists before stop"
        }
        if ($state.Failure -eq "stop") { throw "fixture stop failure" }
    }
    function Export-ScheduledTask {
        param($TaskName, $TaskPath)
        $calls.Add("export")
        if ($state.Failure -eq "backup") { throw "fixture backup failure" }
        return "<Task>previous task fixture</Task>"
    }
    function Register-ScheduledTask {
        param($TaskName, $TaskPath, $InputObject, [switch] $Force)
        $calls.Add("register")
        if ($state.Failure -eq "register") { throw "fixture register failure" }
    }
    function Start-AndVerifyDaemonTask {
        $calls.Add("start")
        if ($state.Failure -eq "start") { throw "fixture start failure" }
    }
    function Unregister-ScheduledTask {
        [CmdletBinding(SupportsShouldProcess = $true)]
        param($TaskName, $TaskPath)
        $calls.Add("unregister")
        if ($state.Failure -eq "unregister") { throw "fixture unregister failure" }
        $state.Task = $null
    }
    function Get-ScheduledTaskInfo {
        param($TaskName, $TaskPath)
        if ($state.Failure -eq "manager") { throw "fixture scheduler failure" }
        return [pscustomobject] @{ LastRunTime = ""; LastTaskResult = 0; NextRunTime = "" }
    }
    function Invoke-RestMethod {
        param($Uri, $Headers)
        $calls.Add("latest")
        $requests.Add($Uri)
        return [pscustomobject] @{ tag_name = $state.LatestTag }
    }
    function Invoke-WebRequest {
        param([switch] $UseBasicParsing, $Uri, $OutFile)
        $calls.Add("download")
        $requests.Add($Uri)
        $state.Download = Split-Path -Parent $OutFile
        if ($state.Failure -eq "download") { throw "fixture download failure" }
        if ($Uri.EndsWith(".sha256")) {
            $jar = $OutFile.Substring(0, $OutFile.Length - 7)
            $hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
            $asset = Split-Path -Leaf $jar
            if ($state.Failure -eq "checksum") { $hash = "0" * 64 }
            if ($state.Failure -eq "foreign-checksum") { $asset = "foreign.jar" }
            $line = "$hash  $asset`n"
            if ($state.Failure -eq "multiple-checksum") { $line += "$hash  second.jar`n" }
            [IO.File]::WriteAllText($OutFile, $line)
        } else {
            [IO.File]::WriteAllText($OutFile, "new jar bytes")
        }
    }
    function Invoke-NativeProcess {
        param($Executable, $Arguments, $WorkingDirectory)
        $logical = @($Arguments[3..($Arguments.Count - 1)] | ForEach-Object {
            [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_))
        })
        $probes.Add([pscustomobject] @{ Logical = $logical; WorkingDirectory = $WorkingDirectory })
        Assert-True -Condition ($calls -notcontains "stop") -Message "all probes precede stop"
        $exit = 0
        $stdout = "kk-studio-daemon 2.4.6`n"
        $stderr = "private diagnostic not for output"
        if ($logical[0] -eq "--version") {
            if ($state.Failure -eq "version") { $stdout = "kk-studio-daemon 9.9.9" }
            if ($state.Failure -eq "version-exit") { $exit = 23 }
        } else {
            Assert-Equal -Expected "--check-config" -Actual $logical[0] -Message "new JAR preflights config"
            Assert-Equal -Expected (Join-Path $WorkingDirectory "daemon.json") -Actual $logical[1] `
                -Message "preflight uses staged rather than live config"
            Assert-Equal -Expected "new token fixture" `
                -Actual ([IO.File]::ReadAllText((Join-Path $WorkingDirectory "daemon.token"))) `
                -Message "staged sibling token copied unchanged"
            $stdout = "Daemon configuration is valid`n"
            switch ($state.Failure) {
                "config" {
                    $exit = 2
                    $stderr = "Invalid daemon configuration: daemon.studioUrl must be an absolute http(s) origin`n"
                }
                "config-leak" {
                    # The trusted field line is safe; the raw token line beside it must not surface.
                    $exit = 2
                    $stderr = "Invalid daemon configuration: daemon.note must be a single line`nnew token fixture`n"
                }
                "config-unknown" {
                    $exit = 2
                    $stderr = "Exception in thread main java.lang.IllegalArgumentException`nnew token fixture`n"
                }
                "config-output" { $stdout = "not valid" }
            }
        }
        return [pscustomobject] @{ Stdout = $stdout; Stderr = $stderr; ExitCode = $exit }
    }
    function Reset-Fixture {
        if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
        New-Item -ItemType Directory -Path (Join-Path $root "lib") -Force | Out-Null
        $calls.Clear(); $requests.Clear(); $probes.Clear()
        $state.Failure = ""; $state.LatestTag = "v2.4.6"; $state.Download = $null; $state.Backup = $null
        Initialize-HostContext
        $script:InvocationParameters = @{}
        if ($Command -eq "install") {
            $script:InvocationParameters = @{ ConfigFile = $ConfigFile; TokenFile = $TokenFile }
        }
        $script:DownloadDirectory = $null
        $state.Task = [pscustomobject] @{ Description = $script:TaskDescription; State = "Running" }
        [IO.File]::WriteAllText($script:InstalledJar, "old jar")
        [IO.File]::WriteAllText($script:InstalledConfig, "old config")
        [IO.File]::WriteAllText($script:InstalledToken, "old token")
        [IO.File]::WriteAllText((Join-Path $root "runtime-data"), "keep data")
    }
    function Assert-OldFiles {
        param($Context)
        foreach ($pair in @(@($script:InstalledJar, "old jar"), @($script:InstalledConfig, "old config"),
            @($script:InstalledToken, "old token"))) {
            Assert-Equal -Expected $pair[1] -Actual ([IO.File]::ReadAllText($pair[0])) `
                -Message "$Context preserves live file"
        }
    }
    try {
        New-Item -ItemType Directory -Path $inputRoot -Force | Out-Null
        [IO.File]::WriteAllText($ConfigFile, '{"studioUrl":"https://studio.example.invalid"}')
        [IO.File]::WriteAllText($TokenFile, "new token fixture")
        Reset-Fixture
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "managed overwrite succeeds"
        Assert-SequenceEqual -Expected @(
            "https://api.github.com/repos/fengwk/kk-studio/releases/latest",
            "https://github.com/fengwk/kk-studio/releases/download/v2.4.6/kk-studio-daemon-v2.4.6.jar",
            "https://github.com/fengwk/kk-studio/releases/download/v2.4.6/kk-studio-daemon-v2.4.6.jar.sha256"
        ) -Actual @($requests) -Message "latest resolves once; same tag jar and checksum"
        Assert-Equal -Expected 2 -Actual $probes.Count -Message "version and config are both checked"
        $encoded = $state.Definition.Arguments
        $logical = @($encoded[3..($encoded.Count - 1)] | ForEach-Object {
            [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_))
        })
        Assert-SequenceEqual -Expected @("--config", $script:InstalledConfig) -Actual $logical `
            -Message "task contains only config path, never token/connection settings"
        Assert-Equal -Expected (Split-Path -Parent $script:InstalledJar) -Actual $state.Definition.WorkingDirectory `
            -Message "task uses lib working directory"
        foreach ($pair in @(@("kk-studio-daemon.jar", "old jar"), @("daemon.json", "old config"), @("daemon.token", "old token"))) {
            Assert-Equal -Expected $pair[1] -Actual ([IO.File]::ReadAllText((Join-Path $state.Backup $pair[0]))) `
                -Message "backup preserves previous bytes"
        }
        Assert-True -Condition (Test-Path -LiteralPath (Join-Path $state.Backup "task.xml")) `
            -Message "backup supports manual task restoration"
        $firstBackup = $state.Backup
        $calls.Clear(); $requests.Clear(); $probes.Clear()
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "repeated overwrite succeeds"
        Assert-True -Condition ($firstBackup -ne $state.Backup -and (Test-Path -LiteralPath $firstBackup)) `
            -Message "backups are unique and previous backups retained"
        Assert-Equal -Expected "keep data" -Actual ([IO.File]::ReadAllText((Join-Path $root "runtime-data"))) `
            -Message "overwrite preserves runtime data"

        foreach ($case in @(
            @("owner", (ConvertTo-FixtureText 0x975E,0x53D7,0x7BA1,0x8BA1,0x5212,0x4EFB,0x52A1)), @("refresh-owner", (ConvertTo-FixtureText 0x975E,0x53D7,0x7BA1,0x8BA1,0x5212,0x4EFB,0x52A1)),
            @("download", (ConvertTo-FixtureText 0x65E0,0x6CD5,0x4E0B,0x8F7D)), @("checksum", (ConvertTo-FixtureText 0x6821,0x9A8C,0x5931,0x8D25)),
            @("foreign-checksum", (ConvertTo-FixtureText 0x6821,0x9A8C,0x5931,0x8D25)), @("multiple-checksum", (ConvertTo-FixtureText 0x6821,0x9A8C,0x5931,0x8D25)),
            @("version", "does not match"), @("version-exit", "exit code 23"),
            @("config", "Invalid daemon configuration"), @("config-leak", "Invalid daemon configuration"),
            @("config-unknown", (ConvertTo-FixtureText 0x914D,0x7F6E,0x6821,0x9A8C,0x5931,0x8D25)), @("config-output", (ConvertTo-FixtureText 0x914D,0x7F6E,0x6821,0x9A8C,0x5931,0x8D25)),
            @("definition", "fixture definition failure"), @("backup", "Partial backup")
        )) {
            Reset-Fixture
            $state.Failure = $case[0]
            if ($state.Failure -eq "owner") { $state.Task.Description = "foreign task" }
            $message = Assert-Throws -Action { Invoke-Main } -ExpectedMessage $case[1] `
                -Message "$($case[0]) refused"
            if ($state.Failure -in @("config-leak", "config-unknown")) {
                Assert-True -Condition ($message -notmatch "new token fixture") `
                    -Message "$($state.Failure) never echoes the token"
            }
            Assert-OldFiles -Context $case[0]
            Assert-True -Condition ($calls -notcontains "stop" -and $calls -notcontains "register") `
                -Message "$($case[0]) never stops or registers"
            if ($state.Failure -eq "owner") {
                Assert-Equal -Expected 0 -Actual $requests.Count -Message "foreign task detected before download"
                Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $root "backups"))) `
                    -Message "foreign task detected before target writes"
            }
            if ($null -ne $state.Download) {
                Assert-True -Condition (-not (Test-Path -LiteralPath $state.Download)) -Message "staging always cleaned"
            }
        }
        Reset-Fixture
        $state.LatestTag = "v2.4.6`n"
        Assert-Throws -Action { Invoke-Main } -ExpectedMessage "release tag must match" -Message "unsafe latest tag rejected"
        Assert-OldFiles -Context "unsafe tag"

        foreach ($failure in @("stop", "publish-config", "publish-token", "register", "start")) {
            Reset-Fixture
            $state.Failure = $failure
            Assert-Throws -Action { Invoke-Main } -ExpectedMessage (ConvertTo-FixtureText 0x672A,0x81EA,0x52A8,0x56DE,0x6EDA) `
                -Message "$failure reports manual recovery"
            Assert-True -Condition (Test-Path -LiteralPath $state.Backup) -Message "$failure retains backup"
            Assert-Equal -Expected "old token" -Actual ([IO.File]::ReadAllText((Join-Path $state.Backup "daemon.token"))) `
                -Message "$failure backup contains original credential"
            if ($failure -eq "stop") { Assert-OldFiles -Context "failed stop" }
            else {
                Assert-Equal -Expected "new jar bytes" -Actual ([IO.File]::ReadAllText($script:InstalledJar)) `
                    -Message "$failure does not secretly roll back published files"
            }
            if ($failure -eq "publish-config") {
                Assert-Equal -Expected "old config" -Actual ([IO.File]::ReadAllText($script:InstalledConfig)) `
                    -Message "failed config publication preserves that target"
            }
            if ($failure -in @("publish-config", "publish-token")) {
                Assert-Equal -Expected "old token" -Actual ([IO.File]::ReadAllText($script:InstalledToken)) `
                    -Message "failed publication preserves token target"
            }
            Assert-True -Condition (-not (Test-Path -LiteralPath $state.Download)) `
                -Message "$failure cleans staging without deleting backup"
        }
        Reset-Fixture
        $state.Task = $null
        Assert-Throws -Action { Invoke-Main } -ExpectedMessage (ConvertTo-FixtureText 0x62D2,0x7EDD,0x8986,0x76D6,0x65E0,0x53D7,0x7BA1,0x4EFB,0x52A1,0x7684,0x7A0B,0x5E8F) -Message "orphan program not overwritten"
        Assert-Equal -Expected 0 -Actual $requests.Count -Message "orphan conflict precedes download"
        Assert-OldFiles -Context "orphan"
        Remove-Item -LiteralPath $script:InstalledJar
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "first install succeeds with preserved config/token"
        Assert-True -Condition ($calls -notcontains "stop") -Message "first install stops no task"

        $Command = "status"
        Reset-Fixture
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "running status"
        $state.Task.State = "Ready"
        Assert-Equal -Expected 3 -Actual (Invoke-Main) -Message "inactive status"
        $state.Task = $null
        Assert-Equal -Expected 1 -Actual (Invoke-Main) -Message "absent status"
        Assert-OldFiles -Context "readonly status"
        Assert-Equal -Expected 0 -Actual $requests.Count -Message "status never downloads"
        Reset-Fixture
        $state.Failure = "manager"
        Assert-Throws -Action { Invoke-Main } -ExpectedMessage "fixture scheduler failure" -Message "manager errors propagate"

        $Command = "uninstall"
        Reset-Fixture
        $state.Failure = "unregister"
        Assert-Throws -Action { Invoke-Main } -ExpectedMessage "fixture unregister failure" -Message "unregister failure explicit"
        Assert-OldFiles -Context "unregister failure"
        $state.Failure = ""
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "uninstall succeeds"
        Assert-True -Condition (-not (Test-Path -LiteralPath $script:InstalledJar)) -Message "uninstall removes only owned jar"
        Assert-Equal -Expected "old token" -Actual ([IO.File]::ReadAllText($script:InstalledToken)) -Message "token preserved"
        Assert-Equal -Expected "old config" -Actual ([IO.File]::ReadAllText($script:InstalledConfig)) -Message "config preserved"
        Assert-Equal -Expected "keep data" -Actual ([IO.File]::ReadAllText((Join-Path $root "runtime-data"))) -Message "data preserved"
        Assert-Equal -Expected 0 -Actual (Invoke-Main) -Message "uninstall idempotent"
    }
    finally {
        if ($null -ne $script:DownloadDirectory -and (Test-Path -LiteralPath $script:DownloadDirectory)) {
            Remove-Item -LiteralPath $script:DownloadDirectory -Recurse -Force
        }
        foreach ($name in $saved.Keys) { Set-Variable -Name $name -Scope Script -Value $saved[$name] }
        Remove-Item -LiteralPath $sandbox -Recurse -Force
    }
}
