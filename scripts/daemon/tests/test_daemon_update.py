"""Managed UNIX update script contracts extracted from the shipped Node Daemon source.

``daemon/src/update-scripts.ts`` is the only source of the UNIX update script. The tests import
``UNIX_UPDATE_SCRIPT`` from that module through Node 24 and execute it with the system Bash, so
there is no second copy of the update facts and no Java/JAR fixture.

The update script runs as an independent short-lived OS job after the daemon accepted the
handoff. The fixture provides a disposable HOME, a temporary operation directory and stubbed
``uname``/``systemctl``/``launchctl``/``mv``/``mktemp``/``sleep`` tools, so no real service is
ever registered, stopped or restarted and no path outside the fixture is touched.

Covered: the handoff identity/state gate, the private permit wait (missing, symlinked and
directory permits fail closed; a late permit is awaited), managed unit/agent ownership,
stop/backup/replace/restart ordering and failures, the unique backup that never reuses a
previous one, and the fact that every rejected input performs zero stop/backup/replace.
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

from release_fixture import SYSTEM_BASH, node_export

UNIT_MARKER = "# Managed by scripts/daemon/install.sh"
PLIST_MARKER = "<!-- Managed by scripts/daemon/install.sh -->"
SERVICE_NAME = "kk-studio-daemon.service"
LAUNCHD_LABEL = "fun.fengwk.kkstudio.environment-daemon"


def write_tool(path, body):
    path.write_text("#!/bin/sh\n" + body, encoding="utf-8")
    path.chmod(0o755)


class UpdateFixture:
    """Disposable operation directory plus stubbed OS service tooling."""

    def __init__(self, root, operating_system="Linux", service_mode="ok",
                 mv_mode="ok", mktemp_mode="ok",
                 handoff_state="ACCEPTED", handoff_operation=None, permit="match",
                 fast_sleep=False, uname_mode="ok", result_mode="ok"):
        self.root = Path(root).resolve()
        self.home = self.root / "home"
        self.bin = self.root / "bin"
        self.operation_id = "11111111-1111-1111-1111-111111111111"
        self.update_dir = self.root / "updates" / self.operation_id
        self.staged = self.update_dir / "extract"
        self.installed = self.root / "lib"
        self.handoff = self.update_dir / "handoff.json"
        self.permit = self.update_dir / "permit"
        self.result = self.update_dir / "update-result.json"
        self.record = self.root / "calls.txt"
        self.script = self.root / "kk-studio-daemon-update.sh"
        self.operating_system = operating_system
        self.service_mode = service_mode
        self.mv_mode = mv_mode
        self.mktemp_mode = mktemp_mode
        self.handoff_state = handoff_state
        self.handoff_operation = handoff_operation
        self.permit_mode = permit
        self.uname_mode = uname_mode
        self.result_mode = result_mode
        self.unit = self.home / ".config/systemd/user" / SERVICE_NAME
        self.plist = self.home / "Library/LaunchAgents" / (LAUNCHD_LABEL + ".plist")
        self.domain = "gui/" + str(os.getuid())
        self._build(fast_sleep)

    def _build(self, fast_sleep):
        for directory in (self.home, self.bin, self.update_dir, self.staged, self.installed):
            directory.mkdir(parents=True, exist_ok=True)
        self.record.touch()
        self.script.write_text(node_export("UNIX_UPDATE_SCRIPT"), encoding="utf-8")
        self.script.chmod(0o755)
        if self.uname_mode == "fail":
            # An unguarded `os=$(uname -s)` failing under `set -e` aborts outside fail_closed.
            write_tool(self.bin / "uname", "exit 1\n")
        else:
            write_tool(self.bin / "uname", 'echo "${FAKE_UNAME:-Linux}"\n')
        write_tool(self.bin / "systemctl", """\
echo "systemctl $*" >> "$FAKE_RECORD"
state="$FAKE_SERVICE_STATE"
case " $* " in
  *" is-active "*)
    [ -f "$state/active" ] && exit 0
    exit 1
    ;;
  *" stop "*)
    [ "$FAKE_MODE" = stop-fail ] && exit 1
    [ "$FAKE_MODE" = stop-stuck ] && exit 0
    rm -f "$state/active"
    exit 0
    ;;
  *" start "*)
    [ "$FAKE_MODE" = start-fail ] && exit 1
    : > "$state/active"
    exit 0
    ;;
esac
exit 0
""")
        write_tool(self.bin / "launchctl", """\
echo "launchctl $*" >> "$FAKE_RECORD"
state="$FAKE_SERVICE_STATE"
case "$1" in
  print) [ -f "$state/active" ] && exit 0 || exit 1 ;;
  bootout)
    [ "$FAKE_MODE" = stop-stuck ] && exit 0
    rm -f "$state/active"
    exit 0
    ;;
  bootstrap)
    [ "$FAKE_MODE" = start-fail ] && exit 1
    : > "$state/active"
    exit 0
    ;;
esac
exit 0
""")
        if self.mv_mode != "ok":
            mode = self.mv_mode
            write_tool(self.bin / "mv", f"""\
echo "mv $*" >> "$FAKE_RECORD"
last=
for argument in "$@"; do last=$argument; done
if [ "{mode}" = replace-fail ] && [ "$last" = "$FAKE_INSTALLED" ]; then exit 1; fi
if [ "{mode}" = backup-fail ] && [ "$(basename "$last")" = previous ]; then exit 1; fi
exec /bin/mv "$@"
""")
        if self.mktemp_mode == "fail":
            write_tool(self.bin / "mktemp", "exit 1\n")
        if fast_sleep:
            # The production waits are 120s/30s. A no-op sleep exercises the bounded loops
            # without patching the real script text or sleeping in the suite.
            write_tool(self.bin / "sleep", "exit 0\n")
        # Placeholder distributions: the update script moves directories, never reads bytes.
        (self.staged / "main.js").write_text("staged-release-main-js\n", encoding="utf-8")
        (self.installed / "main.js").write_text("installed-release-main-js\n", encoding="utf-8")
        if self.result_mode == "unwritable":
            # A directory at the receipt path makes every receipt write fail.
            self.result.mkdir()
        if self.handoff_state != "missing":
            self.write_handoff(self.handoff_state)
        if self.permit_mode == "match":
            self.permit.write_text(self.operation_id + "\n", encoding="utf-8")
        elif self.permit_mode == "directory":
            self.permit.mkdir()
        elif self.permit_mode == "symlink":
            target = self.root / "foreign-permit"
            target.write_text(self.operation_id + "\n", encoding="utf-8")
            self.permit.symlink_to(target)

    def write_handoff(self, state, operation=None):
        """(Re)write the handoff metadata, e.g. to simulate PREPARING -> ACCEPTED."""
        self.handoff.write_text(
            json.dumps({
                "state": state,
                "operationId": operation or self.handoff_operation or self.operation_id,
                "targetVersion": "1.1.1",
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
            "FAKE_MODE": self.service_mode,
            "FAKE_UNAME": self.operating_system,
            "FAKE_INSTALLED": str(self.installed),
            "FAKE_SERVICE_STATE": str(self.root / "service-state"),
        }

    def command(self):
        return [SYSTEM_BASH, str(self.script), str(self.update_dir), str(self.staged), str(self.installed)]

    def run(self):
        (self.root / "service-state").mkdir(exist_ok=True)
        (self.root / "service-state" / "active").touch()
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
        (self.root / "service-state").mkdir(exist_ok=True)
        (self.root / "service-state" / "active").touch()
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
        self.assertEqual(
            "installed-release-main-js\n",
            (fixture.installed / "main.js").read_text(encoding="utf-8"))
        self.assertTrue(
            (fixture.staged / "main.js").exists(), "the staged distribution must not be consumed")
        for call in fixture.calls():
            self.assertNotIn(" stop ", f" {call} ", "no service may be stopped")
            self.assertNotIn("bootout", call, "no launch agent may be booted out")


class TestHandoffGate(UpdateScriptTestCase):
    """Only an operation with a matching private permit and an ACCEPTED handoff may replace anything."""

    def test_missing_handoff_performs_no_destructive_action(self):
        fixture = self.prepared(handoff_state="missing")
        self.assert_failed(fixture, fixture.run(), "handoff metadata is missing")
        self.assert_no_destructive_action(fixture)

    def test_mismatched_operation_performs_no_destructive_action(self):
        fixture = self.prepared(handoff_operation="22222222-2222-2222-2222-222222222222")
        self.assert_failed(fixture, fixture.run(), "handoff metadata does not match the operation")
        self.assert_no_destructive_action(fixture)

    def test_unaccepted_handoff_performs_no_destructive_action(self):
        fixture = self.prepared(handoff_state="PREPARING")
        self.assert_failed(fixture, fixture.run(), "handoff metadata is not accepted")
        self.assert_no_destructive_action(fixture)

    def test_failed_handoff_performs_no_destructive_action(self):
        fixture = self.prepared(handoff_state="FAILED")
        self.assert_failed(fixture, fixture.run(), "handoff metadata is not accepted")
        self.assert_no_destructive_action(fixture)

    def test_early_preparing_without_a_permit_fails_closed_at_the_permit_wait(self):
        # The job starts while the daemon still persists PREPARING, so the operation identity
        # alone is checked first: an early PREPARING must reach the permit wait (and only the
        # missing permit may fail) instead of being rejected as an identity mismatch.
        fixture = self.prepared(handoff_state="PREPARING", permit="absent", fast_sleep=True)
        self.assert_failed(
            fixture, fixture.run(), "the daemon did not release the update permit in time")
        self.assert_no_destructive_action(fixture)

    def test_permit_content_mismatch_performs_no_destructive_action(self):
        # The permit names one operation: a readable permit for another operation is never
        # treated as a release for this one.
        fixture = self.prepared()
        fixture.permit.write_text("22222222-2222-2222-2222-222222222222\n", encoding="utf-8")
        self.assert_failed(fixture, fixture.run(), "the update permit does not match the operation")
        self.assert_no_destructive_action(fixture)

    def test_empty_permit_performs_no_destructive_action(self):
        fixture = self.prepared()
        fixture.permit.write_text("", encoding="utf-8")
        self.assert_failed(fixture, fixture.run(), "the update permit does not match the operation")
        self.assert_no_destructive_action(fixture)

    def test_handoff_identity_change_during_the_permit_wait_is_rejected(self):
        # The snapshot is re-read after the permit: an identity that changed while waiting must
        # still fail closed with zero stop/backup/replace.
        fixture = self.prepared(permit="absent")
        process = fixture.start()
        time.sleep(0.5)
        fixture.write_handoff("ACCEPTED", operation="22222222-2222-2222-2222-222222222222")
        fixture.permit.write_text(fixture.operation_id + "\n", encoding="utf-8")
        stdout, stderr = process.communicate(timeout=30)
        self.assertNotEqual(0, process.returncode, stdout + stderr)
        self.assertEqual("FAILED", fixture.result_document()["phase"])
        self.assertIn("handoff metadata does not match the operation",
                      fixture.result_document()["message"])
        self.assert_no_destructive_action(fixture)

    def test_early_preparing_handoff_applies_once_the_permit_arrives_after_acceptance(self):
        # The production sequence: the job starts with PREPARING, the daemon then freezes
        # ACCEPTED and releases the permit, and only then does the job stop and replace.
        fixture = self.prepared(handoff_state="PREPARING", permit="absent")
        process = fixture.start()
        time.sleep(0.5)
        self.assertFalse(fixture.result.exists(), "an early PREPARING must not report a failure")
        fixture.write_handoff("ACCEPTED")
        fixture.permit.write_text(fixture.operation_id + "\n", encoding="utf-8")
        stdout, stderr = process.communicate(timeout=30)
        self.assertEqual(0, process.returncode, stdout + stderr)
        self.assertEqual("PREPARED", fixture.result_document()["phase"])
        self.assertIn(f"systemctl --user stop {SERVICE_NAME}", fixture.calls())
        self.assertEqual(
            "staged-release-main-js\n",
            (fixture.installed / "main.js").read_text(encoding="utf-8"))

    def test_missing_permit_times_out_without_destructive_action(self):
        # Negative control for the permit gate: no permit means no replacement, even with an
        # ACCEPTED handoff.
        fixture = self.prepared(permit="absent", fast_sleep=True)
        self.assert_failed(
            fixture, fixture.run(), "the daemon did not release the update permit in time")
        self.assert_no_destructive_action(fixture)

    def test_directory_permit_is_not_a_release(self):
        fixture = self.prepared(permit="directory", fast_sleep=True)
        self.assert_failed(
            fixture, fixture.run(), "the daemon did not release the update permit in time")
        self.assert_no_destructive_action(fixture)

    def test_symlinked_permit_is_rejected(self):
        fixture = self.prepared(permit="symlink")
        self.assert_failed(fixture, fixture.run(), "the update permit is not a regular file")
        self.assert_no_destructive_action(fixture)

    def test_a_late_permit_is_awaited_then_applied(self):
        fixture = self.prepared(permit="absent")
        process = fixture.start()
        # The script must be blocked in its permit wait; the permit is released afterwards.
        time.sleep(0.5)
        fixture.permit.write_text(fixture.operation_id + "\n", encoding="utf-8")
        stdout, stderr = process.communicate(timeout=30)
        self.assertEqual(0, process.returncode, stdout + stderr)
        self.assertEqual("PREPARED", fixture.result_document()["phase"])
        self.assertIn(f"systemctl --user stop {SERVICE_NAME}", fixture.calls())

    def test_early_preparing_handoff_is_not_rejected_after_acceptance(self):
        # A permit is not released until the daemon successfully starts the job and freezes
        # ACCEPTED, so a later re-run sees the accepted handoff and applies normally.
        fixture = self.prepared(handoff_state="PREPARING", permit="absent")
        fixture.write_handoff("ACCEPTED")
        fixture.permit.write_text(fixture.operation_id + "\n", encoding="utf-8")
        result = fixture.run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PREPARED", fixture.result_document()["phase"])


class TestSystemdUpdate(UpdateScriptTestCase):

    def test_success_backs_up_replaces_and_restarts_the_managed_service(self):
        fixture = self.prepared()
        result = fixture.run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PREPARED", fixture.result_document()["phase"])
        self.assertFalse(fixture.staged.exists(), "staged distribution must be consumed")
        self.assertEqual(
            "staged-release-main-js\n",
            (fixture.installed / "main.js").read_text(encoding="utf-8"))
        backups = fixture.backup_directories()
        self.assertEqual(1, len(backups))
        self.assertEqual(
            "installed-release-main-js\n",
            (backups[0] / "previous" / "main.js").read_text(encoding="utf-8"))
        calls = fixture.calls()
        self.assertIn(f"systemctl --user stop {SERVICE_NAME}", calls)
        self.assertIn(f"systemctl --user start {SERVICE_NAME}", calls)
        self.assertLess(
            calls.index(f"systemctl --user stop {SERVICE_NAME}"),
            calls.index(f"systemctl --user start {SERVICE_NAME}"))

    def test_each_run_creates_a_unique_backup_and_keeps_the_previous_one(self):
        fixture = self.prepared()
        self.assertEqual(0, fixture.run().returncode)
        # Reset the staged input for a second applied update of the same operation.
        fixture.staged.mkdir()
        (fixture.staged / "main.js").write_text("second-staged-main-js\n", encoding="utf-8")
        self.assertEqual(0, fixture.run().returncode)
        backups = fixture.backup_directories()
        self.assertEqual(2, len(backups), "every run must leave its own unique backup")
        previous = {
            (backup / "previous" / "main.js").read_text(encoding="utf-8")
            for backup in backups
        }
        self.assertEqual({"installed-release-main-js\n", "staged-release-main-js\n"}, previous)

    def test_missing_staged_distribution_is_reported_without_touching_installed(self):
        fixture = self.prepared()
        (fixture.staged / "main.js").unlink()
        self.assert_failed(fixture, fixture.run(), "staged distribution is missing")
        self.assertEqual(
            "installed-release-main-js\n",
            (fixture.installed / "main.js").read_text(encoding="utf-8"))

    def test_missing_installed_distribution_is_reported(self):
        fixture = self.prepared()
        shutil.rmtree(fixture.installed)
        self.assert_failed(fixture, fixture.run(), "installed distribution is missing")

    def test_missing_unit_is_reported(self):
        fixture = self.fixture()
        self.assert_failed(fixture, fixture.run(), "managed systemd unit is missing")
        self.assertTrue(fixture.staged.exists(), "no destructive action before ownership check")

    def test_unit_not_owned_by_installer_is_rejected(self):
        fixture = self.fixture()
        fixture.write_service_definition("# someone else's unit")
        self.assert_failed(fixture, fixture.run(), "systemd unit is not owned by the installer")
        self.assertEqual(
            "installed-release-main-js\n",
            (fixture.installed / "main.js").read_text(encoding="utf-8"))

    def test_stop_failure_is_reported(self):
        fixture = self.prepared(service_mode="stop-fail")
        self.assert_failed(fixture, fixture.run(), "managed service did not stop")

    def test_stop_wait_times_out_when_the_service_stays_active(self):
        # `systemctl stop` returns success but the unit never becomes inactive: the bounded
        # wait must fail closed instead of replacing a running program's files.
        fixture = self.prepared(service_mode="stop-stuck", fast_sleep=True)
        self.assert_failed(fixture, fixture.run(), "managed service did not stop")
        self.assertEqual([], fixture.backup_directories())
        self.assertEqual(
            "installed-release-main-js\n",
            (fixture.installed / "main.js").read_text(encoding="utf-8"))

    def test_backup_directory_creation_failure_leaves_installed_untouched(self):
        fixture = self.prepared(mktemp_mode="fail")
        self.assert_failed(fixture, fixture.run(), "could not create the backup directory")
        self.assertEqual(
            "installed-release-main-js\n",
            (fixture.installed / "main.js").read_text(encoding="utf-8"))

    def test_backup_move_failure_leaves_installed_untouched(self):
        fixture = self.prepared(mv_mode="backup-fail")
        self.assert_failed(fixture, fixture.run(), "could not back up the installed distribution")
        self.assertEqual(
            "installed-release-main-js\n",
            (fixture.installed / "main.js").read_text(encoding="utf-8"))
        self.assertTrue((fixture.staged / "main.js").exists())

    def test_replace_failure_keeps_the_previous_distribution_in_its_backup(self):
        # There is no automatic rollback: the old distribution stays in the unique backup,
        # the staged distribution stays intact, the service stays stopped and recovery is manual.
        fixture = self.prepared(mv_mode="replace-fail")
        self.assert_failed(fixture, fixture.run(), "could not replace the installed distribution")
        backups = fixture.backup_directories()
        self.assertEqual(1, len(backups))
        self.assertEqual(
            "installed-release-main-js\n",
            (backups[0] / "previous" / "main.js").read_text(encoding="utf-8"))
        self.assertFalse(fixture.installed.exists(), "the backup move already vacated the target")
        self.assertTrue((fixture.staged / "main.js").exists())
        self.assertIn(f"systemctl --user stop {SERVICE_NAME}", fixture.calls())
        self.assertNotIn(f"systemctl --user start {SERVICE_NAME}", fixture.calls())

    def test_unexpected_exit_leaves_a_durable_failed_receipt(self):
        # `os=$(uname -s)` is outside fail_closed: an abort there must still leave a durable,
        # de-sensitised FAILED receipt through the EXIT trap, with zero destructive action.
        fixture = self.prepared(uname_mode="fail")
        result = fixture.run()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("FAILED", fixture.result_document()["phase"])
        self.assertIn("update aborted unexpectedly (exit", fixture.result_document()["message"])
        self.assert_no_destructive_action(fixture)

    def test_unwritable_failed_receipt_is_reported_without_masking_the_reason(self):
        # A receipt that cannot be written must be reported, must not truncate the diagnostic
        # and must not hide the fail-closed decision.
        fixture = self.prepared(handoff_state="missing", result_mode="unwritable")
        result = fixture.run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("could not write the FAILED receipt", result.stderr)
        self.assertIn("failed: handoff metadata is missing", result.stderr)
        self.assert_no_destructive_action(fixture)

    def test_unwritable_prepared_receipt_does_not_falsify_a_completed_update(self):
        # The distribution is already replaced and the service restarted, so a receipt that
        # cannot be written is reported but never turned into a false failure.
        fixture = self.prepared(result_mode="unwritable")
        result = fixture.run()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("could not write the PREPARED receipt", result.stderr)
        self.assertNotIn("aborted unexpectedly", result.stderr)
        self.assertFalse(fixture.staged.exists(), "the staged distribution must be consumed")
        self.assertEqual(
            "staged-release-main-js\n",
            (fixture.installed / "main.js").read_text(encoding="utf-8"))
        self.assertIn(f"systemctl --user start {SERVICE_NAME}", fixture.calls())

    def test_restart_failure_reports_and_keeps_the_replaced_distribution(self):
        # The update never rolls back automatically: the new distribution is installed, the
        # failure is reported and the previous distribution remains in the backup directory.
        fixture = self.prepared(service_mode="start-fail")
        self.assert_failed(fixture, fixture.run(), "could not restart the managed service")
        self.assertEqual(
            "staged-release-main-js\n",
            (fixture.installed / "main.js").read_text(encoding="utf-8"))
        backups = fixture.backup_directories()
        self.assertEqual(1, len(backups))
        self.assertEqual(
            "installed-release-main-js\n",
            (backups[0] / "previous" / "main.js").read_text(encoding="utf-8"))


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

    def test_stop_wait_times_out_when_the_agent_stays_loaded(self):
        fixture = self.prepared(operating_system="Darwin", service_mode="stop-stuck", fast_sleep=True)
        self.assert_failed(fixture, fixture.run(), "managed launch agent did not stop")
        self.assertEqual([], fixture.backup_directories())

    def test_restart_failure_is_reported(self):
        fixture = self.prepared(operating_system="Darwin", service_mode="start-fail")
        self.assert_failed(
            fixture, fixture.run(), "could not restart the managed launch agent")


if __name__ == "__main__":
    unittest.main()
