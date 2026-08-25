"""Run the permanent documentation checker as a repository-level contract."""

import subprocess
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


class TestDocumentation(unittest.TestCase):
    """The documentation checker must accept the committed fixed layout."""

    def test_documentation_checker_passes(self):
        """The CLI covers layout, links, headings, source paths, and retired terms."""
        result = subprocess.run(
            ["node", "scripts/docs/check.mjs"],
            cwd=REPOSITORY_ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PASS docs", result.stdout)


if __name__ == "__main__":
    unittest.main()
