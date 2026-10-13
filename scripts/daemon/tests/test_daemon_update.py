"""Managed update script (`update.sh`) contracts.

The update script is applied by an independent short-lived OS job. These tests drive it
with a disposable HOME, a temporary handoff directory and **stubbed** OS tooling, so no
real systemd/launchd unit is ever registered, stopped or restarted and no path outside the
temporary fixture is touched.

Covered: managed unit/agent ownership checks, stop/backup/replace/restart failures, a
failure raised while the backup directory cannot be created (an I/O abort that must still
leave a receipt), an unreported non-zero abort (the EXIT trap), a failing receipt write
that must not swallow the failure, and a failing success receipt that must not become a
false update failure.
"""

import json
import os
from pathlib import Path
import subprocess
import tempfile
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
                 uname_mode="ok", mv_fails=False, mkdir_fails=False,
                 result_is_directory=False):
        self.root = Path(root).resolve()
        self.home = self.root / "home"
        self.bin = self.root / "bin"
        self.operation_id = "11111111-1111-1111-1111-111111111111"
        self.update_dir = self.root / "updates" / self.operation_id
        self.backup_dir = self.update_dir / "backup"
        self.staged = self.update_dir / "kk-studio-daemon-v1.0.9.jar"
        self.installed = self.root / "lib" / "kk-studio-daemon.jar"
        self.result = self.update_dir / "update-result.json"
        self.record = self.root / "calls.txt"
        self.operating_system = operating_system
        self.systemctl_mode = systemctl_mode
        self.uname_mode = uname_mode
        self.mv_fails = mv_fails
        self.mkdir_fails = mkdir_fails
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
        if self.mkdir_fails:
            write_tool(self.bin / "mkdir", "exit 1\n")
        if self.mv_fails:
            write_tool(self.bin / "mv", "exit 1\n")
        # The artifacts are fixture placeholders; the update script never reads their bytes.
        self.staged.write_text("staged-release-jar", encoding="utf-8")
        self.installed.write_text("installed-release-jar", encoding="utf-8")

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
            "KK_STUDIO_UPDATE_GRACE_SECONDS": "0",
        }

    def run(self):
        if self.result_is_directory:
            self.result.mkdir()
        return subprocess.run(
            ["/bin/bash", str(UPDATE_SCRIPT), str(self.staged), str(self.installed)],
            cwd=self.root,
            env=self.environment(),
            text=True,
            capture_output=True,
            start_new_session=True,
            timeout=60,
            check=False,
        )

    def result_document(self):
        return json.loads(self.result.read_text(encoding="utf-8"))

    def calls(self):
        return self.record.read_text(encoding="utf-8").splitlines()


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


class TestSystemdUpdate(UpdateScriptTestCase):

    def test_success_backs_up_replaces_and_restarts_the_managed_service(self):
        fixture = self.prepared()
        result = fixture.run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PREPARED", fixture.result_document()["phase"])
        self.assertFalse(fixture.staged.exists(), "staged artifact must be consumed")
        self.assertEqual("staged-release-jar", fixture.installed.read_text(encoding="utf-8"))
        backup = fixture.backup_dir / "kk-studio-daemon.jar"
        self.assertEqual("installed-release-jar", backup.read_text(encoding="utf-8"))
        calls = fixture.calls()
        self.assertIn(f"systemctl --user stop {SERVICE_NAME}", calls)
        self.assertIn(f"systemctl --user start {SERVICE_NAME}", calls)

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
        fixture = self.prepared(mkdir_fails=True)
        self.assert_failed(fixture, fixture.run(), "could not create the backup directory")
        self.assertEqual("installed-release-jar", fixture.installed.read_text(encoding="utf-8"))

    def test_replace_failure_is_reported(self):
        fixture = self.prepared(mv_fails=True)
        self.assert_failed(fixture, fixture.run(), "could not replace the installed artifact")
        self.assertEqual("installed-release-jar", fixture.installed.read_text(encoding="utf-8"))

    def test_restart_failure_reports_and_keeps_the_replaced_binary(self):
        # The update never rolls back automatically: the binary is already replaced, the
        # failure is reported and the previous binary remains in the backup directory.
        fixture = self.prepared(systemctl_mode="start-fail")
        self.assert_failed(fixture, fixture.run(), "could not restart the managed service")
        self.assertEqual("staged-release-jar", fixture.installed.read_text(encoding="utf-8"))
        self.assertEqual(
            "installed-release-jar",
            (fixture.backup_dir / "kk-studio-daemon.jar").read_text(encoding="utf-8"))

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
