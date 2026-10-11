"""Contract tests for ``scripts/daemon/install.sh`` (Linux systemd and macOS launchd).

The fixture shims curl/uname/service managers; the installer still performs its real
ownership/permission checks, real SHA256 verification, real release ``--check-config``
preflight and real atomic program publication. No real service is registered.
"""

from __future__ import annotations

import os
import unittest

from daemon_fixture import RELEASE_TAG, InstallFixture, TempDirTestCase


class LinuxInstallTest(TempDirTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.fixture = InstallFixture(self.tmp)
        self.config_file, self.token_file = self.fixture.make_inputs()

    def install(self, **kwargs):
        return self.fixture.run(
            "install", "--config-file", str(self.config_file), "--token-file", str(self.token_file), **kwargs
        )

    def test_installs_program_config_token_and_service(self) -> None:
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("READY", result.stdout)
        root = self.fixture.install_root
        self.assertTrue((root / "lib" / "main.js").is_file())
        runtime_node = root / "lib" / "runtime" / "code-server" / "lib" / "node"
        self.assertTrue(os.access(runtime_node, os.X_OK))
        for name in ("daemon.json", "daemon.token"):
            path = root / name
            self.assertTrue(path.is_file())
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        unit = self.fixture.home / ".config" / "systemd" / "user" / "kk-studio-daemon.service"
        text = unit.read_text()
        self.assertEqual(text.splitlines()[0], "# Managed by scripts/daemon/install.sh")
        self.assertIn(f"{root}/lib/runtime/code-server/lib/node", text)
        self.assertIn(f"{root}/lib/main.js", text)
        self.assertTrue((self.fixture.state / "active").is_file())

    def test_second_install_backs_up_previous_program(self) -> None:
        self.assertEqual(self.install().returncode, 0)
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        backups = list((self.fixture.install_root / "backups").glob("install.*"))
        self.assertTrue(backups, "a backup directory must be created on reinstall")
        self.assertTrue((backups[0] / "lib").is_dir())
        self.assertEqual((backups[0] / "daemon.token").stat().st_mode & 0o777, 0o600)

    def test_rejects_unsafe_input_permissions(self) -> None:
        self.config_file.chmod(0o644)
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.fixture.install_root / "lib").exists())

    def test_rejects_non_private_input_directory(self) -> None:
        (self.tmp / "inputs").chmod(0o755)
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.fixture.install_root / "lib").exists())

    def test_rejects_duplicate_and_unknown_options(self) -> None:
        duplicate = self.fixture.run(
            "install",
            "--config-file",
            str(self.config_file),
            "--config-file",
            str(self.config_file),
            "--token-file",
            str(self.token_file),
        )
        self.assertNotEqual(duplicate.returncode, 0)
        unknown = self.fixture.run(
            "install",
            "--config-file",
            str(self.config_file),
            "--token-file",
            str(self.token_file),
            "--java-home",
            "/opt/jdk",
        )
        self.assertNotEqual(unknown.returncode, 0)

    def test_rejects_checksum_mismatch(self) -> None:
        (self.fixture.http_dir / (self.fixture.asset + ".sha256")).write_text(f"{'0' * 64}  {self.fixture.asset}\n")
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.fixture.install_root / "lib").exists())

    def test_rejects_non_official_tag(self) -> None:
        result = self.install(extra_env={"FAKE_TAG": "main"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalid official release tag", result.stderr)

    def test_rejects_version_mismatch(self) -> None:
        result = self.install(extra_env={"FAKE_NODE_VERSION": "9.9.9"})
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.fixture.install_root / "lib").exists())

    def test_rejects_invalid_config_without_changing_installation(self) -> None:
        # 真实契约是裸 daemon 对象：既拒绝非法取值，也拒绝外层 "daemon" 包裹的假结构。
        for config in (
            '{"invalid":true,"studioUrl":"https://studio.example.com"}',
            '{"daemon":{"studioUrl":"https://studio.example.com"}}',
        ):
            with self.subTest(config=config):
                config_file, token_file = self.fixture.make_inputs(config=config)
                result = self.fixture.run("install", "--config-file", str(config_file), "--token-file", str(token_file))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Invalid daemon configuration", result.stderr)
                self.assertFalse((self.fixture.install_root / "lib").exists())

    def test_status_before_install_reports_absent(self) -> None:
        result = self.fixture.run("status")
        self.assertEqual(result.returncode, 1)

    def test_uninstall_removes_program_and_keeps_config(self) -> None:
        self.assertEqual(self.install().returncode, 0)
        result = self.fixture.run("uninstall")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.fixture.install_root / "lib").exists())
        unit = self.fixture.home / ".config" / "systemd" / "user" / "kk-studio-daemon.service"
        self.assertFalse(unit.exists())
        self.assertTrue((self.fixture.install_root / "daemon.json").is_file())
        self.assertTrue((self.fixture.install_root / "daemon.token").is_file())

    def test_help_and_unknown_command(self) -> None:
        self.assertEqual(self.fixture.run("help").returncode, 0)
        self.assertNotEqual(self.fixture.run("upgrade").returncode, 0)


class UnsupportedPlatformTest(TempDirTestCase):
    def test_rejects_unknown_architecture(self) -> None:
        fixture = InstallFixture(self.tmp, machine="riscv64")
        config_file, token_file = fixture.make_inputs()
        result = fixture.run("install", "--config-file", str(config_file), "--token-file", str(token_file))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((fixture.install_root / "lib").exists())


class MacosInstallTest(TempDirTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.fixture = InstallFixture(self.tmp, host_os="Darwin", machine="arm64")
        self.config_file, self.token_file = self.fixture.make_inputs()

    def test_installs_launch_agent(self) -> None:
        result = self.fixture.run(
            "install", "--config-file", str(self.config_file), "--token-file", str(self.token_file)
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        plist = self.fixture.home / "Library" / "LaunchAgents" / "fun.fengwk.kkstudio.environment-daemon.plist"
        text = plist.read_text()
        self.assertIn("Managed by scripts/daemon/install.sh", text)
        self.assertIn("runtime/code-server/lib/node", text)
        self.assertTrue((self.fixture.install_root / "logs").is_dir())
        self.assertEqual(self.fixture.release_arch, "arm64")
        self.assertEqual(self.fixture.asset, f"kk-studio-daemon-{RELEASE_TAG}-macos-arm64.tar.gz")


if __name__ == "__main__":
    unittest.main()
