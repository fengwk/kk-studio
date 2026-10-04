"""Unix installer security/lifecycle contracts; no real downloads or user services."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest

from unix_release_fixture import (
    FixtureTestCase, INSTALL_SCRIPT, STUDIO_URL,
    TOKEN_VALUE, UNIT_MARKER, SERVICE_NAME, systemd_escape, write_executable,
)


class UnixInstallContracts:
    """Run the same private-files, preflight, backup and ownership contracts on both OS fakes."""

    def test_private_layout_and_secret_boundaries(self):
        fixture = self.fixture()
        fixture.record_filesystem_children()
        result = fixture.install(env={
            "JAVA_TOOL_OPTIONS": "unsafe-jvm-option",
            "_JAVA_OPTIONS": "unsafe-jvm-option",
            "JDK_JAVA_OPTIONS": "unsafe-jvm-option",
        })
        self.assert_ok(result)
        self.assertEqual(fixture.jar_body, fixture.jar.read_text())
        self.assertEqual(fixture.input_config.read_bytes(), fixture.config.read_bytes())
        self.assertEqual(fixture.input_token.read_bytes(), fixture.token.read_bytes())
        for path in (fixture.install_root, fixture.jar.parent):
            self.assert_private(path, 0o700)
        for path in (fixture.config, fixture.token):
            self.assert_private(path, 0o600)
        self.assert_private(fixture.jar, 0o644)
        self.assert_private(fixture.service, 0o644)
        self.assertFalse((fixture.home / ".local").exists())
        self.assertIn("READY in Studio", result.stdout)
        self.assert_no_secret(fixture, result)
        self.assert_clean(fixture)
        checks = [args for args in fixture.calls("java") if "--check-config" in args]
        self.assertEqual(1, len(checks))
        self.assertEqual(["-jar", checks[0][1], "--check-config", checks[0][3]], checks[0])
        checked = next(i for i, item in enumerate(fixture.records())
                       if "--check-config" in item["argv"])
        publication = next(i for i, item in enumerate(fixture.records()) if item["tool"] == "mv")
        self.assertLess(checked, publication)
        self.assertTrue(set(fixture.tools()) >= {"cp", "mv", "chmod", "mktemp", "stat"})

    def test_replacement_backs_up_current_root_files_and_preserves_data(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        old = fixture.snapshot()
        durable = fixture.install_root / "resources/state"
        durable.parent.mkdir(mode=0o700)
        durable.write_text("durable")
        fixture.input_config.write_text(json.dumps({"studioUrl": STUDIO_URL, "note": "new"}))
        fixture.input_token.write_text("replacement-fixture-token")
        for attempt in range(2):
            result = fixture.install(env={"FAKE_JAR_BODY": "new-release-jar"})
            self.assert_ok(result)
            backups = sorted((fixture.install_root / "backups").iterdir())
            self.assertEqual(attempt + 1, len(backups))
            for backup in backups:
                self.assert_private(backup, 0o700)
                for path in backup.iterdir():
                    self.assert_private(path, 0o600)
            if attempt == 0:
                for name in ("daemon.json", "daemon.token", "kk-studio-daemon.jar"):
                    source = next(key for key in old if Path(key).name == name)
                    self.assertEqual(old[source], (backups[0] / name).read_bytes())
            self.assertEqual("durable", durable.read_text())
            self.assertEqual("new-release-jar", fixture.jar.read_text())
            self.assertEqual(fixture.input_config.read_bytes(), fixture.config.read_bytes())
            self.assertEqual(fixture.input_token.read_bytes(), fixture.token.read_bytes())
        self.assert_clean(fixture)

    def test_preflight_errors_preserve_running_installation(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        before = fixture.snapshot()
        for mode in ("old-jar", "check-fail", "check-unexpected", "version-fail", "jdk17"):
            with self.subTest(mode=mode):
                fixture.reset_record()
                result = fixture.install(env={"FAKE_JAVA_MODE": mode})
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(before, fixture.snapshot())
                self.assertFalse((fixture.install_root / "backups").exists())
                self.assert_no_switch(fixture)
                self.assert_no_secret(fixture, result)
                self.assert_clean(fixture)
                if mode == "old-jar":
                    self.assertIn("--check-config", result.stderr)
                    self.assertIn("retry", result.stderr)

    def test_schema_and_optional_bash_preflight_belong_to_jar(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        before = fixture.snapshot()
        for text in ('{"studioUrl":"https://ok.invalid","unknown":1}',
                     '{"studioUrl":"https://ok.invalid","studioUrl":"https://other.invalid"}',
                     '{"studioUrl": 3}', '{bad',
                     '{"studioUrl":"https://ok.invalid","bashExecutable":"/missing/bash"}'):
            fixture.input_config.write_text(text)
            fixture.reset_record()
            rejected = fixture.install()
            self.assertNotEqual(0, rejected.returncode)
            self.assertTrue(any("--check-config" in args for args in fixture.calls("java")))
            self.assertEqual(before, fixture.snapshot())
            self.assert_no_switch(fixture)
            self.assert_clean(fixture)
        fixture.input_config.write_text(json.dumps({"studioUrl": STUDIO_URL, "bashExecutable": "/bin/bash"}))
        self.assert_ok(fixture.install())
        self.assertNotIn("bashExecutable", fixture.service.read_text())

    def test_staged_inputs_metadata_and_bounds_rejected_before_download(self):
        for name in ("daemon.json", "daemon.token"):
            for kind in ("link", "empty", "public", "unreadable", "directory", "oversize"):
                with self.subTest(name=name, kind=kind):
                    fixture = self.fixture()
                    path = fixture.staging / name
                    if kind == "link":
                        path.unlink()
                        path.symlink_to(fixture.root / "missing")
                    elif kind == "empty":
                        path.write_text("")
                    elif kind == "public":
                        path.chmod(0o640)
                    elif kind == "unreadable":
                        path.chmod(0o200)
                    elif kind == "directory":
                        path.unlink()
                        path.mkdir()
                    else:
                        path.write_bytes(b"x" * (1048577 if name == "daemon.json" else 16385))
                    result = fixture.install()
                    self.assertNotEqual(0, result.returncode)
                    self.assertNotIn("curl", fixture.tools())
                    self.assertFalse(fixture.install_root.exists())
                    self.assert_no_switch(fixture)
                    self.assert_no_secret(fixture, result)
        fixture = self.fixture()
        fixture.staging.chmod(0o755)
        self.assertNotEqual(0, fixture.install().returncode)
        fixture = self.fixture()
        elsewhere = fixture.root / "other"
        elsewhere.mkdir(mode=0o700)
        other_token = elsewhere / "daemon.token"
        other_token.write_text(TOKEN_VALUE)
        other_token.chmod(0o600)
        self.assertNotEqual(0, fixture.install(**{"token-file": str(other_token)}).returncode)
        self.assertNotIn("curl", fixture.tools())

    def test_unsafe_managed_paths_rejected_without_relaxing_permissions(self):
        for kind in ("root-public", "root-link", "lib-link", "config-public", "token-public",
                     "token-link", "jar-directory", "backups-link", "logs-link"):
            with self.subTest(kind=kind):
                fixture = self.fixture()
                self.assert_ok(fixture.install())
                if kind == "root-public":
                    fixture.install_root.chmod(0o755)
                elif kind == "root-link":
                    moved = fixture.root / "moved-root"
                    fixture.install_root.rename(moved)
                    fixture.install_root.symlink_to(moved)
                elif kind == "lib-link":
                    fixture.jar.unlink()
                    fixture.jar.parent.rmdir()
                    fixture.jar.parent.symlink_to(fixture.root)
                elif kind == "config-public":
                    fixture.config.chmod(0o644)
                elif kind == "token-public":
                    fixture.token.chmod(0o644)
                elif kind == "token-link":
                    fixture.token.unlink()
                    fixture.token.symlink_to(fixture.input_token)
                elif kind == "jar-directory":
                    fixture.jar.unlink()
                    fixture.jar.mkdir()
                else:
                    path = fixture.install_root / kind.split("-")[0]
                    if path.exists():
                        path.rmdir()
                    path.symlink_to(fixture.root)
                fixture.reset_record()
                before = {path: path.read_bytes() for path in
                          (fixture.jar, fixture.config, fixture.token, fixture.service)
                          if path.is_file()}
                for command in ("install", "status", "uninstall"):
                    result = fixture.install() if command == "install" else fixture.run(command)
                    self.assertNotEqual(0, result.returncode)
                    self.assertTrue(fixture.service.exists())
                    for path, contents in before.items():
                        self.assertEqual(contents, path.read_bytes())
                    self.assertNotIn("curl", fixture.tools())
                    self.assert_no_switch(fixture)
                self.assertFalse((fixture.install_root / "backups").is_dir()
                                 and not (fixture.install_root / "backups").is_symlink())

    def shared_ancestors(self, fixture):
        """Host layout directories the installer must treat as the user's own trust boundary."""
        if fixture.operating_system == "Linux":
            return [fixture.home, fixture.home / ".config",
                    fixture.home / ".config/systemd", fixture.home / ".config/systemd/user"]
        return [fixture.home, fixture.home / "Library", fixture.home / "Library/LaunchAgents"]

    def test_group_writable_shared_ancestors_are_trusted(self):
        """Group-writable HOME/.config/systemd/user (Linux) and Library/LaunchAgents (macOS) install."""
        for mode in (0o775, 0o777):
            with self.subTest(mode=oct(mode)):
                self.check_shared_ancestor_lifecycle(mode)

    def check_shared_ancestor_lifecycle(self, mode):
        fixture = self.fixture()
        shared = self.shared_ancestors(fixture)
        sentinels = {}
        for path in shared:
            path.mkdir(parents=True, exist_ok=True)
            path.chmod(mode)
            sentinel = path / "pre-existing-shared-file"
            sentinel.write_text(str(path))
            sentinels[sentinel] = sentinel.read_bytes()
        before = {path: (path.stat().st_mode, path.stat().st_uid, path.stat().st_gid)
                  for path in shared}
        for action in ("install", "install", "status", "uninstall"):
            result = fixture.install() if action == "install" else fixture.run(action)
            self.assert_ok(result)
            for path in shared:
                self.assertEqual(before[path],
                                 (path.stat().st_mode, path.stat().st_uid, path.stat().st_gid))
            for path, contents in sentinels.items():
                self.assertEqual(contents, path.read_bytes())
            self.assert_no_secret(fixture, result)
        self.assert_clean(fixture)

    def test_symlinked_shared_ancestor_link_and_target_are_preserved(self):
        """A shared ancestor may be a relative symlink to another location; never scan or mutate it."""
        fixture = self.fixture()
        link = (fixture.home / ".config" if fixture.operating_system == "Linux"
                else fixture.home / "Library")
        target = fixture.root / "elsewhere" / link.name
        target.mkdir(parents=True, mode=0o700)
        target.chmod(0o777)
        sentinel = target / "pre-existing-target-file"
        sentinel.write_text("keep-target")
        relative = os.path.relpath(target, link.parent)
        link.symlink_to(relative)
        before = {path: (path.lstat().st_mode, path.lstat().st_uid, path.lstat().st_gid)
                  for path in (link, target, target.parent)}
        # systemd may return the resolved path through .config, not its HOME spelling.
        env = {"FAKE_SYSTEMCTL_MODE": "resolved-fragment"}
        for action in ("install", "install", "status", "uninstall"):
            result = fixture.install(env=env) if action == "install" else fixture.run(action, env=env)
            self.assert_ok(result)
            self.assertTrue(link.is_symlink())
            self.assertEqual(relative, os.readlink(link))
            for path, metadata in before.items():
                self.assertEqual(metadata,
                                 (path.lstat().st_mode, path.lstat().st_uid, path.lstat().st_gid))
            self.assertEqual("keep-target", sentinel.read_text())
            self.assertEqual(action != "uninstall", fixture.service.exists())
            if fixture.operating_system == "Linux" and action != "uninstall":
                self.assertNotEqual(fixture.service, fixture.service.resolve())
            self.assert_no_secret(fixture, result)
        self.assertFalse(fixture.service.exists())
        self.assert_clean(fixture)

    def test_foreign_service_diagnostics_exact_reason_path_and_safe_commands(self):
        reasons = {
            "marker": "missing ownership marker",
            "link": "definition is a symlink or not a regular file",
            "directory": "definition is a symlink or not a regular file",
        }
        for kind, reason in reasons.items():
            for action in ("install", "uninstall"):
                with self.subTest(kind=kind, action=action):
                    fixture = self.fixture()
                    fixture.service.parent.mkdir(parents=True, mode=0o700)
                    if kind == "marker":
                        fixture.service.write_text("unknown service")
                    elif kind == "link":
                        fixture.service.symlink_to(fixture.root / "missing")
                    else:
                        fixture.service.mkdir()
                    result = fixture.install() if action == "install" else fixture.run(action)
                    self.assertNotEqual(0, result.returncode)
                    lines = result.stderr.splitlines()
                    self.assertEqual(f"ERROR: refusing unmanaged service: {reason}", lines[0])
                    self.assertIn(f"Requested service definition: {fixture.service}", lines)
                    self.assertIn(f"Actual service definition: {fixture.service}", lines)
                    self.assertIn(f"Inspect: ls -ld {fixture.service}; cat {fixture.service}", lines)
                    self.assertIn("mktemp -d", result.stderr)
                    self.assertIn("cp -p", result.stderr)
                    self.assertIn(f'mv {fixture.service} "$backup/"', result.stderr)
                    self.assertIn("Preserve unknown JAR/config/token/data; then retry", result.stderr)
                    if fixture.operating_system == "Linux":
                        self.assertIn(f"systemctl --user disable --now {SERVICE_NAME}", result.stderr)
                        self.assertIn("Reload: systemctl --user daemon-reload", result.stderr)
                    else:
                        self.assertIn(f"Manual stop (only after confirming ownership): launchctl bootout {fixture.target}",
                                      result.stderr)
                        self.assertNotIn("launchctl disable", result.stderr)
                        self.assertNotIn("launchctl enable", result.stderr)
                    self.assertNotIn("curl", fixture.tools())
                    self.assertFalse(fixture.install_root.exists())
                    self.assert_no_switch(fixture)

    def test_release_diagnostic_marker_surfaced_and_unknown_stays_generic(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        before = fixture.snapshot()
        for mode, marker in (
            ("check-invalid", "Invalid daemon configuration: daemon.lsp.servers.jdtls.command must not be empty"),
            ("check-invalid-noisy", "Invalid daemon configuration: daemon.studioUrl must be an absolute http(s) origin"),
        ):
            with self.subTest(mode=mode):
                fixture.reset_record()
                result = fixture.install(env={"FAKE_JAVA_MODE": mode})
                self.assertNotEqual(0, result.returncode)
                self.assertIn(f"ERROR: {marker}", result.stderr.splitlines())
                self.assertNotIn("at java.base", result.stderr)
                self.assertEqual(before, fixture.snapshot())
                self.assert_no_switch(fixture)
                self.assert_no_secret(fixture, result)
                self.assert_clean(fixture)
        for mode in ("check-fail", "old-jar"):
            with self.subTest(mode=mode):
                fixture.reset_record()
                result = fixture.install(env={"FAKE_JAVA_MODE": mode})
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn(TOKEN_VALUE, result.stderr)
                self.assertFalse(any(line.startswith("Invalid daemon configuration:")
                                     for line in result.stderr.splitlines()))
                self.assertNotIn("unknown option", result.stderr)
                self.assertIn("rejected --check-config", result.stderr)
                self.assertEqual(before, fixture.snapshot())
                self.assert_no_switch(fixture)
                self.assert_clean(fixture)

    def test_mutated_staging_input_is_rejected_before_snapshot(self):
        for mode, expected in (("mutate-input", "symbolic link"), ("mutate-input-public", "permissions")):
            with self.subTest(mode=mode):
                fixture = self.fixture()
                self.assert_ok(fixture.install())
                before = fixture.snapshot()
                fixture.input_config.write_text(json.dumps({"studioUrl": STUDIO_URL, "note": "swapped"}))
                fixture.reset_record()
                result = fixture.install(env={"FAKE_CURL_MODE": mode})
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)
                self.assertEqual(before, fixture.snapshot())
                self.assertFalse((fixture.install_root / "backups").exists())
                self.assert_no_switch(fixture)
                self.assert_no_secret(fixture, result)
                self.assert_clean(fixture)

    def test_foreign_file_and_directory_owners_are_rejected(self):
        for target_kind in ("input", "input-parent", "root", "config", "jar", "service"):
            with self.subTest(target_kind=target_kind):
                fixture = self.fixture()
                if target_kind in ("root", "config", "jar", "service"):
                    self.assert_ok(fixture.install())
                target = {
                    "input": fixture.input_token, "input-parent": fixture.staging,
                    "root": fixture.install_root, "config": fixture.config,
                    "jar": fixture.jar, "service": fixture.service,
                }[target_kind]
                native_stat = shutil.which("stat")
                write_executable(fixture.bin / "stat", f"""#!/usr/bin/env python3
import os
import sys
if sys.argv[1:3] in (["-c", "%u"], ["-f", "%u"]) and sys.argv[3] == {str(target)!r}:
    print(os.getuid() + 1)
else:
    os.execv({native_stat!r}, [{native_stat!r}] + sys.argv[1:])
""")
                fixture.reset_record()
                result = fixture.install()
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("curl", fixture.tools())
                self.assert_no_switch(fixture)
                if target_kind == "service":
                    for text in ("belongs to another user", str(target), "Manual", "retry"):
                        self.assertIn(text, result.stderr)

    def test_partial_publication_failure_reports_original_backup_and_cleans_staging(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        before = fixture.snapshot()
        fixture.input_config.write_text(json.dumps({"studioUrl": STUDIO_URL, "note": "new"}))
        native_mv = shutil.which("mv")
        write_executable(fixture.bin / "mv", f"""#!/usr/bin/env python3
import os
import sys
if sys.argv[-1] == {str(fixture.config)!r}:
    sys.exit(1)
os.execv({native_mv!r}, [{native_mv!r}] + sys.argv[1:])
""")
        result = fixture.install(env={"FAKE_JAR_BODY": "published-new-jar"})
        self.assertNotEqual(0, result.returncode)
        backup = next((fixture.install_root / "backups").iterdir())
        self.assertIn(str(backup), result.stderr)
        self.assertIn("after publication", result.stderr)
        self.assertEqual("published-new-jar", fixture.jar.read_text())
        self.assertEqual(before[str(fixture.config)], fixture.config.read_bytes())
        self.assertEqual(before[str(fixture.token)], fixture.token.read_bytes())
        self.assertEqual(before[str(fixture.jar)], (backup / fixture.jar.name).read_bytes())
        self.assert_clean(fixture)
        self.assert_no_secret(fixture, result)

    def test_backup_failure_happens_before_stop_or_publication(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        before = fixture.snapshot()
        native_cp = shutil.which("cp")
        write_executable(fixture.bin / "cp", f"""#!/usr/bin/env python3
import os
import sys
if "/backups/" in sys.argv[-1]:
    sys.exit(1)
os.execv({native_cp!r}, [{native_cp!r}] + sys.argv[1:])
""")
        fixture.reset_record()
        result = fixture.install()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, fixture.snapshot())
        self.assert_no_switch(fixture)
        self.assert_clean(fixture)
        self.assertNotIn("after publication", result.stderr)

    def test_uninstall_idempotent_and_preserves_config_token_data_backups(self):
        fixture = self.fixture()
        self.assert_ok(fixture.run("uninstall"))
        self.assert_ok(fixture.install())
        self.assert_ok(fixture.install())
        data = fixture.install_root / "state"
        data.write_text("keep-data")
        keep = {path: path.read_bytes() for path in fixture.install_root.rglob("*")
                if path.is_file() and path != fixture.jar}
        fixture.reset_record()
        for _ in range(2):
            self.assert_ok(fixture.run("uninstall", env={"JAVA_HOME_21": "/missing/java"}))
            self.assertFalse(fixture.jar.exists())
            self.assertFalse(fixture.service.exists())
            for path, content in keep.items():
                self.assertEqual(content, path.read_bytes())
        self.assertNotIn("curl", fixture.tools())
        self.assertNotIn("java", fixture.tools())
        self.assert_clean(fixture)

    def test_status_readonly_exit_codes(self):
        fixture = self.fixture()
        self.assertEqual(1, fixture.run("status").returncode)
        self.assert_ok(fixture.install())
        before = fixture.snapshot()
        fixture.reset_record()
        self.assert_ok(fixture.run("status"))
        if fixture.operating_system == "Darwin":
            (fixture.root / "loaded").unlink()
            result = fixture.run("status")
        else:
            result = fixture.run("status", env={"FAKE_SYSTEMCTL_MODE": "inactive"})
        self.assertEqual(3, result.returncode)
        self.assertEqual(before, fixture.snapshot())
        self.assert_no_switch(fixture)
        self.assertNotIn("java", fixture.tools())


class TestLinuxInstall(UnixInstallContracts, FixtureTestCase):
    def test_direct_java_unit_and_hardening(self):
        fixture = self.fixture()
        jdk = fixture.root / 'jdk space "quotes" \\ 10% $HOME'
        shutil.copytree(fixture.jdk, jdk)
        self.assert_ok(fixture.install(**{"java-home": str(jdk)}))
        text = fixture.unit.read_text()
        self.assertEqual(UNIT_MARKER, text.splitlines()[0])
        expected = [str(jdk / "bin/java"), "-jar", str(fixture.jar), "--config", str(fixture.config)]
        self.assertIn("ExecStart=" + " ".join(systemd_escape(item) for item in expected), text)
        for line in ("Wants=network-online.target", "After=network-online.target",
                     "StartLimitIntervalSec=300", "StartLimitBurst=5", "Type=simple",
                     "WorkingDirectory=%h", "Restart=on-failure", "RestartSec=10",
                     "TimeoutStopSec=30", "KillMode=mixed", "UMask=0077",
                     "NoNewPrivileges=yes", "WantedBy=default.target"):
            self.assertIn(line, text)
        self.assertNotIn("Environment=", text)
        self.assertNotIn("sh -c", text)
        self.assertNotIn("--token", text)
        calls = fixture.calls("systemctl")
        self.assertLess(calls.index(["--user", "daemon-reload"]),
                        calls.index(["--user", "enable", SERVICE_NAME]))
        self.assertLess(calls.index(["--user", "enable", SERVICE_NAME]),
                        calls.index(["--user", "restart", SERVICE_NAME]))

    def test_manager_and_foreign_resolved_fragment_fail_before_download(self):
        fixture = self.fixture()
        result = fixture.install(env={"FAKE_SYSTEMCTL_MODE": "unavailable"})
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("curl", fixture.tools())
        self.assertFalse(fixture.install_root.exists())
        result = fixture.install(env={"FAKE_FRAGMENT_PATH": "/etc/systemd/user/kk-studio-daemon.service"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("/etc/systemd/user/kk-studio-daemon.service", result.stderr)
        self.assertIn("Manual", result.stderr)
        self.assertNotIn("curl", fixture.tools())

    def test_different_or_missing_fragment_preserves_managed_files(self):
        """Even identical bytes at a different inode cannot authorize service replacement."""
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        foreign = fixture.root / "different.service"
        foreign.write_bytes(fixture.service.read_bytes())
        foreign.chmod(0o644)
        before = fixture.snapshot()
        foreign_before = foreign.read_bytes()
        for fragment in (foreign, fixture.root / "missing.service"):
            for action in ("install", "status", "uninstall"):
                with self.subTest(fragment=fragment, action=action):
                    fixture.reset_record()
                    env = {"FAKE_FRAGMENT_PATH": str(fragment)}
                    result = (fixture.install(env=env) if action == "install"
                              else fixture.run(action, env=env))
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("service resolves to another definition", result.stderr)
                    self.assertEqual(before, fixture.snapshot())
                    self.assertEqual(foreign_before, foreign.read_bytes())
                    self.assertNotIn("curl", fixture.tools())
                    self.assert_no_switch(fixture)
                    self.assert_no_secret(fixture, result)
        self.assert_clean(fixture)

    def test_same_inode_fragment_keeps_service_endpoint_guards(self):
        """A file alias is allowed, but does not bypass marker or endpoint symlink checks."""
        for kind in ("hard-link", "foreign-marker", "service-link"):
            with self.subTest(kind=kind):
                fixture = self.fixture()
                self.assert_ok(fixture.install())
                alias = fixture.root / "alias.service"
                if kind == "service-link":
                    fixture.service.rename(alias)
                    fixture.service.symlink_to(alias)
                else:
                    alias.hardlink_to(fixture.service)
                    if kind == "foreign-marker":
                        fixture.service.write_text("unknown service")
                self.assertTrue(os.path.samefile(alias, fixture.service))
                before = fixture.snapshot()
                fixture.reset_record()
                env = {"FAKE_FRAGMENT_PATH": str(alias)}
                if kind == "hard-link":
                    self.assert_ok(fixture.run("status", env=env))
                else:
                    for action in ("install", "status", "uninstall"):
                        result = (fixture.install(env=env) if action == "install"
                                  else fixture.run(action, env=env))
                        self.assertNotEqual(0, result.returncode)
                        reason = ("missing ownership marker" if kind == "foreign-marker"
                                  else "definition is a symlink or not a regular file")
                        self.assertIn(reason, result.stderr)
                        self.assertEqual(before, fixture.snapshot())
                        self.assertEqual(before[str(fixture.service)], alias.read_bytes())
                        self.assert_no_secret(fixture, result)
                self.assert_no_switch(fixture)
                self.assertNotIn("curl", fixture.tools())
                self.assert_clean(fixture)

    def test_stop_disable_failures_preserve_files(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        before = fixture.snapshot()
        result = fixture.install(env={"FAKE_SYSTEMCTL_MODE": "stop-fail"})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, fixture.snapshot())
        result = fixture.run("uninstall", env={"FAKE_SYSTEMCTL_MODE": "disable-fail"})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, fixture.snapshot())

    def test_postpublication_failure_reports_backup_no_rollback(self):
        for mode in ("reload-fail", "enable-fail", "restart-fail", "inactive", "flapping"):
            fixture = self.fixture()
            self.assert_ok(fixture.install())
            before = fixture.snapshot()
            fixture.input_config.write_text(json.dumps({"studioUrl": STUDIO_URL, "note": "changed"}))
            (fixture.root / "active-count").unlink()
            result = fixture.install(env={
                "FAKE_SYSTEMCTL_MODE": mode, "FAKE_JAR_BODY": "new-jar",
                "DAEMON_VERIFY_STABLE_SECONDS": "1" if mode == "flapping" else "0",
            })
            self.assertNotEqual(0, result.returncode)
            backup = next((fixture.install_root / "backups").iterdir())
            for expected in ("after publication", "no automatic rollback", str(backup),
                             str(fixture.install_root), "journalctl", "manual", "retry"):
                self.assertIn(expected, result.stderr)
            self.assertEqual(before[str(fixture.jar)], (backup / fixture.jar.name).read_bytes())
            self.assertEqual("new-jar", fixture.jar.read_text())
            self.assert_no_secret(fixture, result)
            self.assert_clean(fixture)

    def test_java_discovery_order_without_requiring_a_compiler(self):
        fixture = self.fixture()
        other = fixture.root / "other-jdk"
        shutil.copytree(fixture.jdk, other)
        for overrides, env, chosen in (
            ({}, {"JAVA_HOME_21": str(other)}, fixture.jdk),
            ({"java-home": None}, {"JAVA_HOME_21": str(other)}, other),
            ({"java-home": None}, {"JAVA_HOME_21": ""}, fixture.jdk),
            ({"java-home": None}, {"JAVA_HOME_21": "", "JAVA_HOME": ""}, fixture.jdk),
        ):
            self.assert_ok(fixture.install(env=env, **overrides))
            self.assertIn(systemd_escape(str(chosen / "bin/java")), fixture.unit.read_text())
        # Runtime-only home: the release JAR is never compiled, so bin/javac is not required.
        self.assertFalse((fixture.jdk / "bin/javac").exists())
        self.assert_ok(fixture.install())

    def test_off_path_bash_is_accepted_and_path_bash_is_not_probed(self):
        fixture = self.fixture()
        # A broken bash earlier on PATH must never be executed by the installer.
        write_executable(fixture.bin / "bash", "#!/bin/sh\nexit 99\n")
        self.assert_ok(fixture.install())
        real_bash = shutil.which("bash")
        self.assertIsNotNone(real_bash)
        off_path = fixture.root / "off-path-bin"
        off_path.mkdir(mode=0o700)
        target = off_path / "bash"
        shutil.copy(real_bash, target)
        target.chmod(0o755)
        self.assertNotIn(str(off_path), fixture.environment()["PATH"])
        fixture.input_config.write_text(json.dumps({"studioUrl": STUDIO_URL, "bashExecutable": str(target)}))
        self.assert_ok(fixture.install())

    def test_manager_errors_are_not_silently_successful(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        self.assertNotEqual(0, fixture.run("status", env={"FAKE_SYSTEMCTL_MODE": "status-fail"}).returncode)
        self.assertNotEqual(0, fixture.run("status", env={"FAKE_JOURNALCTL_MODE": "fail"}).returncode)
        self.assertNotEqual(0, fixture.run("uninstall", env={"FAKE_SYSTEMCTL_MODE": "reload-fail"}).returncode)


class TestInstallInterface(FixtureTestCase):
    def test_help_and_removed_modes(self):
        fixture = self.fixture()
        for args in (("help",), ("--help",), ("-h",), ("install", "--help")):
            result = fixture.run(*args)
            self.assert_ok(result)
            self.assertIn("--config-file", result.stdout)
            self.assertIn("--token-file", result.stdout)
            self.assertIn("READY in Studio", result.stdout)
            for old in ("upgrade", "--from-source", "--gateway-uri", "--version",
                        "--registration-token", "interactive prompts via",
                        "javac", "compiler", "default Bash"):
                self.assertNotIn(old, result.stdout)
        for action in ("upgrade", "version", TOKEN_VALUE):
            result = fixture.run(action)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn(TOKEN_VALUE, result.stderr)
        for flag in ("--from-source", "--version", "--gateway-uri", "--registration-token",
                     "--registration-token-file", "--data-dir", "--note", "--bash-executable",
                     "--lsp-config"):
            result = fixture.install(flag, TOKEN_VALUE)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn(TOKEN_VALUE, result.stderr)
        self.assertNotIn("curl", fixture.tools())

    def test_missing_duplicate_unknown_relative_and_control_inputs(self):
        fixture = self.fixture()
        for args in (("install",), ("install", "--config-file"),
                     ("install", "--config-file", str(fixture.input_config)),
                     ("status", "--help"), ("uninstall", TOKEN_VALUE),
                     ("install", "--help", "--unknown")):
            self.assertNotEqual(0, fixture.run(*args).returncode)
        for extra in (("--token-file", str(fixture.input_token)), ("--unknown", TOKEN_VALUE)):
            self.assertNotEqual(0, fixture.install(*extra).returncode)
        for option in ("config-file", "token-file", "java-home"):
            for value in ("", "relative", "/tmp/a\nb", "/tmp/../inputs/daemon.json"):
                self.assertNotEqual(0, fixture.install(**{option: value}).returncode)
        self.assertNotIn("curl", fixture.tools())
        self.assertFalse(fixture.install_root.exists())

    def test_home_os_and_verification_window_validation(self):
        fixture = self.fixture()
        for env in ({"HOME": ""}, {"HOME": "relative"}, {"HOME": str(fixture.home) + "\t"},
                    {"FAKE_OS": "FreeBSD"}, {"DAEMON_VERIFY_TIMEOUT_SECONDS": "abc"},
                    {"DAEMON_VERIFY_STABLE_SECONDS": "-1"}, {"DAEMON_VERIFY_STABLE_SECONDS": "1.5"}):
            self.assertNotEqual(0, fixture.install(env=env).returncode)
        self.assertNotIn("curl", fixture.tools())

    def test_executable_and_shell_syntax(self):
        self.assertTrue(os.access(INSTALL_SCRIPT, os.X_OK))
        result = subprocess.run(["/bin/bash", "-n", str(INSTALL_SCRIPT)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_generated_command_contract_with_private_manual_fixture(self):
        """Frontend-independent command contract: Unicode/metacharacters stay data, not argv/env."""
        fixture = self.fixture()
        fixture.record_filesystem_children()
        # This deliberately tests the stable stdin command shape, not an unavailable UI generator.
        token = TOKEN_VALUE + "-$HOME-`id`-%s-雪"
        config = json.dumps({"studioUrl": STUDIO_URL, "note": "雪 'quotes' $HOME `id`"}, ensure_ascii=False)
        command = f"""set -eu
umask 077
stage=$(mktemp -d "$HOME/command.XXXXXXXX")
trap 'rm -rf "$stage"' EXIT
chmod 700 "$stage"
cat >"$stage/daemon.json" <<'CONFIG_LITERAL'
{config}
CONFIG_LITERAL
builtin printf '%s' '{token}' >"$stage/daemon.token"
chmod 600 "$stage/daemon.json" "$stage/daemon.token"
bash '{fixture.script}' install --config-file "$stage/daemon.json" --token-file "$stage/daemon.token" --java-home '{fixture.jdk}'
"""
        result = subprocess.run(["/bin/bash"], input=command, env=fixture.environment(),
                                text=True, capture_output=True, start_new_session=True, timeout=30)
        self.assert_ok(result)
        self.assertEqual(token, fixture.token.read_text())
        self.assertEqual(json.loads(config), json.loads(fixture.config.read_text()))
        for text in (fixture.record.read_text(), result.stdout, result.stderr):
            self.assertNotIn(token, text)
        self.assertEqual([], list(fixture.home.glob("command.*")))


if __name__ == "__main__":
    unittest.main()
