"""Permanent contract regression for deploy/test/run.sh offline chat smoke."""

import sys
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
DEPLOY_TEST_DIR = REPOSITORY_ROOT / "deploy/test"
if str(DEPLOY_TEST_DIR) not in sys.path:
    sys.path.insert(0, str(DEPLOY_TEST_DIR))

from offline_chat_payload import build_offline_chat_batch_request


class TestDeploySmokeContract(unittest.TestCase):
    """Ensure deploy smoke offline chat request shape matches canonical runtime wire."""

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

    def test_deploy_run_script_uses_canonical_payload_builder(self):
        """deploy/test/run.sh must invoke build_offline_chat_batch_request without inline stale wire."""
        script_source = (DEPLOY_TEST_DIR / "run.sh").read_text()
        self.assertIn("from offline_chat_payload import build_offline_chat_batch_request", script_source)
        self.assertIn("build_offline_chat_batch_request(", script_source)
        self.assertNotIn("workspace_path", script_source)
        self.assertNotIn('"environment": None', script_source)
        self.assertNotIn('"environment": null', script_source)


if __name__ == "__main__":
    unittest.main()
