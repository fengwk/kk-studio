"""Permanent contract regression for the offline chat and container Canvas smoke."""

import os
import sys
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
SMOKE_DIR = REPOSITORY_ROOT / "scripts" / "dev" / "verify" / "smoke"
SMOKE_SCRIPT = SMOKE_DIR / "offline-chat.sh"
DEPLOY_TEST_README = REPOSITORY_ROOT / "deploy" / "test" / "README.md"
if str(SMOKE_DIR) not in sys.path:
    sys.path.insert(0, str(SMOKE_DIR))

from offline_chat_payload import build_offline_chat_batch_request


class TestDeploySmokeContract(unittest.TestCase):
    """Ensure smoke offline chat request shape matches canonical runtime wire."""

    def test_offline_chat_batch_request_structure(self):
        """Builder must produce a valid NEW_SESSION batch without any workspace field."""
        request = build_offline_chat_batch_request(
            chat_id="00000000-0000-0000-0000-000000000001",
            session_id="00000000-0000-0000-0000-000000000002",
            thread_id="00000000-0000-0000-0000-000000000003",
            command_id="00000000-0000-0000-0000-000000000004",
            marker="smoke-marker-123",
        )

        self.assertEqual(request["owner"], {"type": "CHAT", "id": "00000000-0000-0000-0000-000000000001"})
        target = request["target"]
        self.assertEqual(target["type"], "NEW_SESSION")
        self.assertEqual(target["sessionId"], "00000000-0000-0000-0000-000000000002")
        self.assertEqual(target["threadId"], "00000000-0000-0000-0000-000000000003")
        self.assertFalse(target["yoloEnabled"])

        # canonical root settings 精确为 agentName + model + environmentName 三字段：
        # workspacePath 已随 W2-A 删除；environmentName 是 nullable 快照字段，
        # null 表示未选择 Environment，不代表该字段被删除。
        root_settings = target["rootSettings"]
        self.assertEqual(sorted(root_settings.keys()), ["agentName", "environmentName", "model"])
        self.assertEqual(root_settings["agentName"], "default-assistant")
        self.assertIsNone(root_settings["environmentName"])
        self.assertEqual(
            root_settings["model"],
            {
                "providerName": "stub",
                "modelName": "acceptance-stub",
                "variant": "default",
            },
        )
        for removed in ["workspacePath", "environment", "environmentId"]:
            self.assertNotIn(removed, root_settings)

        commands = request["commands"]
        self.assertEqual(len(commands), 1)
        self.assertEqual(commands[0]["type"], "USER_MESSAGE")
        self.assertEqual(commands[0]["idempotencyKey"], "00000000-0000-0000-0000-000000000004")
        self.assertEqual(commands[0]["contents"], [{"type": "TEXT", "text": "smoke-marker-123"}])

    def test_offline_chat_builder_rejects_removed_workspace_path(self):
        """The deleted workspace_path parameter must not silently reappear on the builder surface."""
        with self.assertRaises(TypeError):
            build_offline_chat_batch_request(
                chat_id="00000000-0000-0000-0000-000000000001",
                session_id="00000000-0000-0000-0000-000000000002",
                thread_id="00000000-0000-0000-0000-000000000003",
                command_id="00000000-0000-0000-0000-000000000004",
                marker="smoke-marker-123",
                workspace_path="src/tests",
            )

    def test_smoke_script_uses_canonical_payload_builder(self):
        """The host smoke runner must invoke build_offline_chat_batch_request without inline stale wire."""
        script_source = SMOKE_SCRIPT.read_text()

        self.assertIn("from offline_chat_payload import build_offline_chat_batch_request", script_source)
        self.assertIn("build_offline_chat_batch_request(", script_source)
        self.assertNotIn("workspace_path", script_source)
        self.assertNotIn('"environment": None', script_source)
        self.assertNotIn('"environment": null', script_source)

    def test_container_canvas_smoke_requires_current_dto_without_thread_id(self):
        """The Docker smoke must enforce the current Canvas DTO, which omits threadId."""
        script = SMOKE_SCRIPT.read_text()
        readme = DEPLOY_TEST_README.read_text()

        self.assertIn('assert "threadId" not in canvas', script)
        self.assertNotIn('canvas["threadId"] is None', script)
        self.assertIn(
            'assert decimal_version(generated_snapshot["document"]["version"]) == 5',
            script,
        )
        self.assertIn(
            'create_and_run("gpt-image-2", "mock-gpt", {"ratio": "1:1"}, 4)',
            script,
        )
        self.assertIn(
            '"seedance2.0fast", "mock-seedance", {"ratio": "16:9", "duration": 4}, 6',
            script,
        )
        self.assertIn("patch_version + expected_checkpoint_count + 2", script)
        self.assertIn("start、checkpoint 与 terminal", readme)
        self.assertNotIn("checkpoint 不前进", readme)

    def test_smoke_script_lives_outside_the_deploy_runtime_assets(self):
        """Human host runners belong to scripts/; deploy/ keeps only compose/images/runtime assets."""
        self.assertTrue(SMOKE_SCRIPT.exists())
        self.assertFalse((REPOSITORY_ROOT / "deploy" / "test" / "run.sh").exists())


if __name__ == "__main__":
    unittest.main()
