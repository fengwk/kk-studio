"""LaunchAgent contracts with fake Darwin/launchctl/plutil on Linux or macOS.

These are not native macOS runtime tests; macOS CI separately exercises /bin/bash 3.2
and BSD filesystem tools. No case registers a real LaunchAgent.
"""

import json
import plistlib
import shutil
import unittest

from test_daemon_install import UnixInstallContracts
from unix_release_fixture import DarwinFixture, FixtureTestCase, LAUNCHD_LABEL, PLIST_MARKER, STUDIO_URL


class TestMacosInstall(UnixInstallContracts, FixtureTestCase):
    fixture_type = DarwinFixture

    def test_direct_java_plist_escaping_and_lifecycle_security(self):
        fixture = self.fixture()
        jdk = fixture.root / 'jdk spaces & <tag> "quoted" \'apos\' 100% $HOME'
        shutil.copytree(fixture.jdk, jdk)
        result = fixture.install(**{"java-home": str(jdk)})
        self.assert_ok(result)
        text = fixture.plist.read_text()
        self.assertEqual(PLIST_MARKER, text.splitlines()[1])
        data = plistlib.loads(fixture.plist.read_bytes())
        self.assertEqual([str(jdk / "bin/java"), "-jar", str(fixture.jar),
                          "--config", str(fixture.config)], data["ProgramArguments"])
        self.assertEqual(LAUNCHD_LABEL, data["Label"])
        self.assertTrue(data["RunAtLoad"])
        self.assertEqual({"SuccessfulExit": False}, data["KeepAlive"])
        self.assertEqual(10, data["ThrottleInterval"])
        self.assertEqual(63, data["Umask"])
        self.assertEqual(str(fixture.home), data["WorkingDirectory"])
        self.assertEqual(str(fixture.stdout_log), data["StandardOutPath"])
        self.assertEqual(str(fixture.stderr_log), data["StandardErrorPath"])
        self.assert_private(fixture.stdout_log.parent, 0o700)
        self.assertNotIn("EnvironmentVariables", text)
        self.assertNotIn("--token", text)
        self.assertFalse(fixture.unit.exists())
        calls = fixture.calls("launchctl")
        self.assertNotIn(["bootout", fixture.target], calls)
        self.assertLess(calls.index(["bootstrap", fixture.domain, str(fixture.plist)]),
                        calls.index(["kickstart", "-p", fixture.target]))
        self.assertFalse(any("enable" in args for args in calls))

    def test_preflight_and_lint_before_bootout_and_publication(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        before = fixture.snapshot()
        fixture.reset_record()
        result = fixture.install(env={"FAKE_PLUTIL_MODE": "fail"})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, fixture.snapshot())
        self.assert_no_switch(fixture)
        self.assertTrue((fixture.root / "loaded").exists())
        self.assert_clean(fixture)
        fixture.reset_record()
        self.assert_ok(fixture.install())
        records = fixture.records()
        checked = next(i for i, item in enumerate(records) if "--check-config" in item["argv"])
        lint = fixture.tools().index("plutil")
        bootout = next(i for i, item in enumerate(records) if item["argv"] == ["bootout", fixture.target])
        self.assertLess(checked, lint)
        self.assertLess(lint, bootout)

    def test_domain_and_loaded_foreign_job_conflicts_precede_download(self):
        fixture = self.fixture()
        result = fixture.install(env={"FAKE_LAUNCHCTL_MODE": "no-domain"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("GUI domain", result.stderr)
        self.assertNotIn("curl", fixture.tools())
        self.assertFalse(fixture.install_root.exists())
        (fixture.root / "loaded").touch()
        for action in ("install", "uninstall"):
            result = fixture.install() if action == "install" else fixture.run(action)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("loaded but its definition is absent", result.stderr)
            self.assertIn(fixture.target, result.stderr)
            self.assertIn(str(fixture.plist), result.stderr)
            self.assertIn("下一步", result.stderr)
            self.assertNotIn("curl", fixture.tools())
            self.assert_no_switch(fixture)

    def test_failed_or_stuck_bootout_preserves_every_file_install_and_uninstall(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        before = fixture.snapshot()
        for mode in ("bootout-fail", "bootout-stuck"):
            for action in ("install", "uninstall"):
                fixture.reset_record()
                env = {"FAKE_LAUNCHCTL_MODE": mode}
                result = fixture.install(env=env) if action == "install" else fixture.run(action, env=env)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(before, fixture.snapshot())
                self.assertTrue((fixture.root / "loaded").exists())
                self.assertFalse(any("bootstrap" in args for args in fixture.calls("launchctl")))
                self.assert_clean(fixture)

    def test_delayed_unload_is_observed_before_bootstrap_and_uninstall(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        for action in ("install", "uninstall"):
            fixture.reset_record()
            env = {"FAKE_LAUNCHCTL_MODE": "bootout-delayed", "DAEMON_VERIFY_TIMEOUT_SECONDS": "1"}
            result = fixture.install(env=env) if action == "install" else fixture.run(action, env=env)
            self.assert_ok(result)
            calls = fixture.calls("launchctl")
            stopped = calls.index(["bootout", fixture.target])
            observed = calls[stopped + 1:]
            self.assertGreaterEqual(observed.count(["print", fixture.target]), 2)
            if action == "install":
                self.assertLess(calls.index(["print", fixture.target], stopped + 1),
                                calls.index(["bootstrap", fixture.domain, str(fixture.plist)]))
            else:
                self.assertFalse(fixture.plist.exists())
                self.assertFalse(fixture.jar.exists())

    def test_postpublication_failures_have_backup_and_manual_recovery(self):
        for mode in ("bootstrap-fail", "pid-missing", "pid-malformed", "pid-dead"):
            fixture = self.fixture()
            self.assert_ok(fixture.install())
            before = fixture.snapshot()
            fixture.input_config.write_text(json.dumps({"studioUrl": STUDIO_URL, "note": "new"}))
            result = fixture.install(env={"FAKE_LAUNCHCTL_MODE": mode, "FAKE_JAR_BODY": "new-jar"})
            self.assertNotEqual(0, result.returncode)
            backup = next((fixture.install_root / "backups").iterdir())
            for expected in ("发布后启动或注册未完成", "未自动回滚", str(backup), str(fixture.stderr_log)):
                self.assertIn(expected, result.stderr)
            self.assertNotIn("launchctl bootout", result.stderr)
            self.assertEqual(before[str(fixture.config)], (backup / "daemon.json").read_bytes())
            self.assertEqual("new-jar", fixture.jar.read_text())
            self.assert_clean(fixture)
            self.assert_no_secret(fixture, result)

    def test_status_tails_private_logs_without_starting(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        fixture.stdout_log.write_text("fixture-stdout\n")
        fixture.stderr_log.write_text("fixture-stderr\n")
        fixture.stdout_log.chmod(0o600)
        fixture.stderr_log.chmod(0o600)
        fixture.reset_record()
        result = fixture.run("status")
        self.assert_ok(result)
        self.assertIn("fixture-stdout", result.stdout)
        self.assertIn("fixture-stderr", result.stdout)
        self.assert_no_switch(fixture)
        self.assert_ok(fixture.run("uninstall"))
        self.assertEqual("fixture-stderr\n", fixture.stderr_log.read_text())


if __name__ == "__main__":
    unittest.main()
