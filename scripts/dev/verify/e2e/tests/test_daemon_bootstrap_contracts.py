"""Permanent contract regression for the E2E daemon bootstrap and its disposable fixtures."""

import json
import os
import re
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
E2E_ROOT = REPOSITORY_ROOT / "scripts" / "dev" / "verify" / "e2e"
SCHEMA_SEED = REPOSITORY_ROOT / "schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql"
E2E_LIB_SH = E2E_ROOT / "lib.sh"
DISTRIBUTED_COMPOSE = REPOSITORY_ROOT / "deploy/distributed/compose.yaml"
UI_SMOKE_MJS = E2E_ROOT / "ui-smoke.mjs"
LIFECYCLE_TEST_JAVA = (
    REPOSITORY_ROOT
    / "web/src/test/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimePostgresqlLifecycleIntegrationTest.java"
)


class TestDaemonBootstrapContracts(unittest.TestCase):
    """Ensure E2E daemon bootstrap, registration tokens, and canonical fixtures adhere to current contracts."""

    def test_e2e_seed_four_environment_cards_and_safety(self):
        """Seed must pre-seed exactly 4 disposable Environment Cards with fixed UUIDs and tokens."""
        content = SCHEMA_SEED.read_text(encoding="utf-8")

        # 1. Four Cards check
        self.assertIn("11111111-1111-1111-1111-111111111111", content)
        self.assertIn("tool-e2e", content)
        self.assertIn("e2e-token-host-tool", content)

        self.assertIn("22222222-2222-2222-2222-222222222222", content)
        self.assertIn("docker-reliability", content)
        self.assertIn("e2e-token-reliability", content)

        self.assertIn("33333333-3333-3333-3333-333333333333", content)
        self.assertIn("distributed-a", content)
        self.assertIn("e2e-token-dist-a", content)

        self.assertIn("44444444-4444-4444-4444-444444444444", content)
        self.assertIn("distributed-b", content)
        self.assertIn("e2e-token-dist-b", content)

        # 2. Re-runnable without deleting existing environments with FK/lease
        self.assertIn("on conflict (id) do update set", content)
        self.assertNotIn("delete from environment", content)

        # 3. default-assistant is NOT bound to any topology-specific environment
        match = re.search(
            r"insert into agent_definition\s*\([^)]*\)\s*values\s*\([^)]*'default-assistant'[^;]*;",
            content,
            re.IGNORECASE,
        )
        self.assertIsNotNone(match)
        default_assistant_stmt = match.group(0)
        self.assertNotIn("environmentId", default_assistant_stmt)
        self.assertNotIn("11111111-1111-1111-1111-111111111111", default_assistant_stmt)

    def test_e2e_daemon_bootstrap_materializes_config_and_launches_with_config_only(self):
        """E2E must materialize the shared daemon.json/owner-only token and launch with --config only."""
        lib_content = E2E_LIB_SH.read_text(encoding="utf-8")
        bootstrap = (E2E_ROOT / "lib" / "daemon-bootstrap.sh").read_text(encoding="utf-8")

        # 旧 CLI/数据目录参数不得复活；启动路径只走 bootstrap helper。
        for removed in ("--gateway-uri", "--data-dir", "--registration-token-file", "--environment-root"):
            self.assertNotIn(removed, lib_content, removed)
        self.assertIn("daemon-bootstrap.sh", lib_content)
        self.assertIn("DAEMON_ROOT", lib_content)
        self.assertNotIn("DAEMON_DATA_DIR", lib_content)
        self.assertIn("Daemon 运行数据目录", lib_content)
        # 凭证经 assignment 前缀进入子进程环境，绝不作为 `env` 的 argv 参数出现。
        self.assertIn(
            'DAEMON_REGISTRATION_TOKEN="$DAEMON_REGISTRATION_TOKEN" nohup env',
            lib_content,
        )
        self.assertIn("--config", bootstrap)
        self.assertNotIn("--data-dir", bootstrap)

        # 执行 recorder：证明 config/secret 落盘布局与 daemon 启动参数，而非静态假通过。
        with tempfile.TemporaryDirectory() as temporary:
            probe_dir = Path(temporary) / "jdk" / "bin"
            probe_dir.mkdir(parents=True)
            argv_file = Path(temporary) / "argv.txt"
            environ_file = Path(temporary) / "environ.txt"
            probe = probe_dir / "java"
            probe.write_text(
                "#!/bin/bash\n"
                'printf \'%s\\n\' "$@" >"$PROBE_ARGV"\n'
                'env >"$PROBE_ENVIRON"\n'
            )
            probe.chmod(0o755)

            root = Path(temporary) / "daemon"
            jar = Path(temporary) / "kk-studio-daemon.jar"
            secret = "e2e-token-host-tool"
            note = "E2E daemon environment — 日本語"
            env = os.environ.copy()
            env.update(
                {
                    "JAVA_HOME": str(probe_dir.parent),
                    "PROBE_ARGV": str(argv_file),
                    "PROBE_ENVIRON": str(environ_file),
                    "DAEMON_ROOT": str(root),
                    "DAEMON_STUDIO_URL": "http://127.0.0.1:18081",
                    "DAEMON_NOTE": note,
                    "DAEMON_JAR": str(jar),
                    "DAEMON_REGISTRATION_TOKEN": secret,
                }
            )
            result = subprocess.run(
                ["bash", str(E2E_ROOT / "lib" / "daemon-bootstrap.sh")],
                text=True,
                capture_output=True,
                check=False,
                env=env,
            )
            self.assertEqual(0, result.returncode, result.stderr)

            argv = argv_file.read_text().splitlines()
            self.assertEqual(["-jar", str(jar), "--config"], argv[:3])
            config_path = Path(argv[argv.index("--config") + 1])
            self.assertTrue(config_path.is_absolute())
            self.assertEqual(root / "daemon.json", config_path)
            self.assertNotIn(secret, argv)
            self.assertNotIn(secret, environ_file.read_text())
            self.assertNotIn(secret, result.stdout + result.stderr)
            self.assertNotIn(secret, config_path.read_text())

            config = json.loads(config_path.read_text(encoding="utf-8"))
            self.assertEqual({"studioUrl", "note"}, set(config))
            self.assertEqual("http://127.0.0.1:18081", config["studioUrl"])
            self.assertEqual(note, config["note"])
            self.assertEqual(0o700, root.stat().st_mode & 0o777)
            self.assertEqual(0o600, config_path.stat().st_mode & 0o777)
            token_path = root / "daemon.token"
            self.assertEqual(0o600, token_path.stat().st_mode & 0o777)
            self.assertEqual(secret, token_path.read_text().strip())

    def test_e2e_daemon_bootstrap_fails_closed_without_a_token(self):
        """缺少注册凭证时 bootstrap 必须立即失败，而不是启动一个无法注册的 daemon。"""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "daemon"
            env = os.environ.copy()
            env.update(
                {
                    "JAVA_HOME": temporary,
                    "DAEMON_ROOT": str(root),
                    "DAEMON_STUDIO_URL": "http://127.0.0.1:18081",
                    "DAEMON_JAR": str(Path(temporary) / "daemon.jar"),
                }
            )
            env.pop("DAEMON_REGISTRATION_TOKEN", None)
            result = subprocess.run(
                ["bash", str(E2E_ROOT / "lib" / "daemon-bootstrap.sh")],
                text=True,
                capture_output=True,
                check=False,
                env=env,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("DAEMON_REGISTRATION_TOKEN", result.stderr)

    def test_distributed_a_b_tokens_are_distinct(self):
        """Distributed A and B must never share a registration token."""
        dist_content = DISTRIBUTED_COMPOSE.read_text(encoding="utf-8")
        token_a_match = re.search(r"DISTRIBUTED_DAEMON_A_REGISTRATION_TOKEN:-([^}]+)}", dist_content)
        token_b_match = re.search(r"DISTRIBUTED_DAEMON_B_REGISTRATION_TOKEN:-([^}]+)}", dist_content)
        self.assertIsNotNone(token_a_match)
        self.assertIsNotNone(token_b_match)
        token_a = token_a_match.group(1)
        token_b = token_b_match.group(1)
        self.assertNotEqual(token_a, token_b)

    def test_ui_smoke_creates_agent_without_implicit_environment(self):
        """UI smoke must verify creating a chat with an agent does not bind an implicit environment."""
        ui_smoke = UI_SMOKE_MJS.read_text(encoding="utf-8")
        self.assertNotIn("选择 Environment", ui_smoke)
        self.assertIn("ui.chat.create_agent_no_implicit_environment", ui_smoke)
        self.assertNotIn("ui.chat.create_agent_bound_environment", ui_smoke)
        self.assertIn("{ requiresTools: true }", ui_smoke)
        self.assertIn("tempAgentName", ui_smoke)
        self.assertNotIn("environmentId: card.id", ui_smoke)
        self.assertIn("model: REAL_UI_MODEL_ID", ui_smoke)
        self.assertNotIn("modelProviderName", ui_smoke)
        self.assertIn("agentCreateRes.status === 201", ui_smoke)
        self.assertNotIn("agentCreateRes.status === 200", ui_smoke)
        self.assertIn("!Object.hasOwn(agentCreateRes.json.data, 'environmentId')", ui_smoke)
        self.assertIn("!Object.hasOwn(agentCreateRes.json.data, 'branchSettings')", ui_smoke)
        self.assertIn('button[aria-label="工作区路径"]', ui_smoke)
        self.assertIn("button.environment-binding-trigger", ui_smoke)
        self.assertIn("Create Chat still rendered a workspace path selector", ui_smoke)
        self.assertIn("environmentText === '未选择环境'", ui_smoke)
        self.assertIn("for (const field of ['workspacePath', 'environment', 'environmentId'])", ui_smoke)
        self.assertIn("!Object.hasOwn(created, field)", ui_smoke)
        self.assertIn("apiDeleteByName(args.backendUrl, 'chats', title)", ui_smoke)
        self.assertIn("apiDeleteByName(args.backendUrl, 'agents', tempAgentName)", ui_smoke)

    def test_lifecycle_fixture_is_canonical(self):
        """HarnessRuntimePostgresqlLifecycleIntegrationTest must seed canonical branch settings."""
        content = LIFECYCLE_TEST_JAVA.read_text(encoding="utf-8")
        self.assertNotIn('"workspacePath"', content)
        self.assertIn('{"settings": {"agentName": "lifecycle-test"', content)


if __name__ == "__main__":
    unittest.main()
