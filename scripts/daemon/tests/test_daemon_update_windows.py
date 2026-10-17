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

    def test_failure_reporting_and_cleanup_are_independent(self):
        text = script_text()
        # One fail-closed entry point that throws, so the finally block always runs.
        self.assertIn("function Fail-Closed([string]$Message) {", text)
        self.assertIn("throw [System.InvalidOperationException]::new($Message)", text)
        self.assertIn("operationId = $OperationId; phase = $Phase; message = $Message", text)
        self.assertIn("if ($null -ne $failure) { exit 1 }", text)
        # Write-Error is script-terminating under $ErrorActionPreference = 'Stop' and would skip
        # the failure receipt and the cleanup; the diagnostic goes to stderr instead.
        code = "\n".join(
            line for line in text.splitlines() if not line.lstrip().startswith("#"))
        self.assertNotIn("Write-Error", code)
        self.assertIn('[Console]::Error.WriteLine("update ${OperationId} failed: $Message")', text)
        # Reporting may itself fail: the failure receipt and the PrePARED receipt are both
        # best effort and never abort the surrounding cleanup.
        self.assertIn("could not write the failure receipt", text)
        self.assertIn("could not write the PREPARED receipt", text)
        # The one-shot task removal sits in its own finally and warns instead of swallowing.
        self.assertIn(
            "Unregister-ScheduledTask -TaskName $UpdateTaskName -Confirm:$false -ErrorAction Stop",
            text)
        self.assertIn("could not unregister its one-shot task", text)
        self.assertNotIn("Unregister-ScheduledTask -TaskName $UpdateTaskName -Confirm:$false -ErrorAction SilentlyContinue", text)

    def test_failure_receipt_is_written_before_the_cleanup_and_outside_the_diagnostic(self):
        text = script_text()
        failure_receipt = text.index("Write-Result 'FAILED' $failure")
        diagnostic = text.index("Write-FailureDiagnostic $failure")
        unregister = text.index("Unregister-ScheduledTask -TaskName $UpdateTaskName")
        self.assertLess(failure_receipt, diagnostic)
        self.assertLess(diagnostic, unregister)

    def test_unexpected_failures_report_only_the_stage_and_the_exception_type(self):
        text = script_text()
        for stage in ("$Step = 'preflight'", "$Step = 'handoff'", "$Step = 'permit'",
                      "$Step = 'stop'", "$Step = 'backup'", "$Step = 'replace'",
                      "$Step = 'restart'"):
            self.assertIn(stage, text, f"the script must pin the {stage} stage")
        self.assertIn(
            '$failure = "update failed during ${Step} ($($_.Exception.GetType().Name))"', text)

    def test_receipt_write_is_bom_free_and_best_effort(self):
        text = script_text()
        # The daemon parses the receipt with JSON.parse, so a BOM would break it.
        self.assertIn(
            "[IO.File]::WriteAllText($ResultPath, ($payload | ConvertTo-Json -Compress))", text)
        self.assertNotIn("Set-Content", text)

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

    def test_handoff_identity_is_required_and_the_state_is_checked_later(self):
        text = script_text()
        self.assertIn("if (-not (Test-ManagedFile -Path $HandoffPath)) { Fail-Closed 'handoff metadata is missing' }", text)
        self.assertIn("ConvertFrom-Json -ErrorAction Stop", text)
        self.assertIn("Fail-Closed 'handoff metadata is malformed'", text)
        self.assertIn("if ([string]$handoff.operationId -ne $OperationId) { Fail-Closed 'handoff metadata does not match the operation' }", text)
        self.assertIn("if ([string]$handoff.state -ne 'ACCEPTED') { Fail-Closed 'handoff metadata is not accepted' }", text)

    def test_the_state_gate_runs_after_the_permit_not_before(self):
        # The one-shot task starts while the daemon still persists PREPARING, so the state gate
        # must sit after the permit: only the operation identity may be checked up front.
        text = script_text()
        permit_wait = text.index("while (-not (Test-Path -LiteralPath $PermitPath -PathType Leaf))")
        state = text.index("if ([string]$handoff.state -ne 'ACCEPTED')")
        self.assertLess(permit_wait, state)
        code = "\n".join(
            line for line in text[:permit_wait].splitlines() if not line.lstrip().startswith("#"))
        self.assertIn("handoff metadata does not match the operation", code)
        self.assertNotIn("state", code)
        self.assertNotIn("ACCEPTED", code)

    def test_permit_must_be_a_regular_file_naming_this_operation(self):
        text = script_text()
        permit_wait = text.index("while (-not (Test-Path -LiteralPath $PermitPath -PathType Leaf))")
        regular = text.index("if (-not (Test-ManagedFile -Path $PermitPath)) { Fail-Closed 'the update permit is not a regular file' }")
        content = text.index("if ((Get-Content -LiteralPath $PermitPath -Raw).Trim() -ne $OperationId) { Fail-Closed 'the update permit does not match the operation' }")
        stop = text.index("Stop-ScheduledTask -TaskName $TaskName")
        self.assertLess(permit_wait, regular)
        self.assertLess(regular, content)
        self.assertLess(content, stop)

    def test_staged_and_installed_layout_is_checked_before_the_permit_wait(self):
        text = script_text()
        permit_wait = text.index("while (-not (Test-Path -LiteralPath $PermitPath -PathType Leaf))")
        assert_in_order(
            self,
            text[:permit_wait],
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

    def test_tasks_are_looked_up_at_the_scheduler_root_by_exact_identity(self):
        text = script_text()
        self.assertIn("$TaskPath = '\\'", text)
        # Enumerating the root keeps an absent task distinguishable from a real CIM failure:
        # a name-filtered Get-ScheduledTask reports absence as an error.
        self.assertIn("Get-ScheduledTask -ErrorAction Stop |", text)
        self.assertIn(
            "Where-Object { $_.TaskPath -eq $TaskPath -and $_.TaskName -eq $TaskName }", text)
        self.assertNotIn("Get-ScheduledTask -TaskName", text)
        self.assertIn("function Find-ManagedTask([string]$TaskName) {", text)

    def test_ownership_checks_cover_description_exactly_one_action_and_the_frozen_runtime(self):
        text = script_text()
        self.assertIn("$task = Find-ManagedTask $TaskName", text)
        self.assertIn("if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }", text)
        self.assertIn("if ([string]$task.Description -ne $TaskDescription) { Fail-Closed 'managed scheduled task is not owned by the installer' }", text)
        self.assertIn("$actions = @($task.Actions)", text)
        self.assertIn("if ($actions.Count -ne 1) { Fail-Closed 'managed scheduled task does not have exactly one action' }", text)
        self.assertIn("$expectedNode = Join-Path $InstalledDir 'runtime\\code-server\\lib\\node.exe'", text)
        self.assertIn("if (-not (Test-SamePath -Left ([string]$action.Execute) -Right $expectedNode)) {", text)
        self.assertIn("Fail-Closed 'managed scheduled task does not run the managed runtime'", text)
        self.assertIn('$expectedArguments = \'"main.js" "--config" "\' + $configPath + \'"\'', text)
        self.assertIn("if ([string]$action.Arguments -ne $expectedArguments) {", text)
        self.assertIn("Fail-Closed 'managed scheduled task arguments do not match the managed configuration'", text)
        self.assertIn("if (-not (Test-SamePath -Left ([string]$action.WorkingDirectory) -Right $InstalledDir)) {", text)
        self.assertIn("Fail-Closed 'managed scheduled task working directory does not match the managed installation'", text)
        self.assertIn("$configPath = Join-Path (Split-Path -Parent $InstalledDir) 'daemon.json'", text)

    def test_ownership_checks_precede_the_stop(self):
        text = script_text()
        stop = text.index("Stop-ScheduledTask -TaskName $TaskName")
        assert_in_order(
            self,
            text[:stop],
            "if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }",
            "if ([string]$task.Description -ne $TaskDescription)",
            "if ($actions.Count -ne 1)",
            "if (-not (Test-SamePath -Left ([string]$action.Execute) -Right $expectedNode))",
            "if ([string]$action.Arguments -ne $expectedArguments)",
            "if (-not (Test-SamePath -Left ([string]$action.WorkingDirectory) -Right $InstalledDir))",
        )

    def test_stop_wait_requires_both_running_and_queued_to_clear(self):
        text = script_text()
        self.assertIn(
            "if ($null -eq $current -or [string]$current.State -notin @('Running', 'Queued')) { break }",
            text)
        self.assertIn("$stopDeadline = (Get-Date).AddSeconds($StopWaitSeconds)", text)
        self.assertIn("Stop-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath", text)


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
        # PS 5.1 会丢掉内联 -e 源码的引号、按控制台代码页解码 stdout，正文只能走 UTF-8 文件。
        self.assertIn("export-update-script.mjs", text)
        self.assertNotIn("--input-type", text)
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

    def test_native_fixture_keeps_the_process_handle_for_the_exit_code(self):
        """PS 5.1 丢弃从函数返回的 Process 句柄，ExitCode 变 $null；失败用例会因此假通过。"""
        text = NATIVE_TEST_PATH.read_text(encoding="utf-8")
        start = text.index("function Start-Case(")
        wait = text.index("function Wait-Case(")
        invoke = text.index("function Invoke-Case(")
        start_body = text[start:wait]
        wait_body = text[wait:invoke]
        self.assertIn("[void] $process.Handle", start_body)
        self.assertNotIn("return Start-Process", start_body)
        self.assertIn("$Process.WaitForExit()", wait_body)
        self.assertIn("$Process.Refresh()", wait_body)
        self.assertIn("if ($null -eq $Process.ExitCode)", wait_body)
        self.assertIn("$Case.Name", wait_body)
        self.assertIn("return [int] $Process.ExitCode", wait_body)

    def test_native_fixture_does_not_register_or_touch_real_tasks(self):
        text = NATIVE_TEST_PATH.read_text(encoding="utf-8")
        self.assertNotIn("Register-ScheduledTask", text)
        self.assertIn("Remove-Case", text)


if __name__ == "__main__":
    unittest.main()
