"""Permanent contract regression for daemon bootstrap, registration tokens, and canonical fixtures."""

import re
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
SCHEMA_SEED = REPOSITORY_ROOT / "schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql"
E2E_LIB_SH = REPOSITORY_ROOT / "scripts/e2e/lib.sh"
RELIABILITY_COMPOSE = REPOSITORY_ROOT / "deploy/reliability/compose.yaml"
DISTRIBUTED_COMPOSE = REPOSITORY_ROOT / "deploy/distributed/compose.yaml"
UI_SMOKE_MJS = REPOSITORY_ROOT / "scripts/e2e/ui-smoke.mjs"
LIFECYCLE_TEST_JAVA = (
    REPOSITORY_ROOT
    / "web/src/test/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimePostgresqlLifecycleIntegrationTest.java"
)
APP_E2E_YML = REPOSITORY_ROOT / "web/src/main/resources/application-e2e.yml"
APP_TEST_YML = REPOSITORY_ROOT / "web/src/test/resources/application.yml"


class TestDaemonBootstrapContracts(unittest.TestCase):
    """Ensure daemon bootstrap, registration tokens, and canonical fixtures adhere to Phase 15 contracts."""

    def test_e2e_seed_four_environment_cards_and_safety(self):
        """Seed must pre-seed exactly 4 disposable Environment Cards with fixed UUIDs and tokens."""
        content = SCHEMA_SEED.read_text(encoding="utf-8")

        # 1. Four Cards check
        self.assertIn("11111111-1111-1111-1111-111111111111", content)
        self.assertIn("tool-e2e", content)
        self.assertIn("e2e-token-host-tool-e2e", content)

        self.assertIn("22222222-2222-2222-2222-222222222222", content)
        self.assertIn("docker-reliability", content)
        self.assertIn("e2e-token-docker-reliability", content)

        self.assertIn("33333333-3333-3333-3333-333333333333", content)
        self.assertIn("distributed-a", content)
        self.assertIn("e2e-token-distributed-a", content)

        self.assertIn("44444444-4444-4444-4444-444444444444", content)
        self.assertIn("distributed-b", content)
        self.assertIn("e2e-token-distributed-b", content)

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

    def test_three_startup_paths_use_registration_token_only(self):
        """lib.sh, reliability compose, and distributed compose must only use registration token."""
        lib_content = E2E_LIB_SH.read_text(encoding="utf-8")
        self.assertIn("--registration-token", lib_content)
        self.assertIn("e2e-token-host-tool-e2e", lib_content)
        self.assertNotIn("--environment-name", lib_content)
        self.assertNotIn("--gateway-token", lib_content)
        self.assertNotIn("--daemon-id", lib_content)

        rel_content = RELIABILITY_COMPOSE.read_text(encoding="utf-8")
        self.assertIn("--registration-token", rel_content)
        self.assertIn("e2e-token-docker-reliability", rel_content)
        self.assertNotIn("--environment-name", rel_content)
        self.assertNotIn("--gateway-token", rel_content)
        self.assertNotIn("--daemon-id", rel_content)

        dist_content = DISTRIBUTED_COMPOSE.read_text(encoding="utf-8")
        self.assertIn("--registration-token", dist_content)
        self.assertIn("e2e-token-distributed-a", dist_content)
        self.assertIn("e2e-token-distributed-b", dist_content)
        self.assertNotIn("--environment-name", dist_content)
        self.assertNotIn("--gateway-token", dist_content)
        self.assertNotIn("--daemon-id", dist_content)

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

    def test_legacy_cli_and_global_token_zero_residual(self):
        """No legacy daemon-token configuration or CLI arguments in production / deployment."""
        app_e2e = APP_E2E_YML.read_text(encoding="utf-8")
        self.assertNotIn("daemon-token", app_e2e)
        self.assertNotIn("environment-gateway", app_e2e)

        app_test = APP_TEST_YML.read_text(encoding="utf-8")
        self.assertNotIn("daemon-token", app_test)

        forbidden_pattern = re.compile(
            r"--environment-name|--gateway-token|--daemon-id|environment-gateway.*daemon-token"
        )
        scan_paths = [
            REPOSITORY_ROOT / "deploy",
            REPOSITORY_ROOT / "scripts",
            REPOSITORY_ROOT / "docs",
            REPOSITORY_ROOT / "web/src/main/resources",
            REPOSITORY_ROOT / "harness/daemon/src/main/java",
        ]
        violations = []
        for base in scan_paths:
            if base.is_file():
                files = [base]
            else:
                files = list(base.rglob("*"))
            for file_path in files:
                if (
                    file_path.is_file()
                    and not file_path.name.endswith(".pyc")
                    and "__pycache__" not in file_path.parts
                    and "tests" not in file_path.parts
                ):
                    text = file_path.read_text(encoding="utf-8", errors="ignore")
                    if forbidden_pattern.search(text):
                        violations.append(str(file_path.relative_to(REPOSITORY_ROOT)))
        self.assertEqual(violations, [])

    def test_ui_smoke_uses_workspace_only_ui(self):
        """UI smoke must not require Environment option picker and must clean up temporary agent in finally."""
        ui_smoke = UI_SMOKE_MJS.read_text(encoding="utf-8")
        self.assertNotIn("选择 Environment", ui_smoke)
        self.assertIn("ui.chat.create_environment_workspace", ui_smoke)
        self.assertIn("tempAgentName", ui_smoke)
        self.assertIn("environmentId: card.id", ui_smoke)
        self.assertIn("model: 'minimax/MiniMax-M2.7'", ui_smoke)
        self.assertNotIn("modelProviderName", ui_smoke)
        self.assertIn("agentCreateRes.status === 201", ui_smoke)
        self.assertNotIn("agentCreateRes.status === 200", ui_smoke)
        self.assertIn("apiDeleteByName(args.backendUrl, 'agents', tempAgentName)", ui_smoke)
        self.assertIn('button[aria-label="工作区路径"]', ui_smoke)

    def test_lifecycle_fixture_is_canonical(self):
        """HarnessRuntimePostgresqlLifecycleIntegrationTest must use canonical workspacePath."""
        content = LIFECYCLE_TEST_JAVA.read_text(encoding="utf-8")
        self.assertIn('"workspacePath": null', content)
        self.assertNotIn('"environment": null', content)
        self.assertNotIn('"environment":', content)


if __name__ == "__main__":
    unittest.main()
