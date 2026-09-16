"""Permanent contract regression for daemon bootstrap, registration tokens, and canonical fixtures."""

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
SCHEMA_SEED = REPOSITORY_ROOT / "schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql"
E2E_LIB_SH = REPOSITORY_ROOT / "scripts/e2e/lib.sh"
RELIABILITY_COMPOSE = REPOSITORY_ROOT / "deploy/reliability/compose.yaml"
DISTRIBUTED_COMPOSE = REPOSITORY_ROOT / "deploy/distributed/compose.yaml"
RELIABILITY_ENTRYPOINT = REPOSITORY_ROOT / "deploy/reliability/daemon-entrypoint.sh"
DEV_ENTRYPOINT = REPOSITORY_ROOT / "deploy/dev/entrypoint.sh"
UI_SMOKE_MJS = REPOSITORY_ROOT / "scripts/e2e/ui-smoke.mjs"
LIFECYCLE_TEST_JAVA = (
    REPOSITORY_ROOT
    / "web/src/test/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimePostgresqlLifecycleIntegrationTest.java"
)
APP_E2E_YML = REPOSITORY_ROOT / "web/src/main/resources/application-e2e.yml"
APP_TEST_YML = REPOSITORY_ROOT / "web/src/test/resources/application.yml"


class TestDaemonBootstrapContracts(unittest.TestCase):
    """Ensure daemon bootstrap, registration tokens, and canonical fixtures adhere to current contracts."""

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

    def test_three_startup_paths_use_registration_token_file_and_data_directory(self):
        """Every daemon startup path must stream its token through an owner-only file and pass a durable data directory."""
        lib_content = E2E_LIB_SH.read_text(encoding="utf-8")
        # 凭证只经文件进入 daemon：argv 与 environ 都不再携带明文 token。
        self.assertIn("--registration-token-file", lib_content)
        self.assertNotIn("--registration-token ", lib_content)
        self.assertIn("chmod 600", lib_content)
        self.assertIn("--data-dir", lib_content)
        self.assertNotIn("--skill-dir", lib_content)
        self.assertIn("e2e-token-host-tool", lib_content)

        rel_content = RELIABILITY_COMPOSE.read_text(encoding="utf-8")
        # compose 只向容器入口注入环境变量，daemon 参数由 entrypoint 物化为 token 文件。
        self.assertIn("KK_STUDIO_DAEMON_REGISTRATION_TOKEN", rel_content)
        self.assertNotIn("--registration-token", rel_content)
        self.assertIn("--data-dir", rel_content)
        self.assertIn("e2e-token-reliability", rel_content)

        dist_content = DISTRIBUTED_COMPOSE.read_text(encoding="utf-8")
        self.assertIn("KK_STUDIO_DAEMON_REGISTRATION_TOKEN", dist_content)
        self.assertNotIn("--registration-token", dist_content)
        self.assertEqual(2, dist_content.count("--data-dir"))
        self.assertIn("e2e-token-dist-a", dist_content)
        self.assertIn("e2e-token-dist-b", dist_content)

        # reliability/distributed 共用同一入口脚本：它必须把环境变量写成 owner-only 文件后只传路径。
        entrypoint = RELIABILITY_ENTRYPOINT.read_text(encoding="utf-8")
        self.assertIn("--registration-token-file", entrypoint)
        self.assertNotIn("--registration-token ", entrypoint)
        self.assertIn("chmod 600", entrypoint)
        self.assertIn("chmod 700", entrypoint)
        self.assertNotIn('--registration-token "$', entrypoint)

    def test_reliability_daemon_image_installs_the_token_file_entrypoint(self):
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

    def test_reliability_entrypoint_materializes_the_token_as_an_owner_only_file(self):
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

    def test_reliability_entrypoint_fails_closed_without_a_token(self):
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

    def test_ui_smoke_creates_agent_bound_environment_chat(self):
        """UI smoke must bind Environment through the Agent only and clean up the temporary agent."""
        ui_smoke = UI_SMOKE_MJS.read_text(encoding="utf-8")
        self.assertNotIn("选择 Environment", ui_smoke)
        self.assertIn("ui.chat.create_agent_bound_environment", ui_smoke)
        self.assertIn("tempAgentName", ui_smoke)
        self.assertIn("environmentId: card.id", ui_smoke)
        self.assertIn("model: REAL_UI_MODEL_ID", ui_smoke)
        self.assertNotIn("modelProviderName", ui_smoke)
        self.assertIn("agentCreateRes.status === 201", ui_smoke)
        self.assertNotIn("agentCreateRes.status === 200", ui_smoke)
        self.assertIn("apiDeleteByName(args.backendUrl, 'agents', tempAgentName)", ui_smoke)
        # Workspace 路径选择器已随 W2-A 删除：UI smoke 只能断言它不存在。
        self.assertIn('button[aria-label="工作区路径"]', ui_smoke)
        self.assertIn("Create Chat still rendered a workspace path selector", ui_smoke)

    def test_lifecycle_fixture_is_canonical(self):
        """HarnessRuntimePostgresqlLifecycleIntegrationTest must seed canonical branch settings."""
        content = LIFECYCLE_TEST_JAVA.read_text(encoding="utf-8")
        self.assertNotIn('"workspacePath"', content)
        self.assertIn('{"settings": {"agentName": "lifecycle-test"', content)


if __name__ == "__main__":
    unittest.main()
