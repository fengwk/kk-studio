"""Permanent guards for the four-provider real E2E credential boundary."""

import ast
from pathlib import Path
import re
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
ALLOWED_CREDENTIAL_ENVIRONMENTS = {
    "TEST_GEMINI_BASE_URL",
    "TEST_GEMINI_API_KEY",
    "TEST_OPENAI_BASE_URL",
    "TEST_OPENAI_API_KEY",
    "TEST_MINIMAX_ANTHROPIC_BASE_URL",
    "TEST_MINIMAX_ANTHROPIC_API_KEY",
    "TEST_DEEPSEEK_BASE_URL",
    "TEST_DEEPSEEK_API_KEY",
}
NON_ALLOWED_CREDENTIAL = re.compile(
    r"\bTEST_(?!(?:GEMINI|OPENAI|MINIMAX_ANTHROPIC|DEEPSEEK|MINIMAX)_(?:BASE_URL|API_KEY)\b)[A-Z0-9_]+_(?:BASE_URL|API_KEY)\b"
)
CREDENTIAL_ENVIRONMENT = re.compile(r"^TEST_[A-Z0-9_]+_(?:BASE_URL|API_KEY)$")


def production_files():
    """Return only production scripts, configuration, compose, and documentation."""
    yield from (
        path
        for path in (REPOSITORY_ROOT / "scripts").rglob("*")
        if path.is_file()
        and path.suffix in {".py", ".sh", ".mjs"}
        and "tests" not in path.parts
    )
    yield from (
        path
        for path in (REPOSITORY_ROOT / "docs").rglob("*")
        if path.is_file() and path.suffix == ".md"
    )
    yield REPOSITORY_ROOT / "README.md"
    yield REPOSITORY_ROOT / "deploy/local/README.md"
    yield REPOSITORY_ROOT / "deploy/local/compose.yaml"
    yield from (
        path
        for path in (REPOSITORY_ROOT / "web/src/main").rglob("*")
        if path.is_file()
    )


class TestCredentialContractGuard(unittest.TestCase):
    """Prevent reintroducing unapproved credential inputs or app-side synchronization."""

    def test_production_scopes_contain_no_unallowed_credential_environment(self):
        offenders = []
        for path in production_files():
            matches = NON_ALLOWED_CREDENTIAL.findall(path.read_text())
            if matches:
                offenders.append(f"{path.relative_to(REPOSITORY_ROOT)}: {matches}")

        self.assertEqual([], offenders)

    def test_app_side_synchronizer_and_configuration_are_absent(self):
        java_root = REPOSITORY_ROOT / "web/src/main"
        classes = list(java_root.rglob("E2eProviderCredential*.java"))
        configured = [
            path.relative_to(REPOSITORY_ROOT)
            for path in java_root.rglob("*")
            if path.is_file() and "e2e-provider-sync" in path.read_text()
        ]

        self.assertEqual([], classes)
        self.assertEqual([], configured)

    def test_compose_never_passes_test_environment(self):
        compose = (REPOSITORY_ROOT / "deploy/local/compose.yaml").read_text()

        self.assertNotIn("TEST_", compose)

    def test_sync_script_reads_exactly_the_allowed_credential_environments(self):
        source_path = REPOSITORY_ROOT / "scripts/e2e/sync_provider_credentials.py"
        source = source_path.read_text()
        tree = ast.parse(source)
        constants = {
            target.id: node.value.value
            for node in tree.body
            if isinstance(node, ast.Assign)
            and len(node.targets) == 1
            and isinstance((target := node.targets[0]), ast.Name)
            and isinstance(node.value, ast.Constant)
            and isinstance(node.value.value, str)
        }
        reads = set()
        for node in ast.walk(tree):
            argument = node.args[0] if isinstance(node, ast.Call) and node.args else None
            environment_name = (
                argument.value
                if isinstance(argument, ast.Constant) and isinstance(argument.value, str)
                else constants.get(argument.id)
                if isinstance(argument, ast.Name)
                else None
            )
            if (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and node.func.attr == "get"
                and isinstance(environment_name, str)
                and CREDENTIAL_ENVIRONMENT.fullmatch(environment_name)
            ):
                reads.add(environment_name)

        self.assertEqual(ALLOWED_CREDENTIAL_ENVIRONMENTS, reads)


if __name__ == "__main__":
    unittest.main()
