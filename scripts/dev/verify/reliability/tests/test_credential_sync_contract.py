"""Permanent guards for the reliability stack's MiniMax credential boundary."""

import os
from pathlib import Path
import unittest


def repository_root() -> Path:
    """Resolve the checkout root: KK_STUDIO_REPO_ROOT, else the enclosing worktree."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


REPOSITORY_ROOT = repository_root()
STACK_SCRIPT = REPOSITORY_ROOT / "scripts" / "dev" / "verify" / "reliability" / "stack.sh"
SYNC_TOOL = "sync_minimax_credentials.py"


class TestReliabilityCredentialSync(unittest.TestCase):
    """The reliability stack owns its legacy MiniMax pair through the dedicated sync tool."""

    def test_stack_synchronizes_minimax_credentials_through_the_sync_tool(self):
        """Credential values must be passed to the tool, never inlined into the stack script."""
        stack = STACK_SCRIPT.read_text()

        self.assertIn(
            "scripts/dev/verify/reliability/" + SYNC_TOOL,
            stack,
        )
        self.assertIn('--backend-url "$APP_URL"', stack)
        for name in ("TEST_MINIMAX_BASE_URL", "TEST_MINIMAX_API_KEY"):
            self.assertIn(f'{name}="${{{name}-}}"', stack)

    def test_sync_tool_uses_shared_library_and_has_no_e2e_dependency(self):
        """Reliability credential sync must import from shared lib, never depend on e2e."""
        sync_script = (
            REPOSITORY_ROOT
            / "scripts"
            / "dev"
            / "verify"
            / "reliability"
            / SYNC_TOOL
        ).read_text()

        self.assertIn("from scripts.dev.lib.provider_credentials import", sync_script)
        self.assertNotIn("verify.e2e", sync_script)


if __name__ == "__main__":
    unittest.main()
