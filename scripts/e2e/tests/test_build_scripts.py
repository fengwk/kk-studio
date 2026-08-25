"""Permanent guards for clean package and runtime work-directory contracts."""

import os
import re
import subprocess
import tempfile
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
        self.assertRegex(body, r"(?:\bmvn\b|\brun_maven\b).*clean\b\s+\bpackage\b")

    def test_e2e_backend_rebuild_uses_clean_package(self):
        self.assert_clean_package("scripts/e2e/lib.sh", "package_backend")

    def test_e2e_daemon_rebuild_uses_clean_package(self):
        self.assert_clean_package("scripts/e2e/lib.sh", "package_daemon")

    def test_dev_backend_start_uses_clean_package(self):
        self.assert_clean_package("scripts/dev.sh", "package_backend")

    def test_runtime_work_directories_survive_maven_clean(self):
        e2e_lib = (REPOSITORY_ROOT / "scripts/e2e/lib.sh").read_text()
        dev_script = (REPOSITORY_ROOT / "scripts/dev.sh").read_text()
        matrix = (REPOSITORY_ROOT / "scripts/e2e/run-matrix.mjs").read_text()

        self.assertIn('WORK_DIR=${E2E_WORK_DIR:-"$REPO_ROOT/runtime/e2e"}', e2e_lib)
        self.assertIn('WORK_DIR=${DEV_WORK_DIR:-"$APP_HOME/runtime/dev"}', dev_script)
        self.assertIn("path.join(REPO_ROOT, 'runtime', 'e2e')", matrix)
        self.assertIn("path.join(REPO_ROOT, 'runtime', 'dev')", matrix)

    def test_e2e_maven_is_online_by_default_and_offline_only_by_opt_in(self):
        """All E2E Maven rebuild paths must share one explicit offline switch."""
        result, default_calls = self.run_e2e_maven("unset")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(3, len(default_calls))
        self.assertTrue(all("-o" not in call.split() for call in default_calls))
        self.assertIn("-pl web", default_calls[0])
        self.assertIn("-pl harness/daemon", default_calls[1])
        self.assertIn("dependency:build-classpath", default_calls[2])

        result, offline_calls = self.run_e2e_maven("true")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(3, len(offline_calls))
        self.assertTrue(all("-o" in call.split() for call in offline_calls))

    def test_e2e_maven_rejects_ambiguous_offline_values(self):
        """An unsupported value must fail before a Maven child can run."""
        result, calls = self.run_e2e_maven("yes")
        self.assertNotEqual(0, result.returncode)
        self.assertRegex(
            result.stderr,
            r"E2E_MAVEN_OFFLINE must be exactly true or false",
        )
        self.assertEqual([], calls)

    def test_daemon_environment_root_is_exported_to_node_matrix(self):
        """The daemon and real Node cases must share the task-local environment root."""
        with tempfile.TemporaryDirectory() as temporary:
            work_dir = Path(temporary) / "e2e"
            env = os.environ.copy()
            env.pop("DAEMON_ENV_ROOT", None)
            env["E2E_WORK_DIR"] = str(work_dir)
            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    (
                        "source scripts/e2e/lib.sh\n"
                        "node -e 'process.stdout.write(process.env.DAEMON_ENV_ROOT || \"\")'"
                    ),
                ],
                cwd=REPOSITORY_ROOT,
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(str(work_dir / "environment"), result.stdout)

            override = str(Path(temporary) / "custom-environment")
            env["DAEMON_ENV_ROOT"] = override
            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    (
                        "source scripts/e2e/lib.sh\n"
                        "node -e 'process.stdout.write(process.env.DAEMON_ENV_ROOT || \"\")'"
                    ),
                ],
                cwd=REPOSITORY_ROOT,
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(override, result.stdout)

        real_case = (REPOSITORY_ROOT / "scripts/e2e/cases/real.mjs").read_text()
        self.assertIn("const envRoot = process.env.DAEMON_ENV_ROOT", real_case)
        self.assertNotIn("/tmp/kk-studio-e2e-env", real_case)

    def test_canvas_function_rebuild_loads_e2e_and_canvas_test_seeds(self):
        """The documented free Function command must enable S3 on a fresh database."""
        script = (REPOSITORY_ROOT / "scripts/e2e.sh").read_text()

        self.assertIn('if [ "$WITH_CANVAS_FUNCTION" = "true" ]; then', script)
        self.assertIn(
            "classpath:db/migration,classpath:db/seed/e2e,"
            "classpath:db/seed/canvas-test",
            script,
        )
        self.assertIn(
            "SPRING_FLYWAY_LOCATIONS=${SPRING_FLYWAY_LOCATIONS:-",
            script,
        )

    def test_container_canvas_smoke_requires_current_dto_without_thread_id(self):
        """The Docker smoke must enforce the current Canvas DTO, which omits threadId."""
        script = (REPOSITORY_ROOT / "deploy/test/run.sh").read_text()
        readme = (REPOSITORY_ROOT / "deploy/test/README.md").read_text()

        self.assertIn('assert "threadId" not in canvas', script)
        self.assertNotIn('canvas["threadId"] is None', script)
        self.assertIn(
            'assert decimal_version(generated_snapshot["document"]["version"]) == 5',
            script,
        )
        self.assertIn(
            'create_and_run("gpt-image-2", "mock-gpt", {"ratio": "1:1"}, 4)',
            script,
        )
        self.assertIn(
            '"seedance2.0fast", "mock-seedance", {"ratio": "16:9", "duration": 4}, 6',
            script,
        )
        self.assertIn("patch_version + expected_checkpoint_count + 2", script)
        self.assertIn("start、checkpoint 与 terminal", readme)
        self.assertNotIn("checkpoint 不前进", readme)

    @staticmethod
    def run_e2e_maven(offline):
        """Run the three E2E Maven paths against a recorder fixture."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bin_dir = root / "bin"
            work_dir = root / "work"
            bin_dir.mkdir()
            maven_log = root / "maven.log"
            fake_maven = bin_dir / "mvn"
            fake_maven.write_text(
                """#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$MAVEN_LOG"
for arg in "$@"; do
  case "$arg" in
    -Dmdep.outputFile=*) printf 'fixture-classpath\\n' > "${arg#*=}" ;;
  esac
done
"""
            )
            fake_maven.chmod(0o755)

            env = os.environ.copy()
            env["PATH"] = f"{bin_dir}{os.pathsep}{env['PATH']}"
            env["MAVEN_LOG"] = str(maven_log)
            env["E2E_WORK_DIR"] = str(work_dir)
            if offline == "unset":
                env.pop("E2E_MAVEN_OFFLINE", None)
            else:
                env["E2E_MAVEN_OFFLINE"] = offline

            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    (
                        "source scripts/e2e/lib.sh\n"
                        "package_backend /fake/jdk\n"
                        "package_daemon /fake/jdk\n"
                        "build_daemon_classpath /fake/jdk"
                    ),
                ],
                cwd=REPOSITORY_ROOT,
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )
            calls = maven_log.read_text().splitlines() if maven_log.exists() else []
            return result, calls


if __name__ == "__main__":
    unittest.main()
