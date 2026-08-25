"""Regression guards for the real Seedance prepare-only smoke boundary."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
SMOKE_SCRIPT = REPOSITORY_ROOT / "scripts/seedance-prepare-smoke.sh"


class TestSeedancePrepareSmoke(unittest.TestCase):
    """The real smoke must not guess a Hub origin or touch the network."""

    def test_missing_hub_origin_fails_closed_before_curl(self):
        """A missing origin exits before the first network request."""
        result, curl_invocation = self.run_smoke()
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn("OPENCLI_HUB_BASE_URL is required", result.stderr)
        self.assertIsNone(curl_invocation, "missing Hub origin must fail before curl")

    def test_invalid_hub_origin_fails_closed_before_curl(self):
        """Invalid origins fail without echoing the supplied URL or invoking curl."""
        for invalid_origin in (
            "ftp://secret.example",
            "https://secret.example/private-token",
        ):
            with self.subTest(invalid_origin=invalid_origin):
                result, curl_invocation = self.run_smoke(invalid_origin)

                self.assertEqual(2, result.returncode, result.stdout + result.stderr)
                self.assertIn(
                    "OPENCLI_HUB_BASE_URL must be an HTTP(S) origin",
                    result.stderr,
                )
                self.assertNotIn(invalid_origin, result.stderr)
                self.assertIsNone(curl_invocation, "invalid Hub origin must fail before curl")

    def test_valid_hub_origin_strips_root_trailing_slash_before_curl(self):
        """A valid origin is normalized before the first request is built."""
        result, curl_invocation = self.run_smoke("https://hub.example/")

        self.assertIsNotNone(curl_invocation, result.stdout + result.stderr)
        self.assertIn("https://hub.example/api/opencli/execute", curl_invocation)
        self.assertNotIn("https://hub.example//api", curl_invocation)

    def run_smoke(self, hub_url=None):
        """Run the guard with a fake curl and retain its evidence before cleanup."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            curl_marker = temporary_path / "curl-called"
            fake_curl = temporary_path / "curl"
            fake_curl.write_text(
                "#!/usr/bin/env bash\n"
                'printf "%s\\n" "$*" > "$CURL_MARKER"\n'
                "exit 99\n",
                encoding="utf-8",
            )
            fake_curl.chmod(0o755)

            environment = os.environ.copy()
            environment.update(
                {
                    "RUN_REAL_SEEDANCE_PREPARE_SMOKE": "1",
                    "SEEDANCE_WORKSPACE_ID": "test-workspace",
                    "CURL_MARKER": str(curl_marker),
                    "PATH": os.pathsep.join(
                        [temporary_directory, environment.get("PATH", "")]
                    ),
                }
            )
            if hub_url is None:
                environment.pop("OPENCLI_HUB_BASE_URL", None)
            else:
                environment["OPENCLI_HUB_BASE_URL"] = hub_url

            result = subprocess.run(
                [str(SMOKE_SCRIPT), "--confirm-prepare-only"],
                cwd=REPOSITORY_ROOT,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )
            curl_invocation = (
                curl_marker.read_text(encoding="utf-8")
                if curl_marker.exists()
                else None
            )

        return result, curl_invocation


if __name__ == "__main__":
    unittest.main()
