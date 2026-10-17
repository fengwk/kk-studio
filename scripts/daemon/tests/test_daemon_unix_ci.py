"""Keep the Unix/Windows daemon script suites wired into the real CI gates.

The install/update fixtures no longer import the removed Java harness. These tests pin the
remaining platform-level facts: every publication path runs the Python script suite with the
host's system Bash, both Windows PowerShell hosts run the native installer/update suites, and a
failed platform job blocks publication.
"""

import re
from pathlib import Path
import unittest

TESTS_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = TESTS_DIR.parents[2]


def job_block(workflow, name):
    """Read one top-level job without adding a YAML dependency to script tests."""
    match = re.search(
        rf"^  {name}:\n(.*?)(?=^  [A-Za-z_][A-Za-z_0-9]*:|\Z)",
        workflow,
        re.MULTILINE | re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"missing job: {name}")
    return match.group(1)


class TestUnixDaemonCI(unittest.TestCase):
    def workflow(self, name):
        return (REPOSITORY_ROOT / ".github" / "workflows" / name).read_text()

    def test_every_publication_path_runs_the_script_suite_with_system_bash(self):
        """Ubuntu/macOS system Bash must run the complete install/update script suite."""
        docker = job_block(self.workflow("docker-publish.yml"), "validate_unix_daemon")
        self.assertIn("os: [ubuntu-latest, macos-latest]", docker)
        self.assertIn("runs-on: ${{ matrix.os }}", docker)
        self.assertIn("fail-fast: false", docker)
        self.assertIn("shell: /bin/bash --noprofile --norc -eo pipefail {0}", docker)
        self.assertIn("/bin/bash --version", docker)
        self.assertIn(
            "python3 -m unittest discover -s scripts/daemon/tests -p 'test_*.py'", docker)

        release = job_block(self.workflow("daemon-release.yml"), "validate_daemon")
        self.assertIn("Test Unix installer and packaging with system Bash", release)
        self.assertIn(
            "python3 -m unittest discover -s scripts/daemon/tests -p 'test_*.py'", release)
        self.assertLess(release.index("npm --prefix daemon ci"), release.index("unittest discover"))

        ci = job_block(self.workflow("daemon-ci.yml"), "node_daemon")
        self.assertIn("Test the macOS installer and archive smoke with system Bash", ci)
        self.assertIn("shell: /bin/bash --noprofile --norc -eo pipefail {0}", ci)
        self.assertIn("/bin/bash --version", ci)
        self.assertIn(
            "python3 -m unittest discover -s scripts/daemon/tests -p 'test_*.py'", ci)

    def test_windows_gate_runs_both_powershell_hosts(self):
        """PS 5.1 is the floor; the PS7 result is collected even when PS 5.1 fails."""
        for name, job_name in (
            ("docker-publish.yml", "validate_windows_daemon"),
            ("daemon-release.yml", "validate_windows_daemon"),
            ("daemon-ci.yml", "windows_daemon_installer"),
        ):
            with self.subTest(workflow=name):
                job = job_block(self.workflow(name), job_name)
                self.assertIn("shell: powershell", job)
                self.assertIn("shell: pwsh", job)
                self.assertIn("test_daemon_install_windows.ps1", job)
                self.assertIn("if: ${{ !cancelled() }}", job)

    def test_windows_gate_chains_the_managed_update_native_suite(self):
        """The CI-invoked installer suite must also run the update suite on both hosts."""
        installer = (TESTS_DIR / "test_daemon_install_windows.ps1").read_text(encoding="utf-8")
        self.assertIn("test_daemon_update_windows.ps1", installer)
        self.assertIn("& $hostExe", installer)
        update = (TESTS_DIR / "test_daemon_update_windows.ps1").read_text(encoding="utf-8")
        self.assertIn("daemon\\src\\update-scripts.ts", update)
        self.assertIn("WINDOWS_UPDATE_SCRIPT", update)

    def test_publish_and_release_require_both_platform_jobs(self):
        docker = self.workflow("docker-publish.yml")
        publish = job_block(docker, "publish")
        needs = publish.split("needs:\n", 1)[1].split("    if:", 1)[0]
        for name in ("validate", "daemon_ci", "validate_unix_daemon", "validate_windows_daemon"):
            self.assertIn(f"      - {name}\n", needs)
            self.assertIn(f"needs.{name}.result == 'success'", publish)
            self.assertIn(f"needs.{name}.result == 'skipped'", publish)
        self.assertIn("&& !cancelled()", publish)

        release = self.workflow("daemon-release.yml")
        stage = job_block(release, "stage")
        needs = stage.split("needs:\n", 1)[1].split("    runs-on:", 1)[0]
        self.assertEqual(
            ["validate_daemon", "validate_windows_daemon", "daemon_ci"],
            re.findall(r"^      - (.+)$", needs, re.MULTILINE),
        )
        self.assertNotRegex(stage, r"(?m)^    if:")


class TestLegacyHarnessRemoval(unittest.TestCase):
    """The script suites must not regress to the deleted Java update harness."""

    def test_script_tests_do_not_reference_the_java_update_harness(self):
        needle = "harness" + "/daemon"
        for path in sorted(TESTS_DIR.iterdir()):
            if path.is_file() and path.suffix in (".py", ".ps1", ".sh"):
                self.assertNotIn(
                    needle, path.read_text(encoding="utf-8", errors="replace"), str(path))


if __name__ == "__main__":
    unittest.main()
