"""Permanent guards for clean dev and E2E package commands."""

import re
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


def function_body(script_path, function_name):
    """Extract a top-level shell function body for command-contract assertions."""
    source = script_path.read_text()
    match = re.search(
        rf"(?ms)^{re.escape(function_name)}\(\) \{{\n(?P<body>.*?)^\}}$",
        source,
    )
    if match is None:
        raise AssertionError(f"missing shell function {function_name} in {script_path}")
    return re.sub(r"\\\s*\n\s*", " ", match.group("body"))


class TestBuildScripts(unittest.TestCase):
    """Deleted classes must not survive a script-requested backend or daemon rebuild."""

    def assert_clean_package(self, script, function_name):
        body = function_body(REPOSITORY_ROOT / script, function_name)
        self.assertRegex(body, r"\bmvn\b.*\bclean\b\s+\bpackage\b")

    def test_e2e_backend_rebuild_uses_clean_package(self):
        self.assert_clean_package("scripts/e2e/lib.sh", "package_backend")

    def test_e2e_daemon_rebuild_uses_clean_package(self):
        self.assert_clean_package("scripts/e2e/lib.sh", "package_daemon")

    def test_dev_backend_start_uses_clean_package(self):
        self.assert_clean_package("scripts/dev.sh", "package_backend")


if __name__ == "__main__":
    unittest.main()
