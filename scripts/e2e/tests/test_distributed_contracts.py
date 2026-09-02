"""Permanent guards for the distributed two-node E2E test base."""

import os
import re
import subprocess
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
DISTRIBUTED_DIR = REPOSITORY_ROOT / "deploy/distributed"
COMPOSE_FILE = DISTRIBUTED_DIR / "compose.yaml"
RUN_SCRIPT = DISTRIBUTED_DIR / "run.sh"
E2E_ENTRY = REPOSITORY_ROOT / "scripts/e2e.sh"
MATRIX_RUNNER = REPOSITORY_ROOT / "scripts/e2e/run-matrix.mjs"
NODE_TEST_FILE = REPOSITORY_ROOT / "scripts/e2e/tests/distributed.test.mjs"


def run_bash(command: str, env_extra=None):
    env = os.environ.copy()
    if env_extra:
        env.update(env_extra)
    return subprocess.run(
        ["bash", "-c", command],
        cwd=REPOSITORY_ROOT,
        text=True,
        capture_output=True,
        check=False,
        env=env,
    )


class TestDistributedCapabilityGating(unittest.TestCase):
    """--distributed must be an explicit orthogonal capability, off by default."""

    def test_e2e_entry_defaults_to_single_instance_path(self):
        """The default single-instance semantics must remain untouched."""
        script = E2E_ENTRY.read_text()

        self.assertIn("DISTRIBUTED=false", script)
        # The matrix invocation only carries distributed args behind the flag.
        self.assertIn(
            'if [ "$DISTRIBUTED" = "true" ]; then\n'
            '  MATRIX_ARGS+=(--distributed --base-url-b "http://127.0.0.1:$APP_B_PORT")\n'
            "fi",
            script,
        )
        # Distributed mode must not boot the host single-instance stack.
        self.assertIn('if [ "$DISTRIBUTED" != "true" ]; then\n  ensure_stack', script)

    def test_matrix_runner_keeps_single_node_defaults(self):
        """Without --distributed the runner must behave exactly as before."""
        source = MATRIX_RUNNER.read_text()

        self.assertIn("baseUrlB: ''", source)
        self.assertIn("distributed: false", source)
        self.assertIn("if (c.requires.has('distributed') && !args.distributed) return false", source)

    def test_matrix_runner_rejects_partial_distributed_arguments(self):
        """--distributed without the second URL (or vice versa) must fail closed."""
        for argv in (
            "--distributed",
            "--base-url-b http://127.0.0.1:18083",
        ):
            result = subprocess.run(
                ["node", "scripts/e2e/run-matrix.mjs", *argv.split()],
                cwd=REPOSITORY_ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, result.returncode, argv)
            self.assertIn("requires", result.stderr)

    def test_distributed_rejects_paid_or_host_stack_capabilities(self):
        """Distributed stays a free mock topology; --real still requires the host stack."""
        script = E2E_ENTRY.read_text()

        self.assertIn(
            "for flag in REAL WITH_TOOLS WITH_BRANCH WITH_CANVAS_STORAGE WITH_CANVAS_FUNCTION WITH_UI; do",
            script,
        )
        self.assertIn("cannot be combined with", script)


class TestDistributedCredentialBoundary(unittest.TestCase):
    """The distributed stack must never touch host TEST_MINIMAX_* credentials."""

    def test_distributed_compose_has_no_test_minimax_references(self):
        compose = COMPOSE_FILE.read_text()
        self.assertNotIn("TEST_MINIMAX", compose)

    def test_distributed_run_script_has_no_test_minimax_references(self):
        runner = RUN_SCRIPT.read_text()
        self.assertNotIn("TEST_MINIMAX", runner)

    def test_distributed_report_redaction_is_wired(self):
        """Container logs entering reports must pass the redaction helper."""
        runner = (MATRIX_RUNNER).read_text()
        self.assertIn("maybeCopyDistributedContainerLogs(runDir)", runner)
        self.assertIn("redactSecrets(logs)", runner)


class TestDistributedTopologyContract(unittest.TestCase):
    """The static validator plus compose must encode the A/B isolation."""

    def test_topology_validator_exists_and_is_invoked_by_verify(self):
        validator = REPOSITORY_ROOT / "scripts/e2e/tests/distributed_topology.py"
        self.assertTrue(validator.exists())
        runner = RUN_SCRIPT.read_text()
        self.assertIn("distributed_topology.py", runner)

    def test_db_fault_injection_never_touches_daemon_network(self):
        """disconnect/reconnect helpers must only address app-a's node-a-db network."""
        runner = RUN_SCRIPT.read_text()

        self.assertIn("docker network disconnect \"$NODE_A_DB_NETWORK\" \"$APP_A_CONTAINER\"", runner)
        self.assertIn("docker network connect \"$NODE_A_DB_NETWORK\" \"$APP_A_CONTAINER\"", runner)
        self.assertIn("NODE_A_DB_NETWORK=kk-studio-distributed_node-a-db", runner)
        self.assertIn("APP_A_CONTAINER=kk-studio-distributed-app-a-1", runner)
        # daemon networks must not appear in any network manipulation command.
        for line in runner.splitlines():
            if "docker network disconnect" in line or "docker network connect" in line:
                self.assertNotIn("daemon", line)

    def test_fault_helpers_are_idempotent_state_checks(self):
        """Repeated disconnect/reconnect calls must be safe (state-guarded)."""
        runner = RUN_SCRIPT.read_text()
        self.assertIn("already disconnected", runner)
        self.assertIn("already connected", runner)

    def test_shared_data_plane_uses_one_database_and_bucket(self):
        """Both apps must reference the same PostgreSQL database and MinIO bucket."""
        compose = COMPOSE_FILE.read_text()
        self.assertIn("KK_STUDIO_DB_URL: jdbc:postgresql://postgres:5432/kk_studio_distributed", compose)
        self.assertIn("KK_STUDIO_STORAGE_S3_BUCKET: kk-studio-distributed", compose)
        self.assertNotIn("app-b-db", compose)

    def test_node_identity_defaults_are_fixed_and_overridable(self):
        """Registration tokens have explicit disposable defaults in compose and CLI options are clean."""
        compose = COMPOSE_FILE.read_text()
        self.assertIn("${DISTRIBUTED_DAEMON_A_REGISTRATION_TOKEN:-e2e-token-dist-a}", compose)
        self.assertIn("${DISTRIBUTED_DAEMON_B_REGISTRATION_TOKEN:-e2e-token-dist-b}", compose)
        self.assertNotIn("--environment-name", compose)
        self.assertNotIn("--gateway-token", compose)
        self.assertNotIn("--daemon-id", compose)
        self.assertNotIn("DISTRIBUTED_DAEMON_TOKEN", compose)


class TestDistributedNodeContracts(unittest.TestCase):
    """The distributed node:test file must be part of the Node contract suite."""

    def test_distributed_node_test_is_discovered_by_python_suite(self):
        """test_node_contracts.py runs *.test.mjs via a glob; assert the new file matches."""
        source = (REPOSITORY_ROOT / "scripts/e2e/tests/test_node_contracts.py").read_text()
        self.assertIn('glob("*.test.mjs")', source)
        self.assertTrue(NODE_TEST_FILE.exists())


if __name__ == "__main__":
    unittest.main()
