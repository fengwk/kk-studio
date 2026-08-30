"""Execute the Node-native E2E helper contract tests."""

import subprocess
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


class TestNodeContracts(unittest.TestCase):
    """E2E polling helpers must retain their deterministic state-machine guards."""

    def test_quiescence_contract(self):
        """A transient quiescent snapshot must not be accepted as a stable CAS cursor."""
        result = subprocess.run(
            ["node", "--test", "scripts/e2e/tests/quiescence.test.mjs"],
            cwd=REPOSITORY_ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
