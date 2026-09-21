"""Permanent guards for the reliability stack's daemon bootstrap and token boundary."""

import os
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
    """The reliability daemon must receive its token through an owner-only file and a durable data dir."""

    def test_compose_streams_the_registration_token_file_and_data_directory(self):
        """Compose only injects environment; the entrypoint materializes the token file."""
        rel_content = RELIABILITY_COMPOSE.read_text(encoding="utf-8")

        self.assertIn("KK_STUDIO_DAEMON_REGISTRATION_TOKEN", rel_content)
        self.assertNotIn("--registration-token", rel_content)
        self.assertIn("--data-dir", rel_content)
        self.assertIn("e2e-token-reliability", rel_content)
        self.assertNotIn("--environment-root", rel_content)

    def test_daemon_image_installs_the_token_file_entrypoint(self):
        """Daemon 镜像入口必须是 token-file 迁移后的脚本，而不是直接 exec java。"""
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
            "直接 exec java 会绕过 token 文件物化，凭证重新出现在 argv",
        )

    def test_entrypoint_materializes_the_token_as_an_owner_only_file(self):
        """入口必须把环境变量写成 0600 文件并从环境中清除，只把绝对路径交给 daemon argv。"""
        with tempfile.TemporaryDirectory() as home:
            probe_dir = Path(home)
            argv_file = probe_dir / "argv.txt"
            environ_file = probe_dir / "environ.txt"
            probe = probe_dir / "java"
            probe.write_text(
                "#!/bin/bash\n"
                'printf \'%s\\n\' "$@" >"$PROBE_ARGV"\n'
                'env >"$PROBE_ENVIRON"\n'
            )
            probe.chmod(0o755)

            environment = dict(os.environ)
            environment.update(
                {
                    "HOME": home,
                    "PATH": f"{probe_dir}:{environment.get('PATH', '')}",
                    "PROBE_ARGV": str(argv_file),
                    "PROBE_ENVIRON": str(environ_file),
                    "KK_STUDIO_DAEMON_REGISTRATION_TOKEN": "probe-secret-token",
                }
            )
            result = subprocess.run(
                ["bash", str(RELIABILITY_ENTRYPOINT), "--gateway-uri", "ws://app:8080/x"],
                text=True,
                capture_output=True,
                check=False,
                env=environment,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            argv = argv_file.read_text().splitlines()
            # 入口自己插入 token 路径，再透传 Compose 提供的业务参数。
            self.assertIn("--registration-token-file", argv)
            self.assertIn("--gateway-uri", argv)
            self.assertNotIn("--registration-token", argv)
            self.assertNotIn("probe-secret-token", argv)
            self.assertNotIn("probe-secret-token", environ_file.read_text())
            self.assertNotIn("probe-secret-token", result.stderr + result.stdout)

            token_path = Path(argv[argv.index("--registration-token-file") + 1])
            self.assertTrue(token_path.is_absolute())
            self.assertEqual(0o700, token_path.parent.stat().st_mode & 0o777)
            self.assertEqual(0o600, token_path.stat().st_mode & 0o777)
            self.assertEqual("probe-secret-token", token_path.read_text().strip())

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


if __name__ == "__main__":
    unittest.main()
