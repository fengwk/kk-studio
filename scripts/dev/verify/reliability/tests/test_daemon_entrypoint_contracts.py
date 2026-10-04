"""Permanent guards for the reliability stack's daemon bootstrap and token boundary."""

import json
import os
import shlex
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


def repository_root():
    """Resolve the checkout root: KK_STUDIO_REPO_ROOT, else the enclosing worktree."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


REPOSITORY_ROOT = repository_root()
RELIABILITY_COMPOSE = REPOSITORY_ROOT / "deploy/reliability/compose.yaml"
RELIABILITY_ENTRYPOINT = REPOSITORY_ROOT / "deploy/reliability/daemon-entrypoint.sh"


class TestReliabilityDaemonBootstrapContracts(unittest.TestCase):
    """The reliability daemon must receive its token through an owner-only file and config via --config."""

    def test_compose_only_injects_environment(self):
        """Compose injects only environment; the entrypoint materializes config and token and starts via --config."""
        rel_content = RELIABILITY_COMPOSE.read_text(encoding="utf-8")

        self.assertIn("KK_STUDIO_DAEMON_REGISTRATION_TOKEN", rel_content)
        self.assertIn("KK_STUDIO_DAEMON_STUDIO_URL: http://app:8080", rel_content)
        self.assertIn("e2e-token-reliability", rel_content)
        # 旧 CLI/启动参数必须保持删除状态；配置只经共享 daemon.json 文件。
        for removed in (
            "--gateway-uri",
            "--data-dir",
            "--note",
            "--registration-token",
            "--environment-root",
        ):
            self.assertNotIn(removed, rel_content, removed)

    def test_daemon_image_installs_the_token_file_entrypoint(self):
        """Daemon 镜像入口必须是 bootstrap 脚本，而不是直接 exec java。"""
        dockerfile = (REPOSITORY_ROOT / "deploy/reliability/daemon.Dockerfile").read_text(
            encoding="utf-8"
        )

        self.assertIn("daemon-entrypoint.sh", dockerfile)
        self.assertEqual(
            1,
            dockerfile.count("deploy/reliability/daemon-entrypoint.sh"),
            "入口脚本只能被 COPY 一次",
        )
        self.assertNotIn(
            'ENTRYPOINT ["java"',
            dockerfile,
            "直接 exec java 会绕过配置/凭证物化，凭证重新出现在 argv",
        )

    def test_entrypoint_materializes_config_and_token_then_launches_with_config_only(self):
        """入口必须物化共享 daemon.json/owner-only token，并只把 --config 交给 daemon。"""
        with tempfile.TemporaryDirectory() as temporary:
            probe_dir = Path(temporary) / "bin"
            probe_dir.mkdir()
            argv_file = Path(temporary) / "argv.txt"
            environ_file = Path(temporary) / "environ.txt"
            probe = probe_dir / "java"
            probe.write_text(
                "#!/bin/bash\n"
                'printf \'%s\\n\' "$@" >"$PROBE_ARGV"\n'
                'env >"$PROBE_ENVIRON"\n'
            )
            probe.chmod(0o755)

            root = Path(temporary) / "daemon-root"
            secret = "probe-secret-token"
            studio_url = "https://studio.example.com"
            note = "Probe note — 日本語"

            environment = dict(os.environ)
            environment.update(
                {
                    "PATH": f"{probe_dir}{os.pathsep}{environment.get('PATH', '')}",
                    "PROBE_ARGV": str(argv_file),
                    "PROBE_ENVIRON": str(environ_file),
                    "KK_STUDIO_DAEMON_ROOT": str(root),
                    "KK_STUDIO_DAEMON_STUDIO_URL": studio_url,
                    "KK_STUDIO_DAEMON_NOTE": note,
                    "KK_STUDIO_DAEMON_REGISTRATION_TOKEN": secret,
                }
            )
            result = subprocess.run(
                ["bash", str(RELIABILITY_ENTRYPOINT)],
                text=True,
                capture_output=True,
                check=False,
                env=environment,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            argv = argv_file.read_text().splitlines()
            self.assertIn("--config", argv)
            config_path = Path(argv[argv.index("--config") + 1])
            self.assertEqual(["-jar", "/opt/kk-studio/daemon.jar", "--config"], argv[:3])
            self.assertTrue(config_path.is_absolute())
            self.assertEqual(root / "daemon.json", config_path)
            # 旧 CLI 双源配置必须不再存在。
            for removed in ("--gateway-uri", "--data-dir", "--registration-token-file", "--note"):
                self.assertNotIn(removed, argv)
            self.assertNotIn(secret, argv)
            self.assertNotIn(secret, environ_file.read_text())
            self.assertNotIn(secret, result.stderr + result.stdout)
            # 凭证从不进入配置 JSON。
            self.assertNotIn(secret, config_path.read_text())

            config = json.loads(config_path.read_text(encoding="utf-8"))
            self.assertEqual({"studioUrl", "note"}, set(config))
            self.assertEqual(studio_url, config["studioUrl"])
            self.assertEqual(note, config["note"])

            token_path = root / "daemon.token"
            self.assertEqual(0o700, root.stat().st_mode & 0o777)
            self.assertEqual(0o600, stat.S_IMODE(config_path.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(token_path.stat().st_mode))
            self.assertEqual(secret, token_path.read_text().strip())

    def test_entrypoint_omits_note_when_unset(self):
        """未设置 note 时配置 JSON 不得出现 note 字段。"""
        with tempfile.TemporaryDirectory() as temporary:
            probe_dir = Path(temporary) / "bin"
            probe_dir.mkdir()
            probe = probe_dir / "java"
            probe.write_text("#!/bin/bash\nexit 0\n")
            probe.chmod(0o755)

            root = Path(temporary) / "daemon-root"
            environment = dict(os.environ)
            environment.update(
                {
                    "PATH": f"{probe_dir}{os.pathsep}{environment.get('PATH', '')}",
                    "KK_STUDIO_DAEMON_ROOT": str(root),
                    "KK_STUDIO_DAEMON_STUDIO_URL": "http://app:8080",
                    "KK_STUDIO_DAEMON_REGISTRATION_TOKEN": "probe-secret-token",
                }
            )
            environment.pop("KK_STUDIO_DAEMON_NOTE", None)
            result = subprocess.run(
                ["bash", str(RELIABILITY_ENTRYPOINT)],
                text=True,
                capture_output=True,
                check=False,
                env=environment,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            config = json.loads((root / "daemon.json").read_text(encoding="utf-8"))
            self.assertEqual({"studioUrl"}, set(config))

    def test_entrypoint_fails_closed_without_a_token(self):
        """缺少凭证时入口必须立即失败，而不是启动一个无法注册的 Daemon。"""
        environment = dict(os.environ)
        environment.pop("KK_STUDIO_DAEMON_REGISTRATION_TOKEN", None)
        result = subprocess.run(
            ["bash", str(RELIABILITY_ENTRYPOINT)],
            text=True,
            capture_output=True,
            check=False,
            env=environment,
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("KK_STUDIO_DAEMON_REGISTRATION_TOKEN", result.stderr)

    def test_entrypoint_fails_closed_without_a_studio_url(self):
        """缺少 studio URL 时入口必须立即失败，不能写出不完整配置。"""
        environment = dict(os.environ)
        environment["KK_STUDIO_DAEMON_REGISTRATION_TOKEN"] = "probe-secret-token"
        environment.pop("KK_STUDIO_DAEMON_STUDIO_URL", None)
        result = subprocess.run(
            ["bash", str(RELIABILITY_ENTRYPOINT)],
            text=True,
            capture_output=True,
            check=False,
            env=environment,
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("KK_STUDIO_DAEMON_STUDIO_URL", result.stderr)

    def test_entrypoint_rejects_a_relative_root(self):
        """ROOT 必须为绝对路径，否则拒绝启动，避免把数据写到不可预期的工作目录。"""
        environment = dict(os.environ)
        environment.update(
            {
                "KK_STUDIO_DAEMON_ROOT": "relative/daemon-root",
                "KK_STUDIO_DAEMON_STUDIO_URL": "http://app:8080",
                "KK_STUDIO_DAEMON_REGISTRATION_TOKEN": "probe-secret-token",
            }
        )
        result = subprocess.run(
            ["bash", str(RELIABILITY_ENTRYPOINT)],
            text=True,
            capture_output=True,
            check=False,
            env=environment,
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("KK_STUDIO_DAEMON_ROOT", result.stderr)
        self.assertIn("absolute", result.stderr)

    def test_entrypoint_creates_config_owner_only_without_relying_on_chmod(self):
        """配置文件必须写入时即 owner-only：桩掉 chmod 并把 umask 全开也不能出现 world-readable 窗口。"""
        with tempfile.TemporaryDirectory() as temporary:
            probe_dir = Path(temporary) / "bin"
            probe_dir.mkdir()
            # chmod 桩为 no-op：只有 writeFileSync 的显式 mode 与 umask 能收敛权限。
            (probe_dir / "chmod").write_text("#!/bin/bash\nexit 0\n")
            (probe_dir / "chmod").chmod(0o755)
            (probe_dir / "java").write_text("#!/bin/bash\nexit 0\n")
            (probe_dir / "java").chmod(0o755)

            root = Path(temporary) / "daemon-root"
            environment = dict(os.environ)
            environment.update(
                {
                    "PATH": f"{probe_dir}{os.pathsep}{environment.get('PATH', '')}",
                    "KK_STUDIO_DAEMON_ROOT": str(root),
                    "KK_STUDIO_DAEMON_STUDIO_URL": "http://app:8080",
                    "KK_STUDIO_DAEMON_REGISTRATION_TOKEN": "probe-secret-token",
                }
            )
            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    f"umask 000; exec bash {shlex.quote(str(RELIABILITY_ENTRYPOINT))}",
                ],
                text=True,
                capture_output=True,
                check=False,
                env=environment,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(0o600, stat.S_IMODE((root / "daemon.json").stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE((root / "daemon.token").stat().st_mode))


if __name__ == "__main__":
    unittest.main()
