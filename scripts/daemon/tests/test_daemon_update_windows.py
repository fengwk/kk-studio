"""Static contracts for the Windows managed update script.

``WINDOWS_UPDATE_SCRIPT`` is imported from the shipped ``daemon/src/update-scripts.ts`` through
Node 24, so these assertions always inspect the real source rather than a test copy. The script
runs as an independent one-shot scheduled task on Windows, where the ScheduledTasks module does
not exist on the Linux/macOS CI legs; the static tests therefore pin the filesystem layout,
permit/ownership ordering and the destructive-action boundaries wherever the suite runs.

The native companion (``test_daemon_update_windows.ps1``) drives the same exported text through
the ScheduledTasks API with disposable fixtures and is invoked by the Windows gate.
"""

from functools import lru_cache
import os
from pathlib import Path
import re
import unittest

from release_fixture import node_export


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
NATIVE_TEST = Path("scripts/daemon/tests/test_daemon_update_windows.ps1")
NATIVE_TEST_PATH = REPOSITORY_ROOT / NATIVE_TEST


@lru_cache(maxsize=1)
def script_text():
    return node_export("WINDOWS_UPDATE_SCRIPT")


def assert_in_order(test_case, body, *needles):
    """Assert that safety-sensitive operations occur in the required source order."""
    previous = -1
    for needle in needles:
        current = body.find(needle, previous + 1)
        test_case.assertGreater(current, previous, f"{needle!r} is missing or out of order")
        previous = current


class TestWindowsUpdateSurface(unittest.TestCase):
    """The shipped script must stay one fail-closed wrapper with an explicit exit contract."""

    def test_script_declares_the_three_node_distribution_directories(self):
        text = script_text()
        self.assertIn("[Parameter(Mandatory = $true)][string]$OperationDir", text)
        self.assertIn("[Parameter(Mandatory = $true)][string]$StagedDir", text)
        self.assertIn("[Parameter(Mandatory = $true)][string]$InstalledDir", text)
        self.assertIn("$OperationId = Split-Path -Leaf $OperationDir", text)
        self.assertIn("$ResultPath = Join-Path $OperationDir 'update-result.json'", text)
        self.assertIn("(Join-Path $StagedDir 'main.js')", text)
        self.assertIn("$ErrorActionPreference = 'Stop'", text)
        # The native suite shortens these bounds in its temporary copy; pin the production values.
        self.assertIn("$PermitWaitSeconds = 120", text)
        self.assertIn("$StopWaitSeconds = 30", text)

    def test_failure_path_writes_one_receipt_then_reports_and_exits_nonzero(self):
        text = script_text()
        self.assertIn("function Fail-Closed([string]$Message) {", text)
        self.assertIn("Complete-Update -Phase 'FAILED' -Message $Message", text)
        self.assertIn('Write-Error ("update " + $OperationId + " failed: " + $Message)', text)
        self.assertIn("exit 1", text)
        self.assertIn("operationId = $OperationId; phase = $Phase; message = $Message", text)

    def test_no_registration_elevation_or_arbitrary_command_execution(self):
        text = script_text()
        for forbidden in ("Register-ScheduledTask", "New-ScheduledTask", "RunLevel",
                          "Restart-Computer", "Set-ExecutionPolicy", "Invoke-Expression",
                          "Start-Process", "Remove-Item -Recurse"):
            self.assertNotIn(forbidden, text)
        # The only scheduler mutation is unregistering this operation's own one-shot task.
        self.assertEqual(1, len(re.findall(r"Unregister-ScheduledTask", text)))

    def test_script_never_reads_credentials_or_the_config_contents(self):
        code = "\n".join(
            line for line in script_text().splitlines() if not line.lstrip().startswith("#"))
        for forbidden in ("daemon.token", "Get-Content -LiteralPath $configPath",
                          "ReadAllText", "ReadAllBytes", "ConvertFrom-SecureString"):
            self.assertNotIn(forbidden, code)


class TestWindowsUpdateHandoffGate(unittest.TestCase):
    """The third argument is the installed distribution, never a user-supplied path."""

    def test_handoff_is_required_parsed_and_identity_checked(self):
        text = script_text()
        self.assertIn("if (-not (Test-Path -LiteralPath $HandoffPath -PathType Leaf))", text)
        self.assertIn("Fail-Closed 'handoff metadata is missing'", text)
        self.assertIn("ConvertFrom-Json -ErrorAction Stop", text)
        self.assertIn("Fail-Closed 'handoff metadata is malformed'", text)
        self.assertIn("if ($handoff.operationId -ne $OperationId) { Fail-Closed 'handoff metadata does not match the operation' }", text)
        self.assertIn("if ($handoff.state -ne 'ACCEPTED') { Fail-Closed 'handoff metadata is not accepted' }", text)

    def test_staged_and_installed_layout_is_checked_before_the_permit_wait(self):
        text = script_text()
        permit_wait = text.index("while (-not (Test-Path -LiteralPath $PermitPath -PathType Leaf))")
        assert_in_order(
            self,
            text[:permit_wait],
            "if ($handoff.state -ne 'ACCEPTED')",
            "if (-not (Test-Path -LiteralPath (Join-Path $StagedDir 'main.js') -PathType Leaf))",
            "if (-not (Test-Path -LiteralPath $InstalledDir))",
        )

    def test_missing_permit_fails_closed_without_touching_the_task(self):
        text = script_text()
        permit_wait = text.index("while (-not (Test-Path -LiteralPath $PermitPath -PathType Leaf))")
        timeout = text.index("Fail-Closed 'the daemon did not release the update permit in time'")
        stop = text.index("Stop-ScheduledTask -TaskName $TaskName")
        self.assertLess(permit_wait, timeout)
        self.assertLess(timeout, stop, "no service action may precede the permit gate")
        self.assertIn("$deadline = (Get-Date).AddSeconds($PermitWaitSeconds)", text)


class TestWindowsUpdateTaskOwnership(unittest.TestCase):
    """The managed AtLogOn task must be re-verified before anything stops."""

    def test_task_name_and_description_are_sid_scoped_installer_markers(self):
        text = script_text()
        self.assertIn('$Sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value', text)
        self.assertIn('$TaskName = "kk-studio-environment-daemon-$Sid"', text)
        self.assertIn(
            '$TaskDescription = "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$Sid"',
            text,
        )

    def test_ownership_checks_cover_task_description_runtime_and_arguments(self):
        text = script_text()
        self.assertIn("$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue", text)
        self.assertIn("if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }", text)
        self.assertIn("if ($task.Description -ne $TaskDescription) { Fail-Closed 'managed scheduled task is not owned by the installer' }", text)
        self.assertIn("$action = $task.Actions | Select-Object -First 1", text)
        self.assertIn("if ($null -eq $action) { Fail-Closed 'managed scheduled task has no action' }", text)
        self.assertIn("$expectedNode = Join-Path $InstalledDir 'runtime\\code-server\\lib\\node.exe'", text)
        self.assertIn("if ($action.Execute -ne $expectedNode) { Fail-Closed 'managed scheduled task does not run the managed runtime' }", text)
        self.assertIn('$expectedArguments = \'"main.js" "--config" "\' + $configPath + \'"\'', text)
        self.assertIn("if ($action.Arguments -ne $expectedArguments) { Fail-Closed 'managed scheduled task arguments do not match the managed configuration' }", text)
        self.assertIn("$configPath = Join-Path (Split-Path -Parent $InstalledDir) 'daemon.json'", text)

    def test_ownership_checks_precede_the_stop(self):
        text = script_text()
        stop = text.index("Stop-ScheduledTask -TaskName $TaskName")
        assert_in_order(
            self,
            text[:stop],
            "if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }",
            "if ($task.Description -ne $TaskDescription)",
            "if ($action.Execute -ne $expectedNode)",
            "if ($action.Arguments -ne $expectedArguments)",
        )

    def test_stop_wait_only_accepts_a_task_that_is_not_running(self):
        text = script_text()
        self.assertIn("if ($null -eq $current -or $current.State -ne 'Running') { break }", text)
        self.assertIn("$stopDeadline = (Get-Date).AddSeconds($StopWaitSeconds)", text)


class TestWindowsUpdateBackup(unittest.TestCase):
    """Every applied update gets its own unique backup and no automatic rollback."""

    def test_guid_backup_and_moves_are_ordered_before_restart(self):
        text = script_text()
        self.assertIn("'backup-' + [Guid]::NewGuid().ToString('N')", text)
        self.assertIn("New-Item -ItemType Directory -Force -Path $backupDir | Out-Null", text)
        self.assertIn("$previous = Join-Path $backupDir 'previous'", text)
        backup = text.index("Move-Item -LiteralPath $InstalledDir -Destination $previous")
        replace = text.index("Move-Item -LiteralPath $StagedDir -Destination $InstalledDir")
        restart = text.index("Start-ScheduledTask -TaskName $TaskName")
        self.assertLess(backup, replace)
        self.assertLess(replace, restart)
        self.assertNotIn("Copy-Item", text)
        self.assertNotIn("Remove-Item", text)


class TestWindowsUpdateNativeFixture(unittest.TestCase):
    """The Windows-only companion must exercise the same exported contract on Windows."""

    def test_native_fixture_imports_the_shipped_typescript_module(self):
        text = NATIVE_TEST_PATH.read_text(encoding="utf-8")
        self.assertIn("daemon\\src\\update-scripts.ts", text)
        self.assertIn("WINDOWS_UPDATE_SCRIPT", text)
        self.assertNotIn("harness" + "/daemon", text)
        for forbidden in (".jar", "java.exe", "JAVA_HOME"):
            self.assertNotIn(forbidden, text)

    def test_native_fixture_covers_the_handoff_permit_and_task_contract(self):
        text = NATIVE_TEST_PATH.read_text(encoding="utf-8")
        for needle in ("handoff.json", "permit", "ACCEPTED", "PREPARING",
                       "Running", "backup-", "previous",
                       "handoff metadata is not accepted",
                       "the daemon did not release the update permit in time",
                       "managed scheduled task is missing",
                       "managed scheduled task does not run the managed runtime",
                       "managed scheduled task arguments do not match the managed configuration",
                       "UnregisterScheduledTask", "-OperationDir", "-StagedDir", "-InstalledDir"):
            self.assertIn(needle, text, f"the native fixture must cover {needle!r}")

    def test_native_fixture_propagates_the_update_script_exit_code(self):
        text = NATIVE_TEST_PATH.read_text(encoding="utf-8")
        self.assertIn("-OperationDir", text)
        self.assertIn("exit $LASTEXITCODE", text)
        self.assertNotIn(". '__SCRIPT__'", text)

    def test_native_fixture_does_not_register_or_touch_real_tasks(self):
        text = NATIVE_TEST_PATH.read_text(encoding="utf-8")
        self.assertNotIn("Register-ScheduledTask", text)
        self.assertIn("Remove-Case", text)


if __name__ == "__main__":
    unittest.main()
