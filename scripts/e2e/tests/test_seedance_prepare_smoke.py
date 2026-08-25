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
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            curl_marker = temporary_path / "curl-called"
            fake_curl = temporary_path / "curl"
            fake_curl.write_text(
                "#!/usr/bin/env bash\n"
                'printf "curl called\\n" > "$CURL_MARKER"\n'
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
            environment.pop("OPENCLI_HUB_BASE_URL", None)

            result = subprocess.run(
                [str(SMOKE_SCRIPT), "--confirm-prepare-only"],
                cwd=REPOSITORY_ROOT,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )

        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn("OPENCLI_HUB_BASE_URL is required", result.stderr)
        self.assertFalse(curl_marker.exists(), "missing Hub origin must fail before curl")


if __name__ == "__main__":
    unittest.main()
