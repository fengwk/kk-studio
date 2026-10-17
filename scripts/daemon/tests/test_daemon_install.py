"""Contract tests for ``scripts/daemon/install.sh`` (Linux systemd and macOS launchd).

The fixture shims curl/uname/service managers; the installer still performs its real
ownership/permission checks, real SHA256 verification, real release ``--check-config``
preflight and real atomic program publication. No real service is registered.
"""

from __future__ import annotations

import io
import os
import shutil
import tarfile
import unittest
from pathlib import Path

from daemon_fixture import RELEASE_TAG, InstallFixture, TempDirTestCase, sha256_of


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

    def test_failed_publication_restores_the_previous_program(self) -> None:
        # 新发行目录改名失败时，已经移进备份的旧 lib 必须回到受管位置。
        self.assertEqual(self.install().returncode, 0)
        previous = (self.fixture.install_root / "lib" / "main.js").read_text()
        script = Path(self.fixture.script).read_text()
        patched = script.replace(
            'mv "$DOWNLOAD_DIR/extract" "$INSTALLED_LIB"',
            'false',
            1,
        )
        self.assertNotEqual(script, patched)
        patched_script = self.tmp / "install-fail-publish.sh"
        patched_script.write_text(patched)
        self.fixture.script = patched_script
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue((self.fixture.install_root / "lib" / "main.js").is_file())
        self.assertEqual((self.fixture.install_root / "lib" / "main.js").read_text(), previous)
        self.assertIn("已恢复上一版程序", result.stderr)

    def test_reports_when_restoring_the_previous_program_also_fails(self) -> None:
        # 发布失败后再恢复失败时，必须明确报告，不能把旧程序仍在备份里说成已恢复。
        self.assertEqual(self.install().returncode, 0)
        script = Path(self.fixture.script).read_text()
        patched = script.replace('mv "$DOWNLOAD_DIR/extract" "$INSTALLED_LIB"', "false", 1).replace(
            'mv "$BACKUP_DIR/lib" "$INSTALLED_LIB"', "false", 1
        )
        self.assertNotIn('mv "$DOWNLOAD_DIR/extract" "$INSTALLED_LIB"', patched)
        self.assertNotIn('mv "$BACKUP_DIR/lib" "$INSTALLED_LIB"', patched)
        patched_script = self.tmp / "install-fail-restore.sh"
        patched_script.write_text(patched)
        self.fixture.script = patched_script
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("未能恢复", result.stderr)
        self.assertFalse((self.fixture.install_root / "lib").exists())

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

    def test_rejects_sha_verified_escaping_paths_before_extraction(self) -> None:
        # 即使 SHA 已通过，也必须在 tar 解包前拒绝逃逸路径。
        extracted = self.tmp / "extract-called"
        real_tar = shutil.which("tar")
        self.assertIsNotNone(real_tar)
        shim = self.fixture.bindir / "tar"
        shim.write_text(
            f'#!/bin/sh\nif [ "$1" = "-xzf" ]; then : > "{extracted}"; fi\nexec "{real_tar}" "$@"\n'
        )
        shim.chmod(0o755)
        outside = self.tmp / "absolute-escape"
        archive = self.fixture.http_dir / self.fixture.asset
        for member in ("../escape", str(outside)):
            with self.subTest(member=member):
                with tarfile.open(archive, "w:gz") as tar:
                    entry = tarfile.TarInfo(member)
                    entry.size = 4
                    tar.addfile(entry, io.BytesIO(b"evil"))
                (self.fixture.http_dir / (self.fixture.asset + ".sha256")).write_text(
                    f"{sha256_of(archive)}  {self.fixture.asset}\n"
                )
                result = self.install()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("不安全路径", result.stderr)
                self.assertFalse(extracted.exists())
                self.assertFalse(outside.exists())
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

    def test_inputs_mutated_during_the_download_are_rejected_before_snapshot(self) -> None:
        # 下载窗口内被换掉/放宽的输入必须在发布前重新校验，且不得影响正在运行的安装。
        assert self.install().returncode == 0
        root = self.fixture.install_root
        before = {name: (root / name).read_bytes() for name in ("daemon.json", "daemon.token")}
        program = (root / "lib" / "main.js").read_bytes()
        real = self.fixture.bindir / "curl.real"
        os.replace(self.fixture.bindir / "curl", real)
        for mode, mutation, expected in (
            ("symlink", f'rm -f "{self.config_file}"; ln -s "{self.token_file}" "{self.config_file}"', "symbolic link"),
            ("public", f'chmod 0644 "{self.config_file}"', "unsafe permissions"),
        ):
            with self.subTest(mode=mode):
                for path in (self.config_file, self.token_file):
                    path.unlink(missing_ok=True)
                self.config_file, self.token_file = self.fixture.make_inputs()
                self.fixture.bindir.joinpath("curl").write_text(
                    "#!/bin/sh\n"
                    f'"{real}" "$@"\n'
                    "status=$?\n"
                    f'if [ "$status" -eq 0 ]; then case "$*" in *.tar.gz*) {mutation} ;; esac; fi\n'
                    'exit "$status"\n'
                )
                self.fixture.bindir.joinpath("curl").chmod(0o755)
                result = self.install()
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn(expected, result.stderr)
                self.assertNotIn("token-value", result.stderr)
                self.assertEqual(program, (root / "lib" / "main.js").read_bytes())
                for name, content in before.items():
                    self.assertEqual(content, (root / name).read_bytes())
                self.assertTrue((self.fixture.state / "active").is_file(), "the running service must be untouched")
                self.assertFalse((root / "backups").exists(), "no backup may precede the input revalidation")

    def test_failed_reinstall_keeps_the_running_installation_and_backups_nothing(self) -> None:
        # 预检失败必须发生在备份与停止之前，正在运行的服务和现有文件保持原样。
        assert self.install().returncode == 0
        root = self.fixture.install_root
        unit = self.fixture.home / ".config" / "systemd" / "user" / "kk-studio-daemon.service"
        before = {
            path: path.read_bytes()
            for path in (root / "daemon.json", root / "daemon.token", root / "lib" / "main.js", unit)
        }
        result = self.install(extra_env={"FAKE_NODE_VERSION": "9.9.9"})
        self.assertNotEqual(result.returncode, 0)
        for target, content in before.items():
            self.assertEqual(content, target.read_bytes())
        self.assertTrue((self.fixture.state / "active").is_file(), "the running service must not be stopped")
        self.assertFalse((root / "backups").exists(), "no backup may precede the preflight")

    def test_rejects_unsafe_managed_paths_without_loosening_permissions(self) -> None:
        # 受管目录被放宽权限后必须拒绝重装，且不得自行修复或替换目录内容。
        assert self.install().returncode == 0
        root = self.fixture.install_root
        program = (root / "lib" / "main.js").read_bytes()
        root.chmod(0o770)
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unsafe permissions", result.stderr)
        self.assertEqual(root.stat().st_mode & 0o777, 0o770)
        self.assertEqual(program, (root / "lib" / "main.js").read_bytes())
        self.assertTrue((self.fixture.state / "active").is_file())

    def test_rejects_foreign_service_definition_without_touching_it(self) -> None:
        # 归属其他用户的 service 定义必须 fail closed，不能覆盖、改动或绕过所有权标记。
        path = self.fixture.home / ".config" / "systemd" / "user" / "kk-studio-daemon.service"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# Managed by scripts/daemon/install.sh\n[Service]\nExecStart=/bin/true\n")
        (self.fixture.state / "unit.path").write_text(f"{path}\n")
        native_stat = shutil.which("stat")
        self.assertIsNotNone(native_stat)
        shim = self.fixture.bindir / "stat"
        shim.write_text(
            "#!/usr/bin/env python3\n"
            "import os\n"
            "import sys\n"
            f"if sys.argv[1:3] in (['-c', '%u'], ['-f', '%u']) and sys.argv[3] == {str(path)!r}:\n"
            "    print(os.getuid() + 1)\n"
            "else:\n"
            f"    os.execv({native_stat!r}, [{native_stat!r}] + sys.argv[1:])\n"
        )
        shim.chmod(0o755)
        before = path.read_bytes()
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        for text in ("belongs to another user", str(path), "不要伪造所有权标记"):
            self.assertIn(text, result.stderr)
        self.assertEqual(before, path.read_bytes())
        self.assertFalse((self.fixture.install_root / "lib").exists())

    def test_reinstall_refreshes_program_and_config_and_keeps_runtime_data(self) -> None:
        assert self.install().returncode == 0
        root = self.fixture.install_root
        previous = (root / "lib" / "main.js").read_bytes()
        # 守护进程自己创建的运行时数据目录（安装器不负责创建），重装必须原样保留。
        (root / "logs").mkdir(mode=0o700, exist_ok=True)
        (root / "logs" / "daemon.log").write_text("runtime log line\n")
        config_file, token_file = self.fixture.make_inputs(
            config='{"studioUrl":"https://other.example.com"}', token="rotated-token"
        )
        result = self.fixture.run(
            "install", "--config-file", str(config_file), "--token-file", str(token_file)
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((root / "daemon.json").read_text(), '{"studioUrl":"https://other.example.com"}')
        self.assertEqual((root / "daemon.token").read_text(), "rotated-token")
        self.assertEqual((root / "logs" / "daemon.log").read_text(), "runtime log line\n")
        backups = [item for item in (root / "backups").iterdir() if item.is_dir()]
        self.assertEqual(1, len(backups))
        self.assertEqual(previous, (backups[0] / "lib" / "main.js").read_bytes())
        self.assertTrue((self.fixture.state / "active").is_file())

    def test_uninstall_is_idempotent_and_preserves_config_token_data_backups(self) -> None:
        assert self.install().returncode == 0
        root = self.fixture.install_root
        kept = {
            path: path.read_bytes()
            for path in sorted(root.rglob("*"))
            if path.is_file() and "lib" not in path.relative_to(root).parts
        }
        self.assertTrue(kept)
        for attempt in range(2):
            with self.subTest(attempt=attempt):
                result = self.fixture.run("uninstall")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertFalse((root / "lib").exists())
                self.assertFalse(
                    (self.fixture.home / ".config" / "systemd" / "user" / "kk-studio-daemon.service").exists()
                )
                for path, content in kept.items():
                    self.assertEqual(content, path.read_bytes())


class UnsupportedPlatformTest(TempDirTestCase):
    def test_rejects_unknown_architecture(self) -> None:
        fixture = InstallFixture(self.tmp, machine="riscv64")
        config_file, token_file = fixture.make_inputs()
        result = fixture.run("install", "--config-file", str(config_file), "--token-file", str(token_file))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((fixture.install_root / "lib").exists())

    def test_rejects_archive_for_another_platform(self) -> None:
        # 归档名可以是本机平台，清单里的平台键不是时必须在写入前拒绝。
        fixture = InstallFixture(self.tmp)
        foreign = fixture.root / "foreign.tar.gz"
        fixture._build_archive(foreign, platform="macos-arm64")
        archive = fixture.http_dir / fixture.asset
        archive.write_bytes(foreign.read_bytes())
        (fixture.http_dir / (fixture.asset + ".sha256")).write_text(f"{sha256_of(archive)}  {fixture.asset}\n")
        config_file, token_file = fixture.make_inputs()
        result = fixture.run("install", "--config-file", str(config_file), "--token-file", str(token_file))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("不是本机平台", result.stderr)
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
        self.assertIn("<key>ProgramArguments</key>", text)
        self.assertNotIn("java", text.lower())
        self.assertNotIn(".jar", text.lower())
        self.assertTrue((self.fixture.install_root / "logs").is_dir())
        self.assertEqual(self.fixture.release_arch, "arm64")
        self.assertEqual(self.fixture.asset, f"kk-studio-daemon-{RELEASE_TAG}-macos-arm64.tar.gz")


if __name__ == "__main__":
    unittest.main()
