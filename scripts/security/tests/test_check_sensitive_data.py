"""Unit tests for the repository sensitive-data gate."""

from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
SCANNER = REPOSITORY_ROOT / "scripts/security/check-sensitive-data.py"
SCANNER_SPEC = importlib.util.spec_from_file_location("check_sensitive_data", SCANNER)
SCANNER_MODULE = importlib.util.module_from_spec(SCANNER_SPEC)
SCANNER_SPEC.loader.exec_module(SCANNER_MODULE)
format_finding = SCANNER_MODULE.format_finding
scan_repository = SCANNER_MODULE.scan_repository
scan_text = SCANNER_MODULE.scan_text


def sample_values():
    """Build positive samples at runtime so the clean-tree test has no fixtures."""
    return {
        "private-key": "-----BEGIN " + "OPENSSH PRIVATE KEY-----",
        "aws-access-key": "AKIA" + "IOSFODNN7EXAMPLE",
        "github-token": "ghp_" + "a" * 36,
        "slack-token": "xoxb-" + "1234567890ABCDEFGHIJ",
        "stripe-live-token": "sk_live_" + "a" * 24,
        "jwt": "eyJ" + "a" * 8 + "." + "b" * 8 + "." + "c" * 8,
        "slack-webhook": (
            "https://hooks." + "slack.com/services/T00000000/B00000000/abcdefghijklmnop"
        ),
        "discord-webhook": (
            "https://discord.com/api/webhooks/"
            + "123456789012345678/"
            + "abcdefghijklmnop"
        ),
        "telegram-webhook": (
            "https://api." + "telegram.org/bot123456789:abcdefghijklmnopqrst/sendMessage"
        ),
        "personal-path": "/home/" + "alice/project",
    }


class TestSensitiveDataScanner(unittest.TestCase):
    """Keep the gate high-confidence, redacted, and usable in CI."""

    def test_detects_supported_rules_without_echoing_values(self):
        """Each supported high-confidence shape must produce only a redacted location."""
        for rule_name, value in sample_values().items():
            with self.subTest(rule=rule_name):
                findings = scan_text(f"fixture={value}", "fixture.txt")
                self.assertEqual([rule_name], [finding.rule for finding in findings])
                output = "\n".join(format_finding(finding) for finding in findings)
                self.assertIn(f"{rule_name} fixture.txt:1", output)
                self.assertNotIn(value, output)

    def test_allows_known_fixture_and_container_paths_only(self):
        """Known synthetic and container roots stay quiet while a user path is blocked."""
        allowed = "/home/" + "dev/project\n/home/" + "kkdaemon/work"
        self.assertEqual([], scan_text(allowed, "paths.txt"))

        findings = scan_text('root="' + "/home/" + "alice" + '"', "paths.txt")
        self.assertEqual(["personal-path"], [finding.rule for finding in findings])

    def test_cli_returns_nonzero_and_redacts_a_detected_value(self):
        """A CI invocation must fail on a finding without printing the matched key."""
        value = "AKIA" + "IOSFODNN7EXAMPLE"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "--quiet"], cwd=root, check=True)
            (root / "fixture.txt").write_text(f"key={value}\n", encoding="utf-8")

            completed = subprocess.run(
                [sys.executable, str(SCANNER), "--root", str(root)],
                capture_output=True,
                text=True,
                check=False,
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn("aws-access-key fixture.txt:1", completed.stdout.splitlines())
        self.assertNotIn(value, completed.stdout)
        self.assertNotIn(value, completed.stderr)

    def test_current_repository_scan_passes_and_script_is_executable(self):
        """The committed and development-visible tree must pass its own gate."""
        self.assertTrue(os.access(SCANNER, os.X_OK))
        self.assertEqual([], scan_repository(REPOSITORY_ROOT))


if __name__ == "__main__":
    unittest.main()
