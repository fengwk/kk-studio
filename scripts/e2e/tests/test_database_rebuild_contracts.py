"""Permanent safety contracts for the shared PostgreSQL rebuild operation."""

import os
from pathlib import Path
import re
import shlex
import subprocess
import unittest

from test_build_scripts import function_body


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPOSITORY_ROOT / "scripts" / "operations" / "rebuild-database.sh"
V1_MIGRATION = (
    REPOSITORY_ROOT
    / "schema"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "V1__schema.sql"
)


class TestDatabaseRebuildContracts(unittest.TestCase):
    """The destructive operation must retain its backup and fail-closed boundaries."""

    def test_preserved_table_set_is_exact(self):
        """Only durable Catalog/Environment facts belong in the portable data dump."""
        source = SCRIPT.read_text()
        match = re.search(r"(?ms)^PRESERVED_TABLES=\(\n(?P<body>.*?)^\)$", source)
        self.assertIsNotNone(match)
        self.assertEqual(
            [
                "environment",
                "environment_skill_source",
                "environment_inventory",
                "environment_skill",
                "agent_provider",
                "agent_model",
                "agent_definition",
            ],
            match.group("body").split(),
        )

    def test_backup_and_restore_are_validated_and_atomic(self):
        """A verified full backup must exist before the one-transaction data restore."""
        backup = function_body(SCRIPT, "write_backups")
        restore = function_body(SCRIPT, "restore_preserved_data")
        replace = function_body(SCRIPT, "replace_database")

        self.assertIn("--format=custom --create", backup)
        self.assertIn("pg_restore --list", backup)
        self.assertIn("--format=custom --data-only", backup)
        self.assertIn("sha256sum", backup)
        self.assertIn("pg_restore -U", restore)
        self.assertIn("--single-transaction", restore)
        self.assertIn('2> "$RESTORE_LOG"', restore)
        self.assertIn('rename to \\"$SNAPSHOT_DB\\"', replace)
        self.assertIn('allow_connections false', replace)

    def test_flyway_checksum_is_bound_to_the_current_v1(self):
        """The Main image cannot silently rebuild from a different V1 revision."""
        command = (
            f"source {shlex.quote(str(SCRIPT))}; "
            f"flyway_checksum {shlex.quote(str(V1_MIGRATION))}"
        )
        result = subprocess.run(
            ["bash", "-c", command],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("-189635492", result.stdout.strip())

    def test_dry_run_does_not_reach_any_mutating_step(self):
        """Dry-run performs preflight and planning without files, stops, or SQL mutations."""
        mutating_functions = (
            "confirm_operation",
            "prepare_work_directory",
            "capture_initial_container_states",
            "stop_app_containers",
            "terminate_database_connections",
            "capture_preserved_state",
            "write_backups",
            "replace_database",
            "start_main_for_migration",
            "wait_for_flyway",
            "stop_main_after_migration",
            "require_empty_preserved_tables",
            "restore_preserved_data",
            "verify_preserved_data",
            "start_runtime",
            "wait_for_daemon_reconnect",
        )
        overrides = "\n".join(
            f"{name}() {{ echo MUTATION:{name}; }}" for name in mutating_functions
        )
        command = f"""
source {shlex.quote(str(SCRIPT))}
preflight() {{ :; }}
show_plan() {{ :; }}
{overrides}
main --dry-run
"""
        result = subprocess.run(
            ["bash", "-c", command],
            cwd=REPOSITORY_ROOT,
            env=dict(os.environ),
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("MUTATION:", result.stdout)
        self.assertIn("no files, containers, or databases were changed", result.stdout)

    def test_backup_output_inside_repository_is_rejected(self):
        """Sensitive archives must never be materialized beneath the Git workspace."""
        command = f"""
source {shlex.quote(str(SCRIPT))}
WORK_DIR="$APP_HOME/runtime/rebuild-database"
require_external_work_directory
"""
        result = subprocess.run(
            ["bash", "-c", command],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("backup output must be outside the repository", result.stderr)

    def test_work_directory_resolution_does_not_require_realpath(self):
        """The NAS BusyBox host has Python but no standalone realpath binary."""
        source = SCRIPT.read_text()
        preflight = function_body(SCRIPT, "preflight")
        work_directory_guard = function_body(SCRIPT, "require_external_work_directory")
        self.assertNotIn("require_command realpath", preflight)
        self.assertIn("os.path.realpath", work_directory_guard)
        self.assertNotIn("$(realpath", source)

    def test_script_is_executable_and_has_strict_shell_defaults(self):
        """Strict mode and private artifact permissions are non-negotiable."""
        source = SCRIPT.read_text()
        self.assertTrue(os.access(SCRIPT, os.X_OK))
        self.assertIn("set -euo pipefail", source)
        self.assertIn("umask 077", source)
        self.assertIn('trap on_exit EXIT', source)


if __name__ == "__main__":
    unittest.main()
