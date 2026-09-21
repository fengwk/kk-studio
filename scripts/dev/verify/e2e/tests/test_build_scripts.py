"""Permanent guards for E2E clean package, runtime work directory, and daemon JAR contracts."""

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


def repository_root():
    """Resolve the checkout root: KK_STUDIO_REPO_ROOT, else the enclosing worktree."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


REPOSITORY_ROOT = repository_root()
E2E_ROOT = REPOSITORY_ROOT / "scripts" / "dev" / "verify" / "e2e"
E2E_LIB = E2E_ROOT / "lib.sh"
E2E_ENTRY = E2E_ROOT / "run.sh"
MATRIX_RUNNER = E2E_ROOT / "run-matrix.mjs"


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


def wait_for_exit(process, timeout_seconds):
    """Wait for a terminated child without leaking a lingering process on timeout."""
    try:
        process.wait(timeout=timeout_seconds)
        return True
    except subprocess.TimeoutExpired:
        return False


class TestE2EBuildScripts(unittest.TestCase):
    """Deleted classes must not survive a script-requested backend or daemon rebuild."""

    def assert_clean_package(self, script, function_name):
        body = function_body(script, function_name)
        self.assertRegex(body, r"(?:\bmvn\b|\brun_maven\b).*clean\b\s+\bpackage\b")

    def test_e2e_backend_rebuild_uses_clean_package(self):
        self.assert_clean_package(E2E_LIB, "package_backend")

    def test_e2e_daemon_rebuild_uses_clean_package(self):
        self.assert_clean_package(E2E_LIB, "package_daemon")

    def test_e2e_runtime_work_directory_survives_maven_clean(self):
        """The E2E runtime directory must stay outside the Maven build output."""
        self.assertIn(
            'WORK_DIR=${E2E_WORK_DIR:-"$REPO_ROOT/runtime/e2e"}',
            E2E_LIB.read_text(),
        )
        self.assertIn("path.join(REPO_ROOT, 'runtime', 'e2e')", MATRIX_RUNNER.read_text())

    def test_e2e_maven_is_online_by_default_and_offline_only_by_opt_in(self):
        """All E2E Maven rebuild paths must share one explicit offline switch."""
        result, default_calls = run_e2e_maven("unset")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(2, len(default_calls))
        self.assertTrue(all("-o" not in call.split() for call in default_calls))
        self.assertIn("-pl web", default_calls[0])
        self.assertIn("-pl harness/daemon", default_calls[1])
        self.assertIn("-am", default_calls[1].split())

        result, offline_calls = run_e2e_maven("true")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(2, len(offline_calls))
        self.assertTrue(all("-o" in call.split() for call in offline_calls))

    def test_daemon_is_a_single_shaded_jar_without_lib_or_classpath_machinery(self):
        """The daemon is one standalone JAR: no lib/ directory, no classpath file, no tool jar."""
        lib = E2E_LIB.read_text()

        self.assertIn(
            'DAEMON_JAR=${DAEMON_JAR:-"$REPO_ROOT/harness/daemon/target/kk-studio-daemon.jar"}',
            lib,
        )
        for removed in ("DAEMON_TOOL_JAR", "DAEMON_CP_FILE", "daemon.classpath"):
            self.assertNotIn(removed, lib, removed)
        for removed_machinery in ("dependency:build-classpath", "dependency:copy-dependencies"):
            self.assertNotIn(removed_machinery, lib, removed_machinery)

        start_daemon = function_body(E2E_LIB, "start_daemon")
        # Runtime classpath construction is gone: the daemon starts only via `java -jar <jar>`.
        self.assertIn('-jar "$DAEMON_JAR"', start_daemon)
        self.assertNotIn("-cp ", start_daemon)
        self.assertNotIn("DaemonMain", start_daemon)

        # `kill_daemon` can no longer match the main class, so it must match the configured JAR.
        kill_daemon = function_body(E2E_LIB, "kill_daemon")
        self.assertIn("pgrep -f --", kill_daemon)
        self.assertIn("$DAEMON_JAR", kill_daemon)
        self.assertNotIn("DaemonMain", kill_daemon)

    def test_daemon_pom_produces_a_shaded_main_artifact(self):
        """Maven must publish the shaded standalone JAR as the ordinary main artifact."""
        pom = (REPOSITORY_ROOT / "harness/daemon/pom.xml").read_text()

        self.assertIn("<finalName>kk-studio-daemon</finalName>", pom)
        self.assertIn("maven-shade-plugin", pom)
        self.assertRegex(pom, r"<version>3\.6\.1</version>")
        self.assertIn("<goal>shade</goal>", pom)
        self.assertIn("<createDependencyReducedPom>false</createDependencyReducedPom>", pom)
        # A classifier-only side artifact would leave the old non-standalone jar in place.
        self.assertNotIn("shadedArtifactAttached", pom)
        for manifest_entry in (
            "<Main-Class>fun.fengwk.kkstudio.harness.daemon.DaemonMain</Main-Class>",
            "<Implementation-Title>kk-studio-daemon</Implementation-Title>",
            "<Implementation-Version>${project.version}</Implementation-Version>",
            "<Multi-Release>true</Multi-Release>",
        ):
            self.assertIn(manifest_entry, pom, manifest_entry)
        # Signature metadata is invalid once dependencies are merged.
        for excluded in ("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA"):
            self.assertIn(f"<exclude>{excluded}</exclude>", pom, excluded)

    def test_kill_daemon_terminates_the_configured_jar_process_only(self):
        """`kill_daemon` must find the `java -jar <DAEMON_JAR>` process and leave others alive."""
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / "kk-studio-daemon.jar"
            # Stand-in for the daemon: any process whose argv carries the configured JAR path.
            daemon = subprocess.Popen(["python3", "-c", "import time; time.sleep(120)", str(jar)])
            bystander = subprocess.Popen(
                ["python3", "-c", "import time; time.sleep(120)", str(Path(temporary) / "other.jar")]
            )
            try:
                env = os.environ.copy()
                env["DAEMON_JAR"] = str(jar)
                result = subprocess.run(
                    [
                        "bash",
                        "-c",
                        f"source {E2E_LIB}\nkill_daemon\n",
                    ],
                    cwd=REPOSITORY_ROOT,
                    env=env,
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertTrue(
                    wait_for_exit(daemon, 30),
                    "the process holding the configured JAR path must be terminated",
                )
                self.assertIsNone(
                    bystander.poll(),
                    "a process that does not carry the configured JAR path must survive",
                )
            finally:
                for process in (daemon, bystander):
                    if process.poll() is None:
                        process.kill()
                        process.wait(timeout=30)

    def test_e2e_maven_rejects_ambiguous_offline_values(self):
        """An unsupported value must fail before a Maven child can run."""
        result, calls = run_e2e_maven("yes")
        self.assertNotEqual(0, result.returncode)
        self.assertRegex(
            result.stderr,
            r"E2E_MAVEN_OFFLINE must be exactly true or false",
        )
        self.assertEqual([], calls)

    def test_daemon_environment_root_is_exported_to_node_matrix(self):
        """The real Node case must receive the task-local work directory as an absolute path."""
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
                        f"source {E2E_LIB}\n"
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
                        f"source {E2E_LIB}\n"
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

        real_case = (E2E_ROOT / "cases/real.mjs").read_text()
        self.assertIn("const envRoot = process.env.DAEMON_ENV_ROOT", real_case)
        self.assertNotIn("/tmp/kk-studio-e2e-env", real_case)

    def test_canvas_function_rebuild_loads_e2e_and_canvas_test_seeds(self):
        """The documented free Function command must enable S3 on a fresh database."""
        script = E2E_ENTRY.read_text()

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

    def test_e2e_provider_credentials_are_synchronized_only_for_real_mode(self):
        """Free stack setup must never consume host real-provider credentials."""
        script = E2E_ENTRY.read_text()
        ensure_stack = function_body(E2E_LIB, "ensure_stack")
        sync_calls = re.findall(
            r"(?m)^\s*sync_e2e_provider_credentials\s*$",
            script,
        )

        self.assertEqual(1, len(sync_calls))
        self.assertRegex(
            script,
            (
                r'if \[ "\$REAL" = "true" \]; then\n'
                r"  sync_e2e_provider_credentials\n"
                r"fi"
            ),
        )
        self.assertNotIn("sync_e2e_provider_credentials", ensure_stack)


class TestE2ERuntimeCredentialScrubbing(unittest.TestCase):
    """The E2E stack must never leak host provider credentials into long-lived processes."""

    def test_long_lived_runtime_processes_scrub_all_test_inputs(self):
        """Every E2E start path and its scrubber must exclude all current and future TEST_* names."""
        start_backend = function_body(E2E_LIB, "start_backend")
        start_frontend = function_body(E2E_LIB, "start_frontend")
        start_daemon = function_body(E2E_LIB, "start_daemon")

        for runtime_body in (start_backend, start_frontend, start_daemon):
            self.assertIn('"${test_env_unsets[@]}"', runtime_body)

        scrubber = function_body(E2E_LIB, "test_env_unset_args")
        self.assertIn("TEST_*)", scrubber)
        self.assertIn("printf '%s\\0' -u", scrubber)
        self.assertIn("compgen -e", scrubber)
        self.assertNotIn("< <(env)", scrubber)
        self.assertNotIn("TEST_GOOGLE", scrubber)

    def test_test_environment_unset_args_covers_unknown_names(self):
        """The E2E scrubber must emit every TEST_* name and leave unrelated inputs alone."""
        env = os.environ.copy()
        env.update(
            {
                "TEST_FUTURE_PROVIDER_API_KEY": "future-secret",
                "TEST_API_KEY": "generic-secret",
                "UNRELATED_API_KEY": "keep",
            }
        )
        result = subprocess.run(
            [
                "bash",
                "-c",
                (
                    f"source {E2E_LIB}\n"
                    "mapfile -d '' -t args < <(test_env_unset_args)\n"
                    "printf '%s\\n' \"${args[@]}\""
                ),
            ],
            cwd=REPOSITORY_ROOT,
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        args = result.stdout.splitlines()
        pairs = set(zip(args[0::2], args[1::2]))

        self.assertIn(("-u", "TEST_FUTURE_PROVIDER_API_KEY"), pairs)
        self.assertIn(("-u", "TEST_API_KEY"), pairs)
        self.assertNotIn(("-u", "UNRELATED_API_KEY"), pairs)


def run_e2e_maven(offline):
    """Run the two E2E Maven paths against a recorder fixture."""
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        bin_dir = root / "bin"
        work_dir = root / "work"
        bin_dir.mkdir()
        work_dir.mkdir()
        maven_log = root / "maven.log"
        fake_maven = bin_dir / "mvn"
        # The daemon path asserts that the reactor produced its single shaded JAR.
        fake_maven.write_text(
            """#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$MAVEN_LOG"
printf 'fixture-daemon-jar\\n' > "$DAEMON_JAR"
"""
        )
        fake_maven.chmod(0o755)

        env = os.environ.copy()
        env["PATH"] = f"{bin_dir}{os.pathsep}{env['PATH']}"
        env["MAVEN_LOG"] = str(maven_log)
        env["E2E_WORK_DIR"] = str(work_dir)
        env["DAEMON_JAR"] = str(work_dir / "kk-studio-daemon.jar")
        if offline == "unset":
            env.pop("E2E_MAVEN_OFFLINE", None)
        else:
            env["E2E_MAVEN_OFFLINE"] = offline

        result = subprocess.run(
            [
                "bash",
                "-c",
                (
                    f"source {E2E_LIB}\n"
                    "package_backend /fake/jdk\n"
                    "package_daemon /fake/jdk"
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
