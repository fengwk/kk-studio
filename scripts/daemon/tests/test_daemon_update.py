"""Managed update script (`update.sh`) contracts.

The update script is applied by an independent short-lived OS job that necessarily starts
*before* the daemon has finished accepting the handoff (the daemon persists `PREPARING`
first and only freezes `ACCEPTED` after the OS job has been accepted). These tests drive it
with a disposable HOME, a temporary handoff directory and **stubbed** OS tooling, so no real
systemd/launchd unit is ever registered, stopped or restarted and no path outside the
temporary fixture is touched.

Covered: the private-permit-first gate (the transient `PREPARING` observed at job start is
never rejected, and only after the matching permit arrives is the state required to be
`ACCEPTED`), a missing/mismatched/unaccepted handoff or a permit that never arrives
performing zero stop/backup/replace, a permit that arrives late, managed unit/agent
ownership checks, stop/backup/replace/restart failures, a failure raised while the backup
directory cannot be created (an I/O abort that must still leave a receipt), an unreported
non-zero abort (the EXIT trap), a failing receipt write that must not swallow the failure, a
failing success receipt that must not become a false update failure, the unique copy-based
backup that never overwrites a previous one and never leaves the installed binary missing,
and the stopped-then-replace ordering.
"""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
UPDATE_SCRIPT = (
    REPOSITORY_ROOT
    / "harness/daemon/src/main/resources/fun/fengwk/kkstudio/harness/daemon/update/update.sh"
)

UNIT_MARKER = "# Managed by scripts/daemon/install.sh"
PLIST_MARKER = "<!-- Managed by scripts/daemon/install.sh -->"
SERVICE_NAME = "kk-studio-daemon.service"
LAUNCHD_LABEL = "fun.fengwk.kkstudio.environment-daemon"


def write_tool(path, body):
    path.write_text("#!/bin/sh\n" + body, encoding="utf-8")
    path.chmod(0o755)


class UpdateFixture:
    """Disposable handoff directory plus stubbed `uname`/`systemctl`/`launchctl`."""

    def __init__(self, root, operating_system="Linux", systemctl_mode="ok",
                 uname_mode="ok", mv_mode="ok", mktemp_mode="ok",
                 handoff_state="ACCEPTED", handoff_operation=None, permit="match",
                 permit_wait=10, result_is_directory=False):
        self.root = Path(root).resolve()
        self.home = self.root / "home"
        self.bin = self.root / "bin"
        self.operation_id = "11111111-1111-1111-1111-111111111111"
        self.update_dir = self.root / "updates" / self.operation_id
        self.staged = self.update_dir / "kk-studio-daemon-v1.0.9.jar"
        self.installed = self.root / "lib" / "kk-studio-daemon.jar"
        self.handoff = self.update_dir / "handoff.json"
        self.permit = self.update_dir / "permit"
        self.result = self.update_dir / "update-result.json"
        self.record = self.root / "calls.txt"
        self.operating_system = operating_system
        self.systemctl_mode = systemctl_mode
        self.uname_mode = uname_mode
        self.mv_mode = mv_mode
        self.mktemp_mode = mktemp_mode
        self.handoff_state = handoff_state
        self.handoff_operation = handoff_operation
        self.permit_mode = permit
        self.permit_wait = permit_wait
        self.result_is_directory = result_is_directory
        self.unit = self.home / ".config/systemd/user" / SERVICE_NAME
        self.plist = self.home / "Library/LaunchAgents" / (LAUNCHD_LABEL + ".plist")
        self.domain = "gui/" + str(os.getuid())
        self._build()

    def _build(self):
        for directory in (self.home, self.bin, self.update_dir, self.installed.parent):
            directory.mkdir(parents=True, exist_ok=True)
        self.record.touch()
        write_tool(
            self.bin / "uname",
            'echo "${FAKE_UNAME:-Linux}"\n' if self.uname_mode == "ok" else "exit 1\n")
        # The action is the first non-option argument; match on the whole argument list so
        # `--user stop`/`--user is-active --quiet` are recognised.
        write_tool(self.bin / "systemctl", """\
echo "systemctl $*" >> "$FAKE_RECORD"
case " $* " in
  *" stop "*) [ "$FAKE_MODE" = stop-fail ] && exit 1 ;;
  *" start "*) [ "$FAKE_MODE" = start-fail ] && exit 1 ;;
  *" is-active "*) [ "$FAKE_MODE" = always-active ] && exit 0; exit 1 ;;
esac
exit 0
""")
        write_tool(self.bin / "launchctl", """\
echo "launchctl $*" >> "$FAKE_RECORD"
case "$1" in
  bootstrap) [ "$FAKE_MODE" = start-fail ] && exit 1 ;;
  print) exit 1 ;;
esac
exit 0
""")
        if self.mv_mode == "replace-fail":
            # Fail only the staged->installed replace so the copy-based backup still happens;
            # this is the failure that must leave the old release both installed and backed up.
            write_tool(self.bin / "mv", """\
echo "mv $*" >> "$FAKE_RECORD"
last=
for argument in "$@"; do last=$argument; done
[ "$last" = "$FAKE_INSTALLED" ] && exit 1
exec /bin/mv "$@"
""")
        if self.mktemp_mode == "fail":
            write_tool(self.bin / "mktemp", "exit 1\n")
        # The artifacts are fixture placeholders; the update script never reads their bytes.
        self.staged.write_text("staged-release-jar", encoding="utf-8")
        self.installed.write_text("installed-release-jar", encoding="utf-8")
        if self.handoff_state != "missing":
            self.write_handoff(self.handoff_state)
        if self.permit_mode == "match":
            self.permit.write_text(self.operation_id + "\n", encoding="utf-8")
        elif self.permit_mode == "wrong":
            self.permit.write_text("22222222-2222-2222-2222-222222222222\n", encoding="utf-8")
        elif self.permit_mode == "directory":
            self.permit.mkdir()

    def write_handoff(self, state):
        """(Re)write the handoff metadata, e.g. to simulate the PREPARING->ACCEPTED transition."""
        self.handoff.write_text(
            json.dumps({
                "state": state,
                "operationId": self.handoff_operation or self.operation_id,
                "targetVersion": "1.0.9",
            }),
            encoding="utf-8")

    def write_service_definition(self, marker):
        if self.operating_system == "Darwin":
            self.plist.parent.mkdir(parents=True, exist_ok=True)
            self.plist.write_text("<?xml version=\"1.0\"?>\n" + marker + "\n", encoding="utf-8")
        else:
            self.unit.parent.mkdir(parents=True, exist_ok=True)
            self.unit.write_text(marker + "\nUnit fixture\n", encoding="utf-8")

    def environment(self):
        return {
            "HOME": str(self.home),
            "PATH": f"{self.bin}:/usr/bin:/bin",
            "FAKE_RECORD": str(self.record),
            "FAKE_MODE": self.systemctl_mode,
            "FAKE_UNAME": self.operating_system,
            "FAKE_INSTALLED": str(self.installed),
            "KK_STUDIO_UPDATE_PERMIT_WAIT_SECONDS": str(self.permit_wait),
        }

    def command(self):
        return ["/bin/bash", str(UPDATE_SCRIPT), str(self.staged), str(self.installed)]

    def run(self):
        if self.result_is_directory:
            self.result.mkdir()
        return subprocess.run(
            self.command(),
            cwd=self.root,
            env=self.environment(),
            text=True,
            capture_output=True,
            start_new_session=True,
            timeout=60,
            check=False,
        )

    def start(self):
        return subprocess.Popen(
            self.command(),
            cwd=self.root,
            env=self.environment(),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )

    def result_document(self):
        return json.loads(self.result.read_text(encoding="utf-8"))

    def calls(self):
        return self.record.read_text(encoding="utf-8").splitlines()

    def backup_directories(self):
        return sorted(self.update_dir.glob("backup.*"))


class UpdateScriptTestCase(unittest.TestCase):

    def fixture(self, **overrides):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        return UpdateFixture(temporary.name, **overrides)

    def prepared(self, **overrides):
        fixture = self.fixture(**overrides)
        fixture.write_service_definition(
            PLIST_MARKER if fixture.operating_system == "Darwin" else UNIT_MARKER)
        return fixture

    def assert_failed(self, fixture, result, message):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        document = fixture.result_document()
        self.assertEqual("FAILED", document["phase"])
        self.assertEqual(fixture.operation_id, document["operationId"])
        self.assertIn(message, document["message"])

    def assert_no_destructive_action(self, fixture):
        """A rejected handoff/permit must produce zero stop, backup or replace."""
        self.assertEqual([], fixture.backup_directories())
        self.assertEqual("installed-release-jar", fixture.installed.read_text(encoding="utf-8"))
        self.assertTrue(fixture.staged.exists(), "the staged artifact must not be consumed")
        for call in fixture.calls():
            self.assertNotIn(" stop ", f" {call} ", "no service may be stopped")


class TestHandoffGate(UpdateScriptTestCase):
    """Only an operation whose matching permit arrived and whose handoff is then ACCEPTED may replace anything."""

    def test_missing_handoff_performs_no_destructive_action(self):
        fixture = self.prepared(handoff_state="missing")
        self.assert_failed(fixture, fixture.run(), "handoff metadata is missing")
        self.assert_no_destructive_action(fixture)

    def test_mismatched_operation_performs_no_destructive_action(self):
        fixture = self.prepared(handoff_operation="22222222-2222-2222-2222-222222222222")
        self.assert_failed(fixture, fixture.run(), "handoff metadata does not match the operation")
        self.assert_no_destructive_action(fixture)

    def test_unaccepted_handoff_performs_no_destructive_action(self):
        # The permit is already present, so the job reaches the state check and must refuse:
        # only ACCEPTED may replace the binary.
        fixture = self.prepared(handoff_state="PREPARING")
        self.assert_failed(fixture, fixture.run(), "handoff metadata is not accepted")
        self.assert_no_destructive_action(fixture)

    def test_failed_handoff_performs_no_destructive_action(self):
        fixture = self.prepared(handoff_state="FAILED")
        self.assert_failed(fixture, fixture.run(), "handoff metadata is not accepted")
        self.assert_no_destructive_action(fixture)

    def test_missing_permit_times_out_without_destructive_action(self):
        # Negative control for the permit gate: a permit that never arrives must fail closed
        # instead of replacing the binary on the strength of the handoff alone.
        fixture = self.prepared(permit="absent", permit_wait=2)
        self.assert_failed(
            fixture, fixture.run(), "the daemon did not release the update permit in time")
        self.assert_no_destructive_action(fixture)

    def test_mismatched_permit_performs_no_destructive_action(self):
        fixture = self.prepared(permit="wrong")
        self.assert_failed(fixture, fixture.run(), "the update permit does not match the operation")
        self.assert_no_destructive_action(fixture)

    def test_permit_that_is_not_a_regular_file_performs_no_destructive_action(self):
        fixture = self.prepared(permit="directory")
        self.assert_failed(fixture, fixture.run(), "the update permit is not a regular file")
        self.assert_no_destructive_action(fixture)

    def test_a_late_permit_is_awaited_then_applied(self):
        fixture = self.prepared(permit="absent", permit_wait=15)
        process = fixture.start()
        # The script must be blocked in its permit wait; the permit is released afterwards.
        time.sleep(0.5)
        fixture.permit.write_text(fixture.operation_id + "\n", encoding="utf-8")
        stdout, stderr = process.communicate(timeout=30)
        self.assertEqual(0, process.returncode, stdout + stderr)
        self.assertEqual("PREPARED", fixture.result_document()["phase"])

    def test_operation_is_rechecked_after_waiting_for_the_permit(self):
        fixture = self.prepared(handoff_state="PREPARING", permit="absent", permit_wait=15)
        write_tool(
            fixture.bin / "sleep",
            'echo "permit-wait" >> "$FAKE_RECORD"\nexec /bin/sleep "$@"\n')
        process = fixture.start()
        try:
            deadline = time.monotonic() + 10
            while "permit-wait" not in fixture.calls():
                self.assertIsNone(process.poll(), "the job must reach the permit wait")
                self.assertLess(time.monotonic(), deadline, "the permit wait must be observable")
                time.sleep(0.01)
            fixture.handoff_operation = "22222222-2222-2222-2222-222222222222"
            fixture.write_handoff("ACCEPTED")
            fixture.permit.write_text(fixture.operation_id + "\n", encoding="utf-8")
            stdout, stderr = process.communicate(timeout=30)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()
        self.assertNotEqual(0, process.returncode, stdout + stderr)
        self.assertEqual("FAILED", fixture.result_document()["phase"])
        self.assertEqual(
            "handoff metadata does not match the operation", fixture.result_document()["message"])
        self.assert_no_destructive_action(fixture)

    def test_early_preparing_handoff_is_not_rejected_before_acceptance(self):
        # A job started during PREPARING must await the permit before requiring ACCEPTED.
        fixture = self.prepared(handoff_state="PREPARING", permit="absent", permit_wait=15)
        process = fixture.start()
        try:
            time.sleep(0.5)
            self.assertIsNone(
                process.poll(), "a transient PREPARING at job start must not fail the job")
            self.assertEqual([], fixture.calls(), "nothing may be stopped while waiting")
            self.assertFalse(fixture.result.exists(), "no receipt may be written while waiting")
            # The daemon accepted the job: freeze ACCEPTED, then release the permit.
            fixture.write_handoff("ACCEPTED")
            fixture.permit.write_text(fixture.operation_id + "\n", encoding="utf-8")
            stdout, stderr = process.communicate(timeout=30)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()
        self.assertEqual(0, process.returncode, stdout + stderr)
        self.assertEqual("PREPARED", fixture.result_document()["phase"])
        self.assertIn(f"systemctl --user stop {SERVICE_NAME}", fixture.calls())


class TestSystemdUpdate(UpdateScriptTestCase):

    def test_success_backs_up_replaces_and_restarts_the_managed_service(self):
        fixture = self.prepared()
        result = fixture.run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PREPARED", fixture.result_document()["phase"])
        self.assertFalse(fixture.staged.exists(), "staged artifact must be consumed")
        self.assertEqual("staged-release-jar", fixture.installed.read_text(encoding="utf-8"))
        backups = fixture.backup_directories()
        self.assertEqual(1, len(backups))
        self.assertEqual(
            "installed-release-jar",
            (backups[0] / "previous").read_text(encoding="utf-8"))
        calls = fixture.calls()
        self.assertIn(f"systemctl --user stop {SERVICE_NAME}", calls)
        self.assertIn(f"systemctl --user start {SERVICE_NAME}", calls)

    def test_each_run_creates_a_unique_backup_and_keeps_the_previous_one(self):
        fixture = self.prepared()
        self.assertEqual(0, fixture.run().returncode)
        # Reset the inputs for a second applied update of the same operation.
        fixture.staged.write_text("second-staged-release-jar", encoding="utf-8")
        fixture.installed.write_text("staged-release-jar", encoding="utf-8")
        self.assertEqual(0, fixture.run().returncode)
        backups = fixture.backup_directories()
        self.assertEqual(2, len(backups), "every run must leave its own unique backup")
        previous = {backup.name: (backup / "previous").read_text(encoding="utf-8")
                    for backup in backups}
        self.assertIn("installed-release-jar", previous.values())
        self.assertIn("staged-release-jar", previous.values())

    def test_success_receipt_write_failure_keeps_the_successful_outcome(self):
        # The binary is already replaced and the service restarted: a receipt that cannot be
        # written stays a warning and never becomes a false update failure.
        fixture = self.prepared(result_is_directory=True)
        result = fixture.run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("could not write the PREPARED receipt", result.stderr)
        self.assertEqual("staged-release-jar", fixture.installed.read_text(encoding="utf-8"))

    def test_missing_staged_artifact_is_reported_without_touching_installed(self):
        fixture = self.prepared()
        fixture.staged.unlink()
        self.assert_failed(fixture, fixture.run(), "staged artifact is missing")
        self.assertEqual("installed-release-jar", fixture.installed.read_text(encoding="utf-8"))

    def test_missing_installed_artifact_is_reported(self):
        fixture = self.prepared()
        fixture.installed.unlink()
        self.assert_failed(fixture, fixture.run(), "installed artifact is missing")

    def test_missing_unit_is_reported(self):
        fixture = self.fixture()
        self.assert_failed(fixture, fixture.run(), "managed systemd unit is missing")
        self.assertTrue(fixture.staged.exists(), "no destructive action before ownership check")

    def test_unit_not_owned_by_installer_is_rejected(self):
        fixture = self.fixture()
        fixture.write_service_definition("# someone else's unit")
        self.assert_failed(fixture, fixture.run(), "systemd unit is not owned by the installer")
        self.assertEqual("installed-release-jar", fixture.installed.read_text(encoding="utf-8"))

    def test_stop_failure_is_reported(self):
        fixture = self.prepared(systemctl_mode="stop-fail")
        self.assert_failed(fixture, fixture.run(), "managed service did not stop")

    def test_backup_directory_creation_failure_still_leaves_a_receipt(self):
        # An I/O abort before `fail_closed` would have reported it: unified failure handling
        # must still produce a FAILED receipt instead of exiting silently.
        fixture = self.prepared(mktemp_mode="fail")
        self.assert_failed(fixture, fixture.run(), "could not create the backup directory")
        self.assertEqual("installed-release-jar", fixture.installed.read_text(encoding="utf-8"))

    def test_replace_failure_keeps_the_old_release_installed_and_backed_up(self):
        # There is no automatic rollback: the old release stays installed *and* in its unique
        # backup (the backup is a copy), the service stays stopped (never restarted) and
        # recovery is manual.
        fixture = self.prepared(mv_mode="replace-fail")
        self.assert_failed(fixture, fixture.run(), "could not replace the installed artifact")
        backups = fixture.backup_directories()
        self.assertEqual(1, len(backups))
        self.assertEqual(
            "installed-release-jar",
            (backups[0] / "previous").read_text(encoding="utf-8"))
        self.assertEqual(
            "installed-release-jar",
            fixture.installed.read_text(encoding="utf-8"),
            "the copy-based backup must leave the installed binary in place")
        self.assertIn(f"systemctl --user stop {SERVICE_NAME}", fixture.calls())
        self.assertNotIn(f"systemctl --user start {SERVICE_NAME}", fixture.calls())

    def test_restart_failure_reports_and_keeps_the_replaced_binary(self):
        # The update never rolls back automatically: the binary is already replaced, the
        # failure is reported and the previous binary remains in the backup directory.
        fixture = self.prepared(systemctl_mode="start-fail")
        self.assert_failed(fixture, fixture.run(), "could not restart the managed service")
        self.assertEqual("staged-release-jar", fixture.installed.read_text(encoding="utf-8"))
        backups = fixture.backup_directories()
        self.assertEqual(1, len(backups))
        self.assertEqual(
            "installed-release-jar",
            (backups[0] / "previous").read_text(encoding="utf-8"))

    def test_unreported_abort_is_still_converted_into_a_receipt(self):
        # `uname` fails without any `|| fail_closed`: `set -e` aborts the script and only
        # the EXIT trap can leave evidence behind.
        fixture = self.prepared(uname_mode="fail")
        self.assert_failed(fixture, fixture.run(), "update aborted unexpectedly")

    def test_receipt_write_failure_does_not_mask_the_failure(self):
        fixture = self.prepared(result_is_directory=True)
        fixture.staged.unlink()
        result = fixture.run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("could not write the FAILED receipt", result.stderr)


class TestLaunchdUpdate(UpdateScriptTestCase):

    def test_success_bootouts_and_bootstraps_the_managed_agent(self):
        fixture = self.prepared(operating_system="Darwin")
        result = fixture.run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PREPARED", fixture.result_document()["phase"])
        calls = fixture.calls()
        self.assertIn(f"launchctl bootout {fixture.domain}/{LAUNCHD_LABEL}", calls)
        self.assertIn(f"launchctl bootstrap {fixture.domain} {fixture.plist}", calls)

    def test_agent_not_owned_by_installer_is_rejected(self):
        fixture = self.fixture(operating_system="Darwin")
        fixture.write_service_definition("<!-- someone else's agent -->")
        self.assert_failed(fixture, fixture.run(), "launch agent is not owned by the installer")

    def test_restart_failure_is_reported(self):
        fixture = self.prepared(operating_system="Darwin", systemctl_mode="start-fail")
        self.assert_failed(
            fixture, fixture.run(), "could not restart the managed launch agent")


if __name__ == "__main__":
    unittest.main()
