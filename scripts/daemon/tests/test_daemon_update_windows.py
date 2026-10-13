"""Static contracts for the Windows managed update script (`update.ps1`).

The update script runs as an independent one-shot scheduled task on Windows, where the
ScheduledTasks module does not exist on the Linux/macOS CI legs. These tests therefore
inspect the failure handling, cleanup independence and de-sensitisation ordering directly
from the shipped source, so the B03 guarantees stay verifiable wherever the suite runs.

The native companion (`test_daemon_update_windows.ps1`) drives the real script through the
ScheduledTasks API with disposable fixtures; it needs Windows PowerShell and is executed on
the Windows matrix leg.
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


if __name__ == "__main__":
    unittest.main()
