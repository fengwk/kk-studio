"""Keep real Unix runner coverage and system Bash selection from silently regressing."""

import re
import subprocess
import tempfile
import unittest

from unix_release_fixture import Fixture, REPOSITORY_ROOT


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

    def test_both_workflows_use_native_unix_matrix_and_only_installer_tests(self):
        """Both publication paths must exercise BSD/GNU tools with actual system Bash."""
        for name in ("docker-publish.yml", "daemon-release.yml"):
            with self.subTest(workflow=name):
                job = job_block(self.workflow(name), "validate_unix_daemon")
                self.assertIn("os: [ubuntu-latest, macos-latest]", job)
                self.assertIn("runs-on: ${{ matrix.os }}", job)
                self.assertIn("fail-fast: false", job)
                self.assertIn("shell: /bin/bash --noprofile --norc -eo pipefail {0}", job)
                self.assertIn("/bin/bash --version", job)
                commands = re.findall(r"^\s+python3 (.+)$", job, re.MULTILINE)
                self.assertEqual([
                    "-m unittest discover -s scripts/daemon/tests -p test_daemon_install.py",
                    "-m unittest discover -s scripts/daemon/tests -p test_daemon_install_macos.py",
                    "-m unittest discover -s scripts/daemon/tests -p test_daemon_download.py",
                ], commands)
                self.assertNotRegex(job, r"\b(brew|sudo|systemctl|launchctl)\b")

    def test_docker_preserves_dev_skip_and_fail_closed_publish_gates(self):
        """Dev still skips validation; main/validate_only cannot bypass a failed Unix matrix."""
        workflow = self.workflow("docker-publish.yml")
        job = job_block(workflow, "validate_unix_daemon")
        self.assertIn(
            "if: github.ref_name == 'main' || "
            "(github.event_name == 'workflow_dispatch' && inputs.validate_only)",
            job,
        )
        publish = job_block(workflow, "publish")
        needs = publish.split("needs:\n", 1)[1].split("    #", 1)[0]
        for name in ("validate", "validate_windows_daemon", "validate_unix_daemon"):
            self.assertIn(f"      - {name}\n", needs)
            self.assertIn(f"needs.{name}.result == 'success'", publish)
            self.assertIn(f"needs.{name}.result == 'skipped'", publish)
        self.assertIn("&& !cancelled()", publish)
        self.assertIn(
            "&& !(github.event_name == 'workflow_dispatch' && inputs.validate_only)", publish
        )

    def test_release_requires_both_platform_jobs_to_succeed(self):
        """Default needs success semantics block release on any platform failure or skip."""
        workflow = self.workflow("daemon-release.yml")
        job = job_block(workflow, "validate_unix_daemon")
        self.assertNotRegex(job, r"(?m)^    if:")
        publish = job_block(workflow, "publish")
        needs = publish.split("needs:\n", 1)[1].split("    runs-on:", 1)[0]
        self.assertEqual(
            ["validate_windows_daemon", "validate_unix_daemon"],
            re.findall(r"^      - (.+)$", needs, re.MULTILINE),
        )
        self.assertNotRegex(publish, r"(?m)^    if:")
        self.assertIn("    permissions:\n      contents: read\n", job)

    def test_fixture_executes_bin_bash_even_with_another_bash_on_path(self):
        """Inspect the child interpreter, not just the workflow shell surrounding Python."""
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(directory)
            fixture.script.write_text('printf "%s\\n" "$BASH" "$BASH_VERSION"\n')
            # A competing PATH Bash must not be used for the installer.
            competing = fixture.bin / "bash"
            competing.write_text("#!/bin/sh\nexit 99\n")
            competing.chmod(0o755)
            actual = fixture.run("--help")
            expected = subprocess.run(
                ["/bin/bash", "-c", 'printf "%s\\n" "$BASH" "$BASH_VERSION"'],
                capture_output=True, text=True, check=True,
            )
            self.assertEqual(0, actual.returncode, actual.stderr)
            self.assertEqual(expected.stdout, actual.stdout)


if __name__ == "__main__":
    unittest.main()
