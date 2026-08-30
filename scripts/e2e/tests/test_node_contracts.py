"""Execute the Node-native E2E helper contract tests."""

import subprocess
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


class TestNodeContracts(unittest.TestCase):
    """Node-native E2E helpers must retain their safety and state-machine guards."""

    def test_node_contracts(self):
        """Execute every colocated node:test contract through Python discovery."""
        test_files = sorted(
            (REPOSITORY_ROOT / "scripts/e2e/tests").glob("*.test.mjs")
        )
        self.assertTrue(test_files)
        result = subprocess.run(
            [
                "node",
                "--test",
                *(
                    str(test_file.relative_to(REPOSITORY_ROOT))
                    for test_file in test_files
                ),
            ],
            cwd=REPOSITORY_ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
