"""Static and optional native contracts for the Windows daemon installer.

The repository's default script tests run on Linux, where the Windows-only ScheduledTasks
module does not exist. These tests therefore inspect lifecycle and security ordering directly.
When a Windows PowerShell executable is available, the companion PowerShell test performs
native parser, argv round-trip, and ScheduledTasks object checks without registering a task.
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
DOCKER_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "docker-publish.yml"
RELEASE_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "daemon-release.yml"


def script_text():
    return INSTALL_SCRIPT.read_text(encoding="utf-8")


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
        self.assertTrue(text.startswith("#Requires -Version 5.1\n"))
        self.assertIn("Set-StrictMode -Version Latest", text)
        self.assertIn('$ErrorActionPreference = "Stop"', text)
        self.assertNotRegex(text, r"(?m)^\+")

    def test_help_lists_commands_options_layout_and_windows_limitations(self):
        """The only discovery surface must state login, logging, Bash, and supervision limits."""
        text = function_body("Write-Usage")
        for command in ("install", "upgrade", "status", "uninstall"):
            self.assertIn(command, text)
        for option in (
            "-GatewayUri",
            "-RegistrationTokenFile",
            "-JavaHome",
            "-DataDir",
            "-Note",
            "-BashExecutable",
            "-LspBridgeCommand",
            "-JavapExecutable",
        ):
            self.assertIn(option, text)
        self.assertIn("%LOCALAPPDATA%\\kk-studio\\daemon", text)
        self.assertIn("AtLogOn", text)
        self.assertIn("Interactive", text)
        self.assertIn("Limited", text)
        self.assertIn("does not capture daemon stdout/stderr", text)
        self.assertIn("best effort settings", text)
        self.assertIn("Service/systemd supervision", text)
        self.assertIn("Git for", text)
        self.assertIn("compatible Bash", text)

    def test_functions_are_dot_sourceable_without_running_main(self):
        """Native tests must load pure helpers without installing or registering anything."""
        text = script_text()
        self.assertIn('if ($MyInvocation.InvocationName -ne ".")', text)
        self.assertTrue(NATIVE_TEST.is_file(), "native Windows contract test must be shipped")


class TestWindowsInstallerSecurity(unittest.TestCase):
    """Credentials, task ownership, and direct execution are fail-closed contracts."""

    def test_token_validation_reads_metadata_only_and_requires_private_acl(self):
        """The installer must validate ACL metadata without ever opening the token bytes."""
        body = function_body("Assert-RegistrationTokenFile")
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
            self.assertNotIn(value, script_text())
        self.assertIn("must not contain a deny-read ACE", body)

    def test_missing_task_lookup_does_not_turn_absence_into_an_error(self):
        """A first install must see an absent task as null without hiding scheduler failures."""
        body = function_body("Find-DaemonTask")
        self.assertIn("Get-ScheduledTask -ErrorAction Stop", body)
        self.assertIn("$_.TaskPath -eq", body)
        self.assertIn("$_.TaskName -eq $script:TaskName", body)
        self.assertNotIn("Get-ScheduledTask -TaskName", body)

    def test_explicit_executable_and_jdk_options_cannot_be_empty(self):
        """An explicitly empty option must not silently fall back to a different executable."""
        body = function_body("Resolve-InstallInputs")
        for name in ("JavaHome", "BashExecutable", "JavapExecutable"):
            self.assertIn(
                f'$script:InvocationParameters.ContainsKey("{name}")',
                body,
            )
            self.assertIn(
                f"Assert-RequiredValue -Value ${name} -Name \"-{name}\"",
                body,
            )

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
        self.assertIn("refusing to touch unmanaged Scheduled Task", body)

    def test_scheduled_action_executes_java_directly_without_wrapper_or_redirection(self):
        """Task Scheduler must own java.exe itself so lifecycle state is not a shell process."""
        body = function_body("New-DaemonScheduledTaskDefinition")
        self.assertIn(
            "New-ScheduledTaskAction -Execute $JavaExecutable",
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

    def test_task_arguments_use_a_dedicated_windows_serializer(self):
        """Raw joining of logical values would corrupt quotes and trailing backslashes."""
        definition = function_body("New-DaemonScheduledTaskDefinition")
        self.assertIn(
            "ConvertTo-WindowsCommandLine -Arguments $DaemonArguments",
            definition,
        )
        self.assertNotIn("$DaemonArguments -join", definition)
        serializer = function_body("ConvertTo-WindowsCommandLineArgument")
        self.assertIn("2 * $backslashes", serializer)
        self.assertIn("$Argument -notmatch '[\\s\"]'", serializer)


class TestWindowsInstallerLifecycle(unittest.TestCase):
    """Build validation and ownership checks must precede every destructive transition."""

    def test_install_validates_owner_and_artifacts_before_stopping_or_forcing(self):
        """A foreign task or failed build must remain untouched."""
        body = function_body("Invoke-Install")
        assert_in_order(
            self,
            body,
            "Assert-ManagedTask -Task $existing",
            "Prepare-StagedJar",
            "New-DaemonScheduledTaskDefinition",
            "Stop-DaemonTaskIfRunning",
            "$managedBeforePublish = Find-DaemonTask",
            "Assert-ManagedTask -Task $managedBeforePublish",
            "Publish-StagedJar",
            "$managedBeforeRegistration = Find-DaemonTask",
            "Assert-ManagedTask -Task $managedBeforeRegistration",
            "Register-ScheduledTask",
            "-Force",
            "Start-AndVerifyDaemonTask",
        )

    def test_upgrade_builds_and_stages_before_stop_then_only_replaces_the_jar(self):
        """Upgrade must preserve the task definition and avoid downtime on build failure."""
        body = function_body("Invoke-Upgrade")
        assert_in_order(
            self,
            body,
            "Get-RequiredManagedTask",
            "Prepare-StagedJar",
            "Stop-DaemonTaskIfRunning",
            "$managedBeforePublish = Get-RequiredManagedTask",
            "Publish-StagedJar",
            "Start-AndVerifyDaemonTask",
        )
        self.assertNotIn("Register-ScheduledTask", body)
        self.assertNotIn("New-DaemonScheduledTaskDefinition", body)

    def test_uninstall_stops_and_unregisters_before_removing_the_jar(self):
        """Stop or unregister failure must leave the managed JAR in place."""
        body = function_body("Invoke-Uninstall")
        assert_in_order(
            self,
            body,
            "Get-RequiredManagedTask",
            "Stop-DaemonTaskIfRunning",
            "Unregister-ScheduledTask",
            "Remove-Item -LiteralPath $script:InstalledJar",
        )

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

    def test_staged_jar_is_always_cleaned_by_main_finally(self):
        """A failed stop/register/start must not leave a staged JAR beside the installation."""
        body = function_body("Invoke-Main")
        self.assertIn("finally", body)
        self.assertIn("$script:StagedJar", body)
        self.assertIn("Remove-Item -LiteralPath $script:StagedJar", body)


class TestWindowsInstallerNativeContracts(unittest.TestCase):
    """Run native tests only where Windows and PowerShell are genuinely available."""

    def test_real_windows_gate_blocks_main_images_and_daemon_releases(self):
        """Formal support requires both PowerShell hosts and real ScheduledTasks cmdlets in CI."""
        for workflow_path in (DOCKER_WORKFLOW, RELEASE_WORKFLOW):
            with self.subTest(workflow=workflow_path.name):
                workflow = workflow_path.read_text(encoding="utf-8")
                self.assertIn("validate_windows_daemon:", workflow)
                self.assertIn("runs-on: windows-latest", workflow)
                self.assertIn("distribution: temurin", workflow)
                self.assertIn("java-version: ${{ env.JAVA_VERSION }}", workflow)
                self.assertIn(
                    r".\scripts\daemon\tests\test_daemon_install_windows.ps1",
                    workflow,
                )
                self.assertIn("shell: powershell", workflow)
                self.assertIn("shell: pwsh", workflow)

        docker = DOCKER_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("- validate_windows_daemon", docker)
        self.assertIn("needs.validate_windows_daemon.result == 'success'", docker)
        release = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("needs: validate_windows_daemon", release)

    @unittest.skipUnless(os.name == "nt", "native ScheduledTasks contracts require Windows")
    def test_native_powershell_contracts(self):
        """The companion suite validates real cmdlet objects and Java argv round-tripping."""
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
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
