"""Static and optional native contracts for the Windows daemon installer.

The repository's default script tests run on Linux, where the Windows-only ScheduledTasks
module does not exist. These tests therefore inspect lifecycle and security ordering directly
from the shipped ``install.ps1`` so the pre-publication checks, private-input snapshot,
program-directory publication and stop/unregister/report ordering stay verifiable everywhere.

The companion PowerShell suite (``test_daemon_install_windows.ps1``) exercises the real
ScheduledTasks objects and the direct ``node.exe`` argv round-trip on Windows; it is executed
by the Windows CI leg, so no Java toolchain is needed on any leg.
"""

import os
from pathlib import Path
import re
import shutil
import subprocess
import unittest


def repository_root():
    """Resolve the checkout root without relying on this test's directory depth."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


REPOSITORY_ROOT = repository_root()
INSTALL_SCRIPT = REPOSITORY_ROOT / "scripts" / "daemon" / "install.ps1"
NATIVE_TEST = REPOSITORY_ROOT / "scripts" / "daemon" / "tests" / "test_daemon_install_windows.ps1"


def script_text():
    return INSTALL_SCRIPT.read_text(encoding="utf-8-sig")


def function_body(name):
    """Extract one top-level PowerShell function for stable ordering assertions."""
    match = re.search(
        rf"(?ms)^function {re.escape(name)} \{{\n(.*?)(?=^function |\Z)",
        script_text(),
    )
    if not match:
        raise AssertionError(f"PowerShell function not found: {name}")
    return match.group(1)


def assert_in_order(test_case, body, *needles):
    """Assert that safety-sensitive operations occur in the required source order."""
    previous = -1
    for needle in needles:
        current = body.find(needle, previous + 1)
        test_case.assertGreater(current, previous, f"{needle!r} is missing or out of order")
        previous = current


class TestWindowsInstallerSurface(unittest.TestCase):
    """The public PowerShell entry must remain discoverable and Windows PowerShell compatible."""

    def test_script_declares_strict_windows_powershell_51_contract(self):
        """Windows PowerShell 5.1 and strict terminating-error behavior are release requirements."""
        text = script_text()
        self.assertTrue(INSTALL_SCRIPT.is_file())
        # PS 5.1 must decode the Chinese messages as UTF-8, not the host ANSI code page.
        self.assertTrue(INSTALL_SCRIPT.read_bytes().startswith(b"\xef\xbb\xbf"))
        self.assertTrue(text.startswith("#Requires -Version 5.1\n"))
        self.assertIn("Set-StrictMode -Version Latest", text)
        self.assertIn('$ErrorActionPreference = "Stop"', text)
        self.assertNotRegex(text, r"(?m)^\+")

    def test_help_lists_commands_layout_and_windows_limitations(self):
        """The discovery surface describes fixed storage, the bundled runtime and supervision limits."""
        text = function_body("Write-Usage")
        for value in ("install", "status", "uninstall", "help", "-ConfigFile", "-TokenFile",
                      "%USERPROFILE%\\.kk-studio", "daemon.json, daemon.token, lib, logs, backups",
                      "No user Node is required", "AtLogOn", "Interactive", "Limited",
                      "does not capture daemon stdout/stderr", "best effort settings"):
            self.assertIn(value, text)
        for value in ("-JavaHome", "JDK", "GatewayUri", "RegistrationToken", "FromSource",
                      "$Version", "LspConfig", "Read-Host", "Invoke-Upgrade",
                      "Resolve-RepositoryRoot", "Resolve-Executable", "Assert-BashReady"):
            self.assertNotIn(value, script_text())

    def test_installer_needs_no_user_jre_or_node(self):
        """The frozen release archive is the only runtime source: no JDK/JAR and no PATH Node."""
        text = script_text()
        for value in (
            "JAVA_HOME_21",
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "java.exe",
            ".jar",
            "--base64-args",
            "Assert-Jdk21",
            "Resolve-JavaHome",
            "Invoke-WithoutJavaOptionEnvironment",
            "ConvertTo-DaemonEncodedArguments",
        ):
            self.assertNotIn(value, text)
        # Only the archive's own frozen runtime may be executed.
        self.assertNotIn('Get-Command "node.exe"', text)
        self.assertIn('runtime\\code-server\\lib\\node.exe', text)

    def test_functions_are_dot_sourceable_without_running_main(self):
        """Native tests must load pure helpers without installing or registering anything."""
        text = script_text()
        self.assertIn('if ($MyInvocation.InvocationName -ne ".")', text)
        self.assertTrue(NATIVE_TEST.is_file(), "native Windows contract test must be shipped")


class TestWindowsInstallerSecurity(unittest.TestCase):
    """Credentials, task ownership, and direct execution are fail-closed contracts."""

    def test_token_validation_reads_metadata_only_and_requires_private_acl(self):
        """The installer must validate ACL metadata without ever opening the token bytes."""
        body = function_body("Assert-PrivateFile")
        for expected in (
            "Get-Item -LiteralPath",
            "ReparsePoint",
            "$item.Length",
            "Get-Acl -LiteralPath",
            "AreAccessRulesProtected",
            "GetAccessRules",
            "AccessControlType]::Allow",
            "ReadData",
            "$ExpectedOwnerSid",
        ):
            self.assertIn(expected, body)
        forbidden = (
            "Get-Content",
            "ReadAllText",
            "ReadAllBytes",
            "OpenRead",
            "StreamReader",
        )
        for value in forbidden:
            self.assertNotIn(value, body)
        self.assertIn("must not contain a deny-read ACE", body)

    def test_missing_task_lookup_does_not_turn_absence_into_an_error(self):
        """A first install must see an absent task as null without hiding scheduler failures."""
        body = function_body("Find-DaemonTask")
        self.assertIn("Get-ScheduledTask -ErrorAction Stop", body)
        self.assertIn("$_.TaskPath -eq", body)
        self.assertIn("$_.TaskName -eq $script:TaskName", body)
        self.assertNotIn("Get-ScheduledTask -TaskName", body)

    def test_install_inputs_stay_private_siblings_and_reject_unknown_options(self):
        """Empty/foreign/unsafe inputs and removed options never silently fall back."""
        body = function_body("Resolve-InstallInputs")
        self.assertIn('Assert-PrivateFile -Path $ConfigFile', body)
        self.assertIn('Assert-PrivateFile -Path $TokenFile', body)
        self.assertIn("must be siblings named daemon.json and daemon.token", body)
        # The option whitelist is exactly what the usage text offers.
        self.assertIn('$script:InstallParameterNames = @("ConfigFile", "TokenFile")', script_text())

    def test_preflight_surfaces_only_the_bounded_trusted_config_marker(self):
        """A config failure must expose the core field line, never raw output or the token."""
        text = script_text()
        self.assertIn('$script:ConfigFailureMarker = "Invalid daemon configuration: "', text)
        self.assertIn("$script:ConfigFailureMaxLength", text)
        helper = function_body("Get-TrustedConfigFailureDetail")
        self.assertIn("StartsWith($script:ConfigFailureMarker", helper)
        self.assertIn("$candidate.Length -gt $script:ConfigFailureMaxLength", helper)
        self.assertIn("$candidate -match", helper)
        staged = function_body("Prepare-StagedArchive")
        self.assertIn(
            "Get-TrustedConfigFailureDetail -Stdout $result.Stdout -Stderr $result.Stderr",
            staged,
        )
        self.assertIn("输出无法识别", staged)
        # Raw streams must never be interpolated into the user-visible failure.
        self.assertNotIn("$($result.Stdout", staged)
        self.assertNotIn("$($result.Stderr", staged)

    def test_task_is_owned_by_an_exact_per_user_marker(self):
        """A SID-scoped name plus exact Description marker prevents cross-user clobbering."""
        text = script_text()
        self.assertIn('"kk-studio-environment-daemon-$($script:CurrentSid)"', text)
        self.assertIn(
            '"Managed by $($script:ManagedBy); schema=1; ownerSid=$($script:CurrentSid)"',
            text,
        )
        body = function_body("Assert-ManagedTask")
        self.assertIn("$Task.Description -ne $script:TaskDescription", body)
        self.assertIn("拒绝操作非受管计划任务", body)
        # Ownership assertions never touch (or repair) the conflicting task.
        for removed in ("Export-ScheduledTask", "Stop-ScheduledTask", "Unregister-ScheduledTask",
                        "Remove-Item"):
            self.assertNotIn(removed, body)

    def test_scheduled_action_executes_node_directly_without_wrapper_or_redirection(self):
        """Task Scheduler must own node.exe itself so lifecycle state is not a shell process."""
        body = function_body("New-DaemonScheduledTaskDefinition")
        self.assertIn(
            "New-ScheduledTaskAction -Execute $NodeExecutable",
            body,
        )
        self.assertIn("-Argument $serializedArguments", body)
        self.assertIn("-WorkingDirectory $WorkingDirectory", body)
        for forbidden in (
            "cmd.exe",
            "powershell.exe",
            "pwsh.exe",
            "Start-Process",
            "RedirectStandard",
            "2>&1",
        ):
            self.assertNotIn(forbidden, body)

    def test_task_model_fixes_long_running_and_least_privilege_defaults(self):
        """The generated task must not stop after 72 hours or request elevated privileges."""
        body = function_body("New-DaemonScheduledTaskDefinition")
        for expected in (
            "New-ScheduledTaskTrigger -AtLogOn -User $UserSid",
            "-LogonType Interactive -RunLevel Limited",
            "-ExecutionTimeLimit ([TimeSpan]::Zero)",
            "-AllowStartIfOnBatteries",
            "-DontStopIfGoingOnBatteries",
            "-MultipleInstances IgnoreNew",
            "-RestartCount 3",
            "-RestartInterval (New-TimeSpan -Minutes 1)",
        ):
            self.assertIn(expected, body)
        self.assertNotIn("RunOnlyIfNetworkAvailable", body)
        self.assertNotIn("Highest", body)

    def test_task_arguments_are_always_quoted_by_the_dedicated_serializer(self):
        """The task command line must match the independently generated update script exactly."""
        definition = function_body("New-DaemonScheduledTaskDefinition")
        self.assertIn(
            "ConvertTo-WindowsCommandLine -Arguments $DaemonArguments",
            definition,
        )
        self.assertNotIn("$DaemonArguments -join", definition)
        serializer = function_body("ConvertTo-WindowsCommandLineArgument")
        self.assertIn("2 * $backslashes", serializer)
        # Always quote: an unquoted bypass would diverge on spaces or metacharacters.
        self.assertIn("$builder.Append('\"')", serializer)
        self.assertNotIn("notmatch", serializer)
        self.assertNotIn("return $Argument", serializer)

    def test_task_arguments_run_main_js_without_encoding(self):
        """Task Scheduler owns one command line; main.js is an ASCII leaf resolved against cwd."""
        arguments = function_body("New-DaemonArgumentList")
        self.assertIn('return @("main.js", "--config", $script:InstalledConfig)', arguments)
        self.assertNotIn("ToBase64String", arguments)
        self.assertNotIn("$script:InstalledNode", arguments)

    def test_task_working_directory_is_the_installed_lib(self):
        """A profile-dependent absolute main.js path must never be stored in the command line."""
        body = function_body("Invoke-Install")
        self.assertIn("-WorkingDirectory $script:InstalledLib", body)
        self.assertIn("-NodeExecutable $script:InstalledNode", body)
        definition = function_body("New-DaemonScheduledTaskDefinition")
        self.assertIn("-WorkingDirectory $WorkingDirectory", definition)

    def test_generic_capture_never_encodes_and_can_set_a_working_directory(self):
        """Only verbatim argv and native cwd travel into the probe; no pre-encoding is applied."""
        capture = function_body("Invoke-NativeProcess")
        self.assertIn("[AllowEmptyString()][string] $WorkingDirectory", capture)
        self.assertIn("$startInfo.WorkingDirectory = $WorkingDirectory", capture)
        self.assertNotIn("ToBase64String", capture)
        self.assertNotIn("--base64-args", capture)

        gate = function_body("Assert-DownloadedArchive")
        self.assertIn('@("main.js", "--version")', gate)
        self.assertIn("-WorkingDirectory $script:StagedLib", gate)
        self.assertIn('"runtime\\code-server\\lib\\node.exe"', gate)
        self.assertIn("main.js", gate)
        self.assertNotIn("ToBase64String", gate)

    def test_archive_is_unpacked_privately_and_layout_is_verified(self):
        """A SHA-verified archive still has to carry the expected program and frozen runtime."""
        body = function_body("Assert-DownloadedArchive")
        for expected in (
            'Get-Command "tar.exe"',
            "New-PrivateDirectory -Path $script:StagedLib",
            "-Arguments @(\"-xzf\", $script:DownloadedArchive, \"-C\", $script:StagedLib)",
            "is missing main.js",
            "is missing the bundled node.exe runtime",
        ):
            self.assertIn(expected, body)
        # The unpack target is a fresh private directory inside the private download directory.
        self.assertIn('$script:StagedLib = Join-Path $script:DownloadDirectory "lib"', script_text())


class TestWindowsInstallerLifecycle(unittest.TestCase):
    """Build validation and ownership checks must precede every destructive transition."""

    def test_install_validates_owner_and_artifacts_before_stopping_or_forcing(self):
        """Foreign tasks/layout conflicts precede downloads; all preflight precedes publication."""
        body = function_body("Invoke-Install")
        assert_in_order(self, body, "Initialize-HostContext", "Find-DaemonTask", "Assert-ManagedTask",
                        "Assert-ManagedLayout", "Resolve-InstallInputs", "Prepare-StagedArchive",
                        "New-DaemonScheduledTaskDefinition", "Assert-ManagedLayout", "Backup-ManagedFiles",
                        "Stop-DaemonTaskIfRunning", "Assert-ManagedTask",
                        'Move-Item -LiteralPath $script:StagedLib -Destination $script:InstalledLib',
                        "Publish-PrivateFile", "Register-ScheduledTask", "Start-AndVerifyDaemonTask")

    def test_program_directory_is_published_atomically_with_a_private_acl(self):
        """The previous program is moved into the backup before the staged lib replaces it."""
        body = function_body("Invoke-Install")
        assert_in_order(
            self,
            body,
            'Move-Item -LiteralPath $script:InstalledLib',
            "-Destination (Join-Path $script:BackupDirectory \"lib\")",
            "Move-Item -LiteralPath $script:StagedLib -Destination $script:InstalledLib",
            "Set-Acl -LiteralPath $script:InstalledLib -AclObject (New-PrivateAcl -Directory)",
        )
        for value in ("$script:InstalledLib", "$script:InstalledConfig", "$script:InstalledToken",
                      '"backups"', "Guid", "Assert-PrivateFile", "Copy-PrivateFile",
                      "Export-ScheduledTask"):
            self.assertIn(value, function_body("Backup-ManagedFiles"))

    def test_backups_and_failure_recovery_never_roll_back_or_touch_external_paths(self):
        """Backups contain only fixed regular owned targets; failures report manual recovery."""
        body = function_body("Invoke-Install")
        self.assertIn("未自动回滚", body)
        self.assertIn("备份：", body)
        self.assertNotIn("Publish-PrivateFile", body[body.index("    catch {"):])
        conflict = function_body("Assert-ManagedTask")
        for value in ("缺少精确所有权标记", "Get-ScheduledTask", "不要伪造所有权标记"):
            self.assertIn(value, conflict)
        for removed in ("Export-ScheduledTask", "Stop-ScheduledTask", "Unregister-ScheduledTask",
                        "Get-ConflictBackupCommands"):
            self.assertNotIn(removed, conflict)

    def test_refuses_to_overwrite_a_program_directory_without_a_managed_task(self):
        """An unknown program directory must never be replaced or deleted."""
        body = function_body("Assert-ManagedLayout")
        self.assertIn("Test-Path -LiteralPath $script:InstalledLib", body)
        self.assertIn("$script:InstalledLib", body)
        self.assertIn("未自动回滚，未知文件未变更", body)
        for removed in ("Remove-Item", "Move-Item", "Set-Acl"):
            self.assertNotIn(removed, body)

    def test_uninstall_stops_and_unregisters_before_removing_the_program(self):
        """Stop or unregister failure must leave the managed program directory in place."""
        body = function_body("Invoke-Uninstall")
        assert_in_order(
            self,
            body,
            "Assert-ManagedTask",
            "Stop-DaemonTaskIfRunning",
            "Get-RequiredManagedTask",
            "Unregister-ScheduledTask",
            "Remove-Item -LiteralPath $script:InstalledLib -Recurse -Force",
            "配置、数据和备份已保留。",
        )

    def test_uninstall_of_an_absent_task_reports_success_without_deleting_anything(self):
        """Absence is reported independently of deregistration; nothing else is touched."""
        body = function_body("Invoke-Uninstall")
        absent_branch = body[: body.index("Assert-ManagedTask -Task $task")]
        self.assertIn("$task = Find-DaemonTask", absent_branch)
        self.assertIn("KK Studio 环境未安装受管服务。配置、数据和备份均已保留。", absent_branch)
        self.assertIn("return 0", absent_branch)
        for forbidden in ("Unregister-ScheduledTask", "Remove-Item", "Stop-ScheduledTask"):
            self.assertNotIn(forbidden, absent_branch)

    def test_status_is_read_only_and_distinguishes_task_state_from_health(self):
        """Status may inspect task state but must not start, stop, or mutate it."""
        body = function_body("Invoke-Status")
        self.assertIn("Get-ScheduledTaskInfo", body)
        self.assertIn('State -eq "Running"', body)
        self.assertIn("task state is not gateway health", body)
        for forbidden in (
            "Start-ScheduledTask",
            "Stop-ScheduledTask",
            "Register-ScheduledTask",
            "Unregister-ScheduledTask",
            "Remove-Item",
        ):
            self.assertNotIn(forbidden, body)

    def test_staged_archive_is_always_cleaned_by_main_finally(self):
        """Only the installer-created staging directory is deleted on failures."""
        body = function_body("Invoke-Main")
        self.assertIn("finally", body)
        self.assertIn("Remove-Item -LiteralPath $script:DownloadDirectory", body)
        self.assertNotIn("SilentlyContinue", body)

    def test_release_is_latest_only_and_checks_staged_config(self):
        """Official latest tag selects the windows archive, then it validates sibling inputs."""
        self.assertIn('[string] $Command = "install"', script_text())
        staged = function_body("Prepare-StagedArchive")
        assert_in_order(self, staged, "Get-ReleaseArchive", "Assert-DownloadedArchive",
                        "Copy-PrivateFile -Source $ConfigFile",
                        "Copy-PrivateFile -Source $TokenFile", '"--check-config"',
                        "Invoke-NativeProcess")
        self.assertIn('"Daemon configuration is valid"', staged)
        release = function_body("Get-ReleaseArchive")
        self.assertIn('$asset = "kk-studio-daemon-$tag-windows-amd64.tar.gz"', release)
        for value in ("https://api.github.com/repos/fengwk/kk-studio/releases/latest",
                      "https://github.com/fengwk/kk-studio/releases/download/$tag",
                      "[Net.SecurityProtocolType]::Tls12", "Get-FileHash", "New-PrivateDirectory",
                      "Assert-ReleaseTag"):
            self.assertIn(value, release)


class TestWindowsInstallerNativeContracts(unittest.TestCase):
    """Run native tests only where Windows and PowerShell are genuinely available."""

    def test_native_suite_needs_no_java_and_asserts_a_direct_argv_round_trip(self):
        """The native leg must exercise the shipped serializer against a real node.exe."""
        native = NATIVE_TEST.read_text(encoding="ascii")
        self.assertIn("ConvertTo-WindowsCommandLine -Arguments (@($outputPath) + $arguments)", native)
        self.assertIn("Assert-DirectArgumentRoundTrip", native)
        self.assertIn("real node.exe", native)
        for value in ("java.exe", "JDK", "base64", "Base64", "DaemonArguments", "-jar"):
            self.assertNotIn(value, native)

    def test_windows_gate_also_runs_the_managed_update_native_suite(self):
        """The CI-invoked installer suite must chain the update suite for both PS hosts."""
        native = NATIVE_TEST.read_text(encoding="utf-8")
        self.assertIn("test_daemon_update_windows.ps1", native)
        self.assertIn("& $hostExe", native)
        self.assertIn("if ($LASTEXITCODE -ne 0)", native)

    def test_native_capture_keeps_streams_separate_and_drains_both_before_waiting(self):
        """Guard PS 5.1 compatibility and deadlock ordering; real outcomes are tested in PowerShell."""
        body = function_body("Invoke-NativeProcess")
        self.assertIn("ConvertTo-WindowsCommandLine -Arguments $Arguments", body)
        self.assertIn("$startInfo.UseShellExecute = $false", body)
        assert_in_order(
            self, body,
            "$process.StandardOutput.ReadToEndAsync()",
            "$process.StandardError.ReadToEndAsync()",
            "$process.WaitForExit()",
            "$stdout.GetAwaiter().GetResult()",
            "$stderr.GetAwaiter().GetResult()",
            "$process.ExitCode",
            "$process.Dispose()",
        )
        self.assertNotIn("ArgumentList", body, "ProcessStartInfo.ArgumentList requires newer .NET")
        probe = function_body("Assert-DownloadedArchive")
        self.assertIn("Invoke-NativeProcess", probe)
        self.assertNotIn("2>&1", probe)
        self.assertNotIn("$LASTEXITCODE", probe)

    @unittest.skipUnless(os.name == "nt", "native ScheduledTasks contracts require Windows")
    def test_native_powershell_contracts(self):
        """The companion suite validates real cmdlet objects and the node argv round-trip."""
        executable = shutil.which("powershell") or shutil.which("pwsh")
        self.assertIsNotNone(executable, "Windows validation requires PowerShell")
        result = subprocess.run(
            [
                executable,
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(NATIVE_TEST),
            ],
            cwd=REPOSITORY_ROOT,
            text=True,
            capture_output=True,
            check=False,
            timeout=300,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
