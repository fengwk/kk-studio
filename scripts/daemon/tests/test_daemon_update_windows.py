"""Static contracts for the Windows managed update script (`update.ps1`).

The update script runs as an independent one-shot scheduled task on Windows, where the
ScheduledTasks module does not exist on the Linux/macOS CI legs. These tests therefore
inspect the failure handling, cleanup independence, de-sensitisation ordering, the
handoff/permit gate ordering, the exact-root scheduled-task ownership checks and the
copy-based unique backup directly from the shipped source, so those guarantees stay
verifiable wherever the suite runs.

The native companion (`test_daemon_update_windows.ps1`) drives the real script through the
ScheduledTasks API with disposable fixtures; it needs Windows PowerShell and is executed on
the Windows matrix leg, so the assertions below also pin the contract that companion test
must exercise.
"""

import os
from pathlib import Path
import re
import unittest

UPDATE_SCRIPT = Path(
    "harness/daemon/src/main/resources/fun/fengwk/kkstudio/harness/daemon/update/update.ps1"
)
NATIVE_TEST = Path("scripts/daemon/tests/test_daemon_update_windows.ps1")


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
UPDATE_SCRIPT_PATH = REPOSITORY_ROOT / UPDATE_SCRIPT
NATIVE_TEST_PATH = REPOSITORY_ROOT / NATIVE_TEST


def script_text():
    return UPDATE_SCRIPT_PATH.read_text(encoding="utf-8")


def finally_body():
    """The single top-level `finally` block that must perform reporting and cleanup."""
    text = script_text()
    index = text.index("} finally {")
    return text[index:]


def assert_in_order(test_case, body, *needles):
    """Assert that safety-sensitive operations occur in the required source order."""
    previous = -1
    for needle in needles:
        current = body.find(needle, previous + 1)
        test_case.assertGreater(current, previous, f"{needle!r} is missing or out of order")
        previous = current


class TestWindowsUpdateSurface(unittest.TestCase):
    """The shipped script must stay one fail-closed wrapper with an explicit exit contract."""

    def test_script_is_a_single_bounded_fail_closed_wrapper(self):
        self.assertTrue(UPDATE_SCRIPT_PATH.is_file())
        text = script_text()
        self.assertIn("[Parameter(Mandatory = $true)][string]$StagedJar", text)
        self.assertIn("[Parameter(Mandatory = $true)][string]$InstalledJar", text)
        self.assertIn("$ErrorActionPreference = 'Stop'", text)
        self.assertEqual(1, len(re.findall(r"(?m)^try \{$", text)))
        self.assertIn("if ($null -ne $failure) { exit 1 }", text)

    def test_terminating_write_error_is_never_used(self):
        # `Write-Error` is script-terminating under `$ErrorActionPreference = 'Stop'` and would
        # skip the failure receipt and the one-shot task cleanup. Comments may name it.
        code = "\n".join(
            line for line in script_text().splitlines() if not line.lstrip().startswith("#"))
        self.assertNotRegex(code, r"\bWrite-Error\b")

    def test_failure_path_uses_one_entry_point_and_stderr_diagnostics(self):
        text = script_text()
        self.assertIn("$script:failClosedMessage = $Message", text)
        self.assertIn("throw [System.InvalidOperationException]::new($Message)", text)
        self.assertIn('[Console]::Error.WriteLine("update ${operationId} failed: $Message")', text)
        self.assertIn('[Console]::Error.WriteLine("update ${operationId} warning: $Message")', text)

    def test_exceptions_report_only_stage_and_type(self):
        # De-sensitised reporting: the stage and the exception type are safe, the exception
        # message and any command output may carry secrets, paths or artifact internals.
        text = script_text()
        self.assertIn("update failed during ${step}", text)
        self.assertIn("$($_.Exception.GetType().Name)", text)
        self.assertNotIn("$_.Exception.Message", text)
        self.assertNotIn("$Error[0]", text)

    def test_success_receipt_is_best_effort(self):
        # The binary is already replaced and the service restarted, so an unwritable success
        # receipt must stay a warning instead of a false update failure.
        text = script_text()
        match = re.search(
            r"(?ms)^  try \{\n(.*?)^  \} catch \{\n(.*?)^  \}", text)
        self.assertIsNotNone(match, "the success receipt write must be guarded by its own try/catch")
        self.assertIn("Write-UpdateResult 'PREPARED'", match.group(1))
        self.assertIn("could not write the PREPARED receipt", match.group(2))

    def test_no_registration_elevation_or_host_side_effects(self):
        text = script_text()
        for forbidden in ("Register-ScheduledTask", "New-ScheduledTask", "RunLevel",
                          "Restart-Computer", "Set-ExecutionPolicy", "Remove-Item -Recurse"):
            self.assertNotIn(forbidden, text)


class TestWindowsUpdateCleanup(unittest.TestCase):
    """Reporting and the one-shot task cleanup must be independent of each other."""

    def test_finally_reports_then_unregisters_independently(self):
        body = finally_body()
        self.assertIn("if ($null -ne $failure) {", body)
        self.assertIn("try {\n        Write-UpdateResult 'FAILED' $failure\n      } catch {", body)
        self.assertIn('Write-UpdateWarning "could not write the failure receipt', body)
        self.assertIn("Write-FailureDiagnostic $failure", body)
        assert_in_order(
            self,
            body,
            "Write-UpdateResult 'FAILED' $failure",
            "Write-FailureDiagnostic $failure",
            "Unregister-ScheduledTask",
        )

    def test_failure_report_is_guarded_by_a_cleanup_finally(self):
        # The failure receipt and the stderr diagnostic can both throw (an unwritable receipt,
        # an unavailable stderr). They therefore sit in one try whose finally unregisters the
        # one-shot task, so a failing report can never skip the cleanup.
        body = finally_body()
        report = body.index("if ($null -ne $failure) {")
        diagnostic = body.index("Write-FailureDiagnostic $failure")
        self.assertIn("} finally {\n    try {\n      Unregister-ScheduledTask", body[diagnostic:],
                      "the one-shot cleanup must run in the finally of the failure report")
        self.assertLess(report, diagnostic)

    def test_one_shot_task_cleanup_never_depends_on_the_outcome(self):
        body = finally_body()
        unregister = body.index("Unregister-ScheduledTask")
        self.assertIn("} catch {", body[unregister:],
                      "a failing cleanup must be caught and reported, never propagated")
        self.assertIn("could not unregister its one-shot task", body[unregister:])

    def test_native_windows_companion_test_is_shipped(self):
        self.assertTrue(NATIVE_TEST_PATH.is_file())


class TestWindowsUpdateHandoffGate(unittest.TestCase):
    """The one-shot task starts while `PREPARING` is still persisted, so only the permit gates it."""

    def test_matching_permit_is_awaited_before_the_state_is_required(self):
        text = script_text()
        permit_wait = text.index("while (-not (Test-Path -LiteralPath $permitPath -PathType Leaf))")
        accepted = text.index("if ([string]$handoff.state -ne 'ACCEPTED')")
        self.assertLess(
            permit_wait, accepted, "the ACCEPTED state must be required only after the permit")
        self.assertNotIn(
            "is not accepted",
            text[:permit_wait],
            "a transient PREPARING at job start must never fail the job early")

    def test_operation_id_is_pre_checked_and_the_transitioned_handoff_is_re_read(self):
        text = script_text()
        permit_wait = text.index("while (-not (Test-Path -LiteralPath $permitPath -PathType Leaf))")
        self.assertIn(
            "if ([string]$handoff.operationId -ne $operationId) { Fail-Closed 'handoff metadata does not match the operation' }",
            text[:permit_wait],
            "the operationId must be pre-checked before the permit wait")
        self.assertGreaterEqual(
            text[:permit_wait].count("$handoff = Read-Handoff"),
            1,
            "the handoff must be read before the permit wait")
        self.assertGreaterEqual(
            text[permit_wait:].count("$handoff = Read-Handoff"),
            1,
            "the possibly transitioned handoff must be re-read after the permit arrives")

    def test_missing_permit_fails_closed_with_the_permit_reason(self):
        text = script_text()
        self.assertIn(
            "Fail-Closed 'the daemon did not release the update permit in time'", text)
        self.assertIn("if ((Get-Content -LiteralPath $permitPath -Raw).Trim() -ne $operationId)", text)
        self.assertIn("Fail-Closed 'the update permit does not match the operation'", text)
        self.assertIn("Fail-Closed 'the update permit is not a regular file'", text)

    def test_managed_runtime_must_be_recorded_by_the_handoff(self):
        text = script_text()
        self.assertIn(
            "if ([string]::IsNullOrEmpty([string]$handoff.javaExecutable)) { Fail-Closed 'handoff metadata does not record the managed runtime' }",
            text)


class TestWindowsUpdateTaskOwnership(unittest.TestCase):
    """The real current installer task must be re-verified at the exact root path before anything stops."""

    def test_task_lookup_uses_the_exact_root_path_and_never_swallows_scheduler_errors(self):
        text = script_text()
        self.assertIn("$taskPath = '\\'", text)
        self.assertIn("function Find-ManagedTask([string]$TaskName) {", text)
        self.assertIn("Get-ScheduledTask -ErrorAction Stop |", text)
        self.assertIn(
            "Where-Object { $_.TaskPath -eq $taskPath -and $_.TaskName -eq $TaskName }", text)
        # A name-filtered query reports an absent task as an error; that is only safe while a
        # genuine scheduler failure still terminates, so it must never be silenced.
        self.assertNotRegex(text, r"Get-ScheduledTask[^\n]*-ErrorAction\s+SilentlyContinue")
        self.assertNotRegex(text, r"Get-ScheduledTask\s+-TaskName")

    def test_task_mutations_target_the_exact_root_path(self):
        text = script_text()
        self.assertIn("Stop-ScheduledTask -TaskName $taskName -TaskPath $taskPath", text)
        self.assertIn("Start-ScheduledTask -TaskName $taskName -TaskPath $taskPath", text)

    def test_stop_waits_for_both_running_and_queued(self):
        text = script_text()
        self.assertIn("[string]$current.State -notin @('Running', 'Queued')", text)

    def test_ownership_checks_cover_description_single_action_runtime_arguments_and_cwd(self):
        text = script_text()
        self.assertIn(
            '$taskDescription = "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$sid"',
            text)
        self.assertIn(
            "if ([string]$task.Description -ne $taskDescription) { Fail-Closed 'managed scheduled task is not owned by the installer' }",
            text)
        self.assertIn("if ($actions.Count -ne 1)", text)
        self.assertIn(
            "Test-SamePath -Left ([string]$action.Execute) -Right ([string]$handoff.javaExecutable)",
            text,
            "the action must run the managed JVM recorded by the handoff, never a copied foreign runtime")
        self.assertIn("if ([string]$action.Arguments -ne $expectedArguments)", text)
        self.assertIn(
            "Test-SamePath -Left ([string]$action.WorkingDirectory) -Right $installedDir", text)
        self.assertNotIn("node", text.lower())

    def test_expected_arguments_are_the_installers_base64_config_invocation(self):
        text = script_text()
        self.assertIn("'-jar kk-studio-daemon.jar --base64-args '", text)
        self.assertIn("[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('--config'))", text)
        self.assertIn(
            "[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($configPath))", text)
        self.assertIn("$configPath = Join-Path $installRoot 'daemon.json'", text)

    def test_ownership_failure_is_reported_before_any_destructive_action(self):
        text = script_text()
        stop = text.index("Stop-ScheduledTask -TaskName $taskName -TaskPath $taskPath")
        self.assertLess(
            text.index("if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }"),
            stop)
        self.assertLess(text.index("if ([string]$task.Description -ne $taskDescription)"), stop)
        self.assertLess(text.index("Test-SamePath -Left ([string]$action.WorkingDirectory)"), stop)


class TestWindowsUpdateBackup(unittest.TestCase):
    """Every applied update gets its own unique backup and the installed binary is never moved aside."""

    def test_guid_backup_is_created_without_reuse_and_copied(self):
        text = script_text()
        self.assertIn("'backup-' + [Guid]::NewGuid().ToString('N')", text)
        self.assertIn("New-Item -ItemType Directory -Path $backupDir | Out-Null", text)
        self.assertNotIn(
            "New-Item -ItemType Directory -Force",
            text,
            "a generated backup directory must never reuse an existing one")
        self.assertIn(
            "Copy-Item -LiteralPath $InstalledJar -Destination (Join-Path $backupDir 'previous')",
            text)
        self.assertLess(
            text.index("Copy-Item -LiteralPath $InstalledJar"),
            text.index("[IO.File]::Replace("),
            "the backup must be copied before the installed jar is replaced")
        self.assertNotIn("Remove-Item -Recurse", text)


class TestWindowsUpdateReplacement(unittest.TestCase):
    """The staged jar must atomically replace the installed jar on the same volume."""

    REPLACE = "[IO.File]::Replace($StagedJar, $InstalledJar, [NullString]::Value)"

    def test_staged_jar_atomically_replaces_the_installed_jar(self):
        text = script_text()
        self.assertIn(self.REPLACE, text)
        self.assertNotIn(
            "Move-Item", text, "the replacement must not move the installed jar aside")
        self.assertNotIn(
            "Remove-Item", text, "the installed jar must never be deleted before replacement")
        self.assertIn("$step = 'replace'", text)

    def test_replacement_passes_a_genuine_null_backup_name(self):
        # The third File.Replace argument is the optional backup file name. A `$null` literal
        # binds to the empty string for the [string] parameter, which File.Replace rejects
        # ("The value cannot be an empty string"), so only [NullString]::Value is a genuine null.
        text = script_text()
        self.assertIn(self.REPLACE, text)
        self.assertNotIn(
            "[IO.File]::Replace($StagedJar, $InstalledJar, $null)",
            text,
            "a $null literal is not a genuine null for the File.Replace backup argument")

    def test_replacement_keeps_the_installed_jar_untouched_until_it_succeeds(self):
        text = script_text()
        backup = text.index("Copy-Item -LiteralPath $InstalledJar")
        replace = text.index(self.REPLACE)
        restart = text.index("Start-ScheduledTask -TaskName $taskName -TaskPath $taskPath")
        self.assertLess(backup, replace)
        self.assertLess(replace, restart)


class TestWindowsUpdateNativeFixture(unittest.TestCase):
    """The Windows-only companion must exercise the same contract on the Windows matrix leg."""

    def test_native_fixture_covers_the_handoff_permit_and_task_contract(self):
        text = NATIVE_TEST_PATH.read_text(encoding="utf-8")
        for needle in ("handoff.json", "permit", "ACCEPTED", "PREPARING",
                       "TaskPath", "Running", "Queued", "backup-", "previous",
                       "handoff metadata is not accepted",
                       "the daemon did not release the update permit in time",
                       "managed scheduled task is missing",
                       "FileShare]::None", "CopyItem:"):
            self.assertIn(needle, text, f"the native fixture must cover {needle!r}")

    def test_native_fixture_propagates_the_update_script_exit_code(self):
        # The fixture must invoke the production script with the call operator: a dot-sourced
        # script cannot propagate its fail-closed `exit 1` (the host still exits 0), which would
        # make every "exits non-zero" assertion pass silently. The child exit code is relayed
        # explicitly, so the runner cannot report a false success either.
        text = NATIVE_TEST_PATH.read_text(encoding="utf-8")
        self.assertIn(
            "& '__SCRIPT__' -StagedJar '__STAGED__' -InstalledJar '__INSTALLED__'", text)
        self.assertNotIn(". '__SCRIPT__'", text)
        self.assertIn("$LASTEXITCODE = 0", text)
        self.assertIn("exit $LASTEXITCODE", text)

    def test_native_fixture_keeps_stub_state_out_of_the_callee_script_scope(self):
        # Under the call operator the production script runs in a child scope, where `$script:`
        # resolves to the callee's scope. Stub state that straddles the invocation must live in
        # the global scope, or the queued-state wait and the locked-handle replace case would
        # silently stop working.
        text = NATIVE_TEST_PATH.read_text(encoding="utf-8")
        self.assertNotIn("$script:stateIndex", text)
        self.assertNotIn("$script:installedLock", text)
        self.assertIn("$global:stateIndex", text)
        self.assertIn("$global:installedLock", text)

    def test_native_fixture_does_not_register_or_touch_real_tasks(self):
        text = NATIVE_TEST_PATH.read_text(encoding="utf-8")
        self.assertNotIn("Register-ScheduledTask", text)
        self.assertIn("Remove-Case", text)


if __name__ == "__main__":
    unittest.main()
