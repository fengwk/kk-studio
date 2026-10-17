"""Permanent guards for the reliability stack's Node daemon bootstrap and token boundary."""

import json
import os
import shlex
import shutil
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
RELIABILITY_DOCKERFILE = REPOSITORY_ROOT / "deploy/reliability/daemon.Dockerfile"


def real_node():
    node = shutil.which("node")
    assert node is not None, "node is required to run the reliability entrypoint contract tests"
    return node


def write_node_dist(temporary, node_executable=None):
    """按发行布局写一个最小 Daemon 目录：根目录入口 + 冻结运行时。

    运行时是一个可执行包装脚本：`-e` 交给真实 Node（入口用它写 JSON），其它调用被记录成
    daemon 启动参数，因此断言的是入口真实的 exec 行为而不是静态文本。
    """
    dist = Path(temporary) / "daemon-dist"
    (dist / "runtime" / "code-server" / "lib").mkdir(parents=True)
    (dist / "main.js").write_text("// daemon entry\n", encoding="utf-8")
    if node_executable is None:
        argv_file = Path(temporary) / "argv.txt"
        environ_file = Path(temporary) / "environ.txt"
        runtime = dist / "runtime" / "code-server" / "lib" / "node"
        runtime.write_text(
            "#!/bin/bash\n"
            'if [ "$1" = "-e" ]; then exec "' + real_node() + '" "$@"; fi\n'
            'printf \'%s\\n\' "$@" >"$PROBE_ARGV"\n'
            'env >"$PROBE_ENVIRON"\n'
        )
        runtime.chmod(0o755)
        return dist, argv_file, environ_file
    runtime = dist / "runtime" / "code-server" / "lib" / "node"
    runtime.write_text(
        "#!/bin/bash\nexec " + shlex.quote(node_executable) + ' "$@"\n'
    )
    runtime.chmod(0o755)
    return dist, None, None


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
        # daemon 服务保留：镜像/uid/workspace named volume/健康依赖是现存自动化基础设施。
        self.assertIn("dockerfile: deploy/reliability/daemon.Dockerfile", rel_content)
        self.assertIn('user: "0:0"', rel_content)
        self.assertIn("daemon-workspace:/workspace", rel_content)

    def test_daemon_image_runs_the_frozen_node_distribution(self):
        """Daemon 镜像只带冻结 Node 发行目录：没有 JRE/Maven，也没有第二个 Node。"""
        dockerfile = RELIABILITY_DOCKERFILE.read_text(encoding="utf-8")

        self.assertIn("daemon-entrypoint.sh", dockerfile)
        self.assertEqual(
            1,
            dockerfile.count("deploy/reliability/daemon-entrypoint.sh"),
            "入口脚本只能被 COPY 一次",
        )
        self.assertNotIn('ENTRYPOINT ["java"', dockerfile, "直接 exec java 会绕过配置/凭证物化")
        for removed_jvm in ("eclipse-temurin", "daemon.jar", "maven:"):
            self.assertNotIn(removed_jvm, dockerfile, removed_jvm)
        # 发行目录由真实打包脚本产出，运行入口是归档根的 main.js。
        self.assertIn("scripts/daemon/prepare-release.sh", dockerfile)
        self.assertIn("/distribution/main.js", dockerfile)
        self.assertIn("runtime/code-server/lib/node", dockerfile)
        self.assertIn("FROM debian:bookworm-slim", dockerfile)
        # 只有一个 Node 二进制：/usr/local/bin/node 指向冻结运行时，npm/npx 跑在同一个 Node 上。
        self.assertIn(
            "ln -s /opt/kk-studio/daemon/runtime/code-server/lib/node /usr/local/bin/node",
            dockerfile,
        )
        self.assertNotIn("FROM node:", dockerfile.split("---------- runtime ----------")[1])

    def test_entrypoint_launches_the_node_distribution_entry_with_config_only(self):
        """入口必须物化共享 daemon.json/owner-only token，并用冻结 Node 运行发行根入口。"""
        with tempfile.TemporaryDirectory() as temporary:
            dist, argv_file, environ_file = write_node_dist(temporary)
            root = Path(temporary) / "daemon-root"
            secret = "probe-secret-token"
            studio_url = "https://studio.example.com"
            note = "Probe note — 日本語"

            environment = dict(os.environ)
            environment.update(
                {
                    "PROBE_ARGV": str(argv_file),
                    "PROBE_ENVIRON": str(environ_file),
                    "KK_STUDIO_DAEMON_DIST": str(dist),
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
            self.assertEqual([str(dist / "main.js"), "--config"], argv[:2])
            self.assertTrue(config_path.is_absolute())
            self.assertEqual(root / "daemon.json", config_path)
            # 旧 CLI 双源配置与 JVM 启动形式必须不再存在。
            for removed in (
                "--gateway-uri",
                "--data-dir",
                "--registration-token-file",
                "-jar",
                "daemon.jar",
            ):
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
            dist, _, _ = write_node_dist(temporary, node_executable=real_node())
            root = Path(temporary) / "daemon-root"
            environment = dict(os.environ)
            environment.update(
                {
                    "KK_STUDIO_DAEMON_DIST": str(dist),
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

    def test_entrypoint_rejects_a_distribution_without_entry_or_runtime(self):
        """发行目录缺入口或缺冻结运行时时必须失败，而不是回退到 PATH 上的其它 Node。"""
        for missing in ("main.js", "runtime/code-server/lib/node"):
            with self.subTest(missing=missing):
                with tempfile.TemporaryDirectory() as temporary:
                    dist, _, _ = write_node_dist(temporary)
                    (dist / missing).unlink()
                    environment = dict(os.environ)
                    environment.update(
                        {
                            "KK_STUDIO_DAEMON_DIST": str(dist),
                            "KK_STUDIO_DAEMON_ROOT": str(Path(temporary) / "daemon-root"),
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
                    self.assertIn(missing, result.stderr)

    def test_entrypoint_creates_config_owner_only_without_relying_on_chmod(self):
        """配置文件必须写入时即 owner-only：桩掉 chmod 并把 umask 全开也不能出现 world-readable 窗口。"""
        with tempfile.TemporaryDirectory() as temporary:
            probe_dir = Path(temporary) / "bin"
            probe_dir.mkdir()
            # chmod 桩为 no-op：只有 writeFileSync 的显式 mode 与 umask 能收敛权限。
            (probe_dir / "chmod").write_text("#!/bin/bash\nexit 0\n")
            (probe_dir / "chmod").chmod(0o755)
            dist, _, _ = write_node_dist(temporary, node_executable=real_node())

            root = Path(temporary) / "daemon-root"
            environment = dict(os.environ)
            environment.update(
                {
                    "PATH": f"{probe_dir}{os.pathsep}{environment.get('PATH', '')}",
                    "KK_STUDIO_DAEMON_DIST": str(dist),
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
