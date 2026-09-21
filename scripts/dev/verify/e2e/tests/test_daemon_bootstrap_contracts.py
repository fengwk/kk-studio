"""Permanent contract regression for the E2E daemon bootstrap and its disposable fixtures."""

import os
import re
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

    def test_e2e_daemon_startup_streams_its_token_through_an_owner_only_file(self):
        """The E2E daemon start path must stream its token through a file and pass a durable data directory."""
        lib_content = E2E_LIB_SH.read_text(encoding="utf-8")

        # 凭证只经文件进入 daemon：argv 与 environ 都不再携带明文 token。
        self.assertIn("--registration-token-file", lib_content)
        self.assertNotIn("--registration-token ", lib_content)
        self.assertIn("chmod 600", lib_content)
        self.assertIn("--data-dir", lib_content)
        self.assertNotIn("--skill-dir", lib_content)
        self.assertIn("e2e-token-host-tool", lib_content)
        # 已删除的 daemon 选项不得重新出现在启动路径里：工作目录由调用方按 Tool
        # arguments 显式给出，Daemon 自己的持久状态只由 --data-dir 决定。
        self.assertNotIn("--environment-root", lib_content)

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
