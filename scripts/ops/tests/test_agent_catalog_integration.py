#!/usr/bin/env python3
"""Integration tests for Agent catalog maintenance scripts against real PostgreSQL.

This module validates the end-to-end behavior of the three production maintenance scripts:
  - scripts/ops/export-agent-catalog.sh
  - scripts/ops/reset-database.sh
  - scripts/ops/import-agent-catalog.sh

These tests exercise real PostgreSQL server behaviors that unit and mock contracts cannot show:
  - Exact libpq connection and authentication mechanics without command-line passwords;
  - Real pg_dump and pg_restore binary custom-format archive creation and inspection;
  - Table locking, concurrent session detection via pg_stat_activity, and database freezing;
  - Atomic single-transaction COPY rollbacks on late constraint violations;
  - PostgreSQL locale, encoding, ICU settings, and connection limit preservation across reset;
  - Byte-for-byte fidelity of edge characters, JSON escapes, and timestamps across schemas.
"""

import atexit
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
from typing import Dict, List, Optional, Sequence
import unittest
import uuid

# 以仓库根为导入根：脚本模块以 `scripts.ops.*` 为包路径，测试不依赖调用者 cwd。
WORKTREE_ROOT = Path(__file__).resolve().parents[3]
if str(WORKTREE_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKTREE_ROOT))

from scripts.ops.agent_catalog import sanitize_error  # noqa: E402  (after sys.path setup)

OPS_DIR = WORKTREE_ROOT / "scripts" / "ops"
EXPORT_SCRIPT = OPS_DIR / "export-agent-catalog.sh"
RESET_SCRIPT = OPS_DIR / "reset-database.sh"
IMPORT_SCRIPT = OPS_DIR / "import-agent-catalog.sh"
LIB_MAINTENANCE = OPS_DIR / "lib" / "database-maintenance.sh"
V1_SCHEMA_SQL = (
    WORKTREE_ROOT / "schema" / "src" / "main" / "resources" / "db" / "migration" / "V1__schema.sql"
)
RESOURCES_DIR = OPS_DIR / "tests" / "resources" / "agent_catalog"
LEGACY_SOURCE_SQL = RESOURCES_DIR / "legacy_source.psql"
CURRENT_EDGE_SQL = RESOURCES_DIR / "current_edge_fixture.psql"
FLYWAY_HISTORY_SQL = RESOURCES_DIR / "flyway_v1_history.psql"

FIXTURE_PASSWORD = "probe-fixture-pg-secret-pass-789"
#: Fixture values that must never be surfaced by a maintenance script.  Assertions that look at
#: command output go through `assert_no_fixture_values` first, so a leak fails without echoing it.
FORBIDDEN_VALUES = (
    FIXTURE_PASSWORD,
    "wrong-secret-password-val-987",
    "fake-uri-secret-token-123",
    "fake-conninfo-secret-token-456",
    "probe-provider-credential",
    "probe-registration-token",
    "probe-edge-token",
    'line\nbreak\ttab\\backslash\\.dot"quote\'apostrophe-日本語-🚀',
    "line\nbreak\ttab",
    "apostrophe-日本語-🚀",
    'credential\nwith\ttabs\\and"quotes',
    "credential\nwith\ttabs",
    'https://probe.invalid/v1?x="y"\\z',
    "variant\nwith\nescape",
)


def _docker_available() -> bool:
    """True when docker CLI exists and the daemon responds to info."""
    if shutil.which("docker") is None:
        return False
    try:
        res = subprocess.run(
            ["docker", "info"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=10,
            check=False,
        )
        return res.returncode == 0
    except Exception:
        return False


@unittest.skipUnless(_docker_available(), "Docker daemon is not reachable")
class TestAgentCatalogIntegration(unittest.TestCase):
    """Integration test suite exercising production maintenance scripts on a real PostgreSQL container."""

    container_name: Optional[str] = None
    pg_port: Optional[int] = None
    passfile_dir: Optional[tempfile.TemporaryDirectory] = None
    passfile_path: Optional[Path] = None
    service_dir: Optional[tempfile.TemporaryDirectory] = None
    service_file: Optional[Path] = None
    client_env: Optional[Dict[str, str]] = None
    v1_checksum: Optional[str] = None

    @classmethod
    def setUpClass(cls) -> None:
        # Start throwaway PostgreSQL container with standard Alpine image.
        cls.container_name = f"kk-studio-integ-{uuid.uuid4().hex[:10]}"
        run_cmd = [
            "docker",
            "run",
            "--detach",
            "--name",
            cls.container_name,
            "--publish",
            "127.0.0.1::5432",
            "--env",
            f"POSTGRES_PASSWORD={FIXTURE_PASSWORD}",
            "postgres:17-alpine",
        ]
        try:
            subprocess.run(run_cmd, check=True, capture_output=True, text=True, timeout=60)
        except subprocess.CalledProcessError as err:
            raise RuntimeError(
                f"Failed to start PostgreSQL container: {err.stderr.strip()}"
            ) from err

        # Register cleanup to guarantee container removal even on abnormal process termination.
        def _cleanup() -> None:
            if cls.container_name:
                subprocess.run(
                    ["docker", "rm", "--force", "--volumes", cls.container_name],
                    check=False,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )

        atexit.register(_cleanup)

        # Parse published port from docker port output.
        port_proc = subprocess.run(
            ["docker", "port", cls.container_name, "5432/tcp"],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
        # Output format: 127.0.0.1:<port>
        match = re.search(r"127\.0\.0\.1:(\d+)", port_proc.stdout)
        if not match:
            raise RuntimeError(f"Could not parse port from docker port output: {port_proc.stdout}")
        cls.pg_port = int(match.group(1))

        # Prepare mode-0600 passfile for libpq authentication.
        cls.passfile_dir = tempfile.TemporaryDirectory(prefix="integ_pgpass_")
        cls.passfile_path = Path(cls.passfile_dir.name) / "pgpass"
        cls.passfile_path.write_text(
            f"127.0.0.1:{cls.pg_port}:*:postgres:{FIXTURE_PASSWORD}\n",
            encoding="utf-8",
        )
        os.chmod(cls.passfile_path, 0o600)

        # Construct inherited client environment for all child libpq and script processes: every
        # inherited PG* setting is removed first, so an operator's ambient service, passfile or
        # database can never influence (or authenticate) these tests.
        env = {key: value for key, value in os.environ.items() if not key.startswith("PG")}
        env["PGHOST"] = "127.0.0.1"
        env["PGPORT"] = str(cls.pg_port)
        env["PGUSER"] = "postgres"
        env["PGPASSFILE"] = str(cls.passfile_path)
        env["PGSSLMODE"] = "disable"
        env["KK_STUDIO_REPO_ROOT"] = str(WORKTREE_ROOT)
        cls.client_env = env

        # The documented connection contract is PGSERVICE + PGPASSFILE: keep one service file for
        # the tests that must prove that pair works without any PGHOST/PGPORT in the environment.
        cls.service_dir = tempfile.TemporaryDirectory(prefix="integ_pgservice_")
        cls.service_file = Path(cls.service_dir.name) / "pg_service.conf"
        cls.service_file.write_text(
            "[kk_studio_integration]\n"
            f"host=127.0.0.1\nport={cls.pg_port}\nuser=postgres\n",
            encoding="utf-8",
        )

        # Wait for PostgreSQL readiness via bounded pg_isready loop.
        ready = False
        deadline = time.time() + 120
        while time.time() < deadline:
            res = subprocess.run(
                [
                    "pg_isready",
                    "-h",
                    "127.0.0.1",
                    "-p",
                    str(cls.pg_port),
                    "-U",
                    "postgres",
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            if res.returncode == 0:
                ready = True
                break
            time.sleep(1)

        if not ready:
            raise RuntimeError("PostgreSQL container did not become ready within 120 seconds")

        # Obtain expected V1 checksum directly from the production shell library.
        res = subprocess.run(
            ["bash", "-c", f". '{LIB_MAINTENANCE}'; v1_checksum"],
            capture_output=True,
            text=True,
            check=True,
            env={"KK_STUDIO_REPO_ROOT": str(WORKTREE_ROOT)},
        )
        cls.v1_checksum = res.stdout.strip()
        if not re.fullmatch(r"-?\d+", cls.v1_checksum):
            raise RuntimeError(f"Invalid production V1 checksum computed: {cls.v1_checksum}")

    @classmethod
    def tearDownClass(cls) -> None:
        if cls.container_name:
            subprocess.run(
                ["docker", "rm", "--force", "--volumes", cls.container_name],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            cls.container_name = None
        if cls.passfile_dir:
            cls.passfile_dir.cleanup()
            cls.passfile_dir = None
        if cls.service_dir:
            cls.service_dir.cleanup()
            cls.service_dir = None

    def setUp(self) -> None:
        self.test_dir = tempfile.TemporaryDirectory(prefix="catalog_test_")
        self.created_databases: List[str] = []

    def tearDown(self) -> None:
        # Drop all test databases created during this test method.
        for db_name in set(self.created_databases):
            try:
                self.run_psql_command(
                    "postgres", f'DROP DATABASE IF EXISTS "{db_name}" WITH (FORCE)'
                )
            except Exception:
                pass
            # Also clean up any snapshot databases created by reset
            try:
                snapshots = self.run_psql_command(
                    "postgres",
                    f"SELECT datname FROM pg_database WHERE datname LIKE '{db_name}_pre_%'",
                ).splitlines()
                for snap in snapshots:
                    snap_name = snap.strip()
                    if snap_name:
                        self.run_psql_command(
                            "postgres", f'DROP DATABASE IF EXISTS "{snap_name}" WITH (FORCE)'
                        )
            except Exception:
                pass
        self.test_dir.cleanup()

    def register_db(self, db_name: str) -> str:
        self.created_databases.append(db_name)
        return db_name

    def assert_no_fixture_values(
        self, text: str, message: str = "Credential disclosure check"
    ) -> None:
        """Assert that no forbidden fixture value or secret substring leaked in text."""
        if not text:
            return
        for forbidden in FORBIDDEN_VALUES:
            if forbidden and forbidden in text:
                self.fail(f"{message}: output contained forbidden fixture/credential token")

    def run_psql_command(
        self, database: str, sql: str, env: Optional[Dict[str, str]] = None
    ) -> str:
        """Execute a SQL statement and return its stripped standard output."""
        use_env = self.client_env if env is None else env
        cmd = [
            "psql",
            "-X",
            "-q",
            "-A",
            "-t",
            "-v",
            "ON_ERROR_STOP=1",
            "--no-password",
            "-d",
            database,
            "-c",
            sql,
        ]
        res = subprocess.run(
            cmd,
            env=use_env,
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
        if res.returncode != 0:
            sanitized = sanitize_error(res.stderr)
            raise RuntimeError(f"psql command failed on {database}: {sanitized}")
        return res.stdout.strip()

    def run_psql(
        self, arguments: Sequence[str], database: str, env: Optional[Dict[str, str]] = None
    ) -> subprocess.CompletedProcess:
        """Run a raw psql invocation (used where the exact client argv matters)."""
        cmd = ["psql", "-X", "-q", "--no-password", "-d", database] + list(arguments)
        return subprocess.run(
            cmd,
            env=self.client_env if env is None else env,
            check=False,
            capture_output=True,
            text=True,
            timeout=60,
        )

    def run_psql_file(
        self,
        database: str,
        file_path: Path,
        psql_vars: Optional[Dict[str, str]] = None,
        env: Optional[Dict[str, str]] = None,
    ) -> None:
        """Apply a SQL fixture file with ON_ERROR_STOP=1 and optional psql variables."""
        use_env = self.client_env if env is None else env
        cmd = [
            "psql",
            "-X",
            "-q",
            "-v",
            "ON_ERROR_STOP=1",
            "--no-password",
            "-d",
            database,
        ]
        if psql_vars:
            for k, v in psql_vars.items():
                cmd.extend(["-v", f"{k}={v}"])
        cmd.extend(["-f", str(file_path)])
        res = subprocess.run(
            cmd,
            env=use_env,
            check=False,
            capture_output=True,
            text=True,
            timeout=60,
        )
        if res.returncode != 0:
            sanitized = sanitize_error(res.stderr)
            raise RuntimeError(f"psql file failed on {database} ({file_path.name}): {sanitized}")

    def create_empty_db(self, db_name: str) -> None:
        """Create an empty database via the maintenance connection."""
        self.register_db(db_name)
        self.run_psql_command("postgres", f'CREATE DATABASE "{db_name}"')

    def create_v1_database(self, db_name: str) -> None:
        """Create a target database initialized with V1 schema and successful Flyway history."""
        self.create_empty_db(db_name)
        self.run_psql_file(db_name, V1_SCHEMA_SQL)
        self.run_psql_file(
            db_name,
            FLYWAY_HISTORY_SQL,
            psql_vars={"v1_checksum": self.v1_checksum},
        )

    def run_script(
        self,
        script_path: Path,
        args: Sequence[str],
        env_override: Optional[Dict[str, str]] = None,
        timeout: int = 60,
    ) -> subprocess.CompletedProcess:
        """Run an ops maintenance script and assert that no fixture value leaked into output."""
        env = dict(self.client_env)
        if env_override:
            for k, v in env_override.items():
                if v is None:
                    env.pop(k, None)
                else:
                    env[k] = v
        cmd = ["bash", str(script_path)] + list(args)
        res = subprocess.run(
            cmd,
            env=env,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        self.assert_no_fixture_values(res.stdout, f"{script_path.name} stdout")
        self.assert_no_fixture_values(res.stderr, f"{script_path.name} stderr")
        return res

    @staticmethod
    def parse_facts(output: str) -> Dict[str, str]:
        """Extract key=value fact pairs printed by catalog maintenance scripts."""
        return dict(re.findall(r"^([a-zA-Z0-9_.]+)=(.*)$", output, re.MULTILINE))

    # -------------------------------------------------------------------------
    # Test 1: Complete legacy flow
    # -------------------------------------------------------------------------

    def test_01_complete_legacy_flow(self) -> None:
        # Test intent:
        # Verify the complete migration lifecycle from an origin/main-shaped database through
        # catalog export, full reset with snapshot retention, Flyway V1 schema application,
        # and catalog import. Confirms that credentials and wire configurations survive,
        # deprecated columns/tables are eliminated, unmigrated tables remain uncopied, and
        # server metadata (encoding, locale, owner, connection limit) is strictly preserved.
        db_name = self.register_db(f"probe_flow_{uuid.uuid4().hex[:8]}")
        self.create_empty_db(db_name)
        self.run_psql_file(db_name, LEGACY_SOURCE_SQL)

        # Set an explicit connection limit before reset to verify it is preserved.
        self.run_psql_command("postgres", f'ALTER DATABASE "{db_name}" CONNECTION LIMIT 7')

        initial_counts = {
            "agent_provider": 1,
            "agent_model": 1,
            "agent_definition": 2,
            "environment": 1,
            "mcp_tool": 1,
            "environment_skill_source": 1,
            "environment_skill": 1,
        }
        for table, expected in initial_counts.items():
            cnt = int(self.run_psql_command(db_name, f"SELECT count(*) FROM {table}"))
            self.assertEqual(cnt, expected, f"Initial count mismatch for {table}")

        meta_before = self.run_psql_command(
            "postgres",
            f"SELECT pg_encoding_to_char(encoding), datcollate, pg_get_userbyid(datdba), datconnlimit FROM pg_database WHERE datname = '{db_name}'",
        ).split("|")
        orig_encoding, orig_collate, orig_owner, orig_connlimit = meta_before
        self.assertEqual(int(orig_connlimit), 7)

        # 1. Export catalog from legacy database.
        export_work_dir = Path(self.test_dir.name) / "export_pkg"
        res_export = self.run_script(
            EXPORT_SCRIPT,
            ["--work-dir", str(export_work_dir)],
            env_override={"PGDATABASE": db_name},
        )
        self.assertEqual(
            res_export.returncode,
            0,
            f"Export failed: {sanitize_error(res_export.stderr)}",
        )
        export_facts = self.parse_facts(res_export.stdout)
        pkg_dir = Path(export_facts["package_dir"])

        # Assert package directory and artifacts permissions.
        self.assertTrue(pkg_dir.is_dir())
        self.assertEqual(pkg_dir.stat().st_mode & 0o777, 0o700)
        for artifact_name in ("catalog.sql", "manifest.json", "sha256sums.txt"):
            artifact = pkg_dir / artifact_name
            self.assertTrue(artifact.is_file(), f"Missing artifact: {artifact_name}")
            self.assertEqual(
                artifact.stat().st_mode & 0o777,
                0o600,
                f"Artifact {artifact_name} should be mode 0600",
            )

        # Assert source database was left untouched by export.
        for table, expected in initial_counts.items():
            cnt = int(self.run_psql_command(db_name, f"SELECT count(*) FROM {table}"))
            self.assertEqual(cnt, expected, f"Source table {table} was mutated during export")

        # 2. Reset database.
        reset_work_dir = Path(self.test_dir.name) / "reset_backup"
        res_reset = self.run_script(
            RESET_SCRIPT,
            ["--yes", "--work-dir", str(reset_work_dir)],
            env_override={"PGDATABASE": db_name},
        )
        self.assertEqual(
            res_reset.returncode,
            0,
            f"Reset failed: {sanitize_error(res_reset.stderr)}",
        )

        # Assert backup files exist and are mode 0600.
        dump_files = list(reset_work_dir.glob("*.dump"))
        self.assertEqual(len(dump_files), 1, "Expected exactly one .dump file")
        dump_file = dump_files[0]
        checksum_file = reset_work_dir / f"{dump_file.name}.sha256"
        self.assertTrue(checksum_file.is_file(), "Missing backup checksum file")
        self.assertEqual(dump_file.stat().st_mode & 0o777, 0o600)
        self.assertEqual(checksum_file.stat().st_mode & 0o777, 0o600)

        # Verify backup sha256 checksum.
        actual_dump_sha = hashlib.sha256(dump_file.read_bytes()).hexdigest()
        self.assertEqual(
            f"{actual_dump_sha}  {dump_file.name}",
            checksum_file.read_text(encoding="utf-8").strip(),
        )

        # Assert pg_restore --list accepts the backup archive.
        restore_res = subprocess.run(
            ["pg_restore", "--no-password", "--list", str(dump_file)],
            env=self.client_env,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(restore_res.returncode, 0, "pg_restore rejected the backup file")

        # Assert frozen snapshot exists with datallowconn = f and holds the legacy rows.
        snapshots = self.run_psql_command(
            "postgres",
            f"SELECT datname, datallowconn FROM pg_database WHERE datname LIKE '{db_name}_pre_%'",
        ).splitlines()
        self.assertEqual(len(snapshots), 1, "Expected exactly one snapshot database")
        snap_name, allow_conn = snapshots[0].split("|")
        self.assertEqual(allow_conn, "f", "Snapshot database must not allow connections")

        # Temporarily enable connections on snapshot to verify it holds the original rows.
        self.run_psql_command("postgres", f'ALTER DATABASE "{snap_name}" ALLOW_CONNECTIONS true')
        try:
            for table, expected in initial_counts.items():
                cnt = int(self.run_psql_command(snap_name, f"SELECT count(*) FROM {table}"))
                self.assertEqual(cnt, expected, f"Snapshot table {table} count mismatch")
        finally:
            self.run_psql_command(
                "postgres", f'ALTER DATABASE "{snap_name}" ALLOW_CONNECTIONS false'
            )

        # Assert recreated database is completely empty.
        tables_in_recreated = int(
            self.run_psql_command(
                db_name,
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'",
            )
        )
        self.assertEqual(tables_in_recreated, 0, "Recreated database is not empty")

        # Assert recreated database preserves metadata and connection limit.
        meta_after = self.run_psql_command(
            "postgres",
            f"SELECT pg_encoding_to_char(encoding), datcollate, pg_get_userbyid(datdba), datconnlimit FROM pg_database WHERE datname = '{db_name}'",
        ).split("|")
        new_encoding, new_collate, new_owner, new_connlimit = meta_after
        self.assertEqual(new_encoding, orig_encoding)
        self.assertEqual(new_collate, orig_collate)
        self.assertEqual(new_owner, orig_owner)
        self.assertEqual(int(new_connlimit), 7)

        # 3. Simulate application startup running Flyway V1 migration on the empty database.
        self.run_psql_file(db_name, V1_SCHEMA_SQL)
        self.run_psql_file(
            db_name,
            FLYWAY_HISTORY_SQL,
            psql_vars={"v1_checksum": self.v1_checksum},
        )

        # 4. Import the catalog package into the recreated V1 database.
        import_work_dir = Path(self.test_dir.name) / "import_log"
        res_import = self.run_script(
            IMPORT_SCRIPT,
            ["--package", str(pkg_dir), "--work-dir", str(import_work_dir)],
            env_override={"PGDATABASE": db_name},
        )
        self.assertEqual(
            res_import.returncode,
            0,
            f"Import failed: {sanitize_error(res_import.stderr)}",
        )

        # 5. Assert data restoration and schema evolution assertions.
        # Credential survived:
        cred_ok = self.run_psql_command(
            db_name,
            "SELECT credential = 'probe-provider-credential' FROM agent_provider WHERE name = 'probe-provider'",
        )
        self.assertEqual(cred_ok, "t", "Provider credential was not restored accurately")

        # Bound agent config matches the current wire shape:
        bound_config_ok = self.run_psql_command(
            db_name,
            """SELECT config = '{"tools": ["read", "lsp_goto_definition", "probe_mcp_tool", "update_goal"], "skills": [], "subagents": ["probe-child"], "inheritParentEnvironment": true}'::jsonb
               FROM agent_definition WHERE name = 'bound-agent'""",
        )
        self.assertEqual(bound_config_ok, "t", "Bound agent config wire shape mismatch")

        # Unbound agent config matches the current wire shape:
        unbound_config_ok = self.run_psql_command(
            db_name,
            """SELECT config = '{"tools": [], "skills": [], "subagents": [], "inheritParentEnvironment": true}'::jsonb
               FROM agent_definition WHERE name = 'unbound-agent'""",
        )
        self.assertEqual(unbound_config_ok, "t", "Unbound agent config wire shape mismatch")

        # Non-config columns remain unchanged:
        bound_non_config_ok = self.run_psql_command(
            db_name,
            """SELECT (
                description = 'probe agent description'
                AND system_prompt = 'probe system prompt'
                AND model_provider_name = 'probe-provider'
                AND model_name = 'probe-model'
                AND variant = 'probe-variant'
                AND version = 4
                AND created_at = '2024-01-07 00:00:00.005+00'::timestamptz
                AND updated_at = '2024-01-08 00:00:00.006+00'::timestamptz
            ) FROM agent_definition WHERE name = 'bound-agent'""",
        )
        self.assertEqual(bound_non_config_ok, "t", "Bound agent non-config columns mutated")

        unbound_non_config_ok = self.run_psql_command(
            db_name,
            """SELECT (
                description IS NULL
                AND system_prompt IS NULL
                AND model_provider_name = 'probe-provider'
                AND model_name = 'probe-model'
                AND variant IS NULL
                AND version = 5
                AND created_at = '2024-01-09 00:00:00.007+00'::timestamptz
                AND updated_at = '2024-01-10 00:00:00.008+00'::timestamptz
            ) FROM agent_definition WHERE name = 'unbound-agent'""",
        )
        self.assertEqual(unbound_non_config_ok, "t", "Unbound agent non-config columns mutated")

        # Deprecated agent_definition.environment_id column does NOT exist in V1 schema:
        env_id_col = int(
            self.run_psql_command(
                db_name,
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'agent_definition' AND column_name = 'environment_id'",
            )
        )
        self.assertEqual(env_id_col, 0, "Deprecated environment_id column still exists")

        # Legacy environment tables were NOT recreated:
        legacy_tables = int(
            self.run_psql_command(
                db_name,
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('environment_skill', 'environment_skill_source')",
            )
        )
        self.assertEqual(legacy_tables, 0, "Legacy environment skill tables were recreated")

        # Environment row was NOT migrated (current environment table is empty):
        env_rows = int(self.run_psql_command(db_name, "SELECT count(*) FROM public.environment"))
        self.assertEqual(env_rows, 0, "Legacy environment rows were incorrectly migrated")

    # -------------------------------------------------------------------------
    # Test 2: Current-baseline hostile round trip
    # -------------------------------------------------------------------------

    def test_02_current_baseline_hostile_round_trip(self) -> None:
        # Test intent:
        # Verify that a full export-import cycle on current V1 schema preserves all edge cases
        # byte-for-byte, including hostile text escapes (newline, tab, backslash, \. delimiter,
        # quotes, non-ASCII Unicode), NULL vs empty string differentiation, JSON config objects,
        # and timestamptz instants. Validates that import fingerprints and row counts strictly match
        # export facts, and performs in-database SQL assertions so values never echo to Python.
        src_db = self.register_db(f"probe_edge_src_{uuid.uuid4().hex[:8]}")
        tgt_db = self.register_db(f"probe_edge_tgt_{uuid.uuid4().hex[:8]}")

        self.create_v1_database(src_db)
        self.run_psql_file(src_db, CURRENT_EDGE_SQL)

        self.create_v1_database(tgt_db)

        # 1. Export from edge source database.
        export_work_dir = Path(self.test_dir.name) / "edge_export"
        res_export = self.run_script(
            EXPORT_SCRIPT,
            ["--work-dir", str(export_work_dir)],
            env_override={"PGDATABASE": src_db},
        )
        self.assertEqual(
            res_export.returncode,
            0,
            f"Export failed: {sanitize_error(res_export.stderr)}",
        )
        export_facts = self.parse_facts(res_export.stdout)
        pkg_dir = Path(export_facts["package_dir"])

        # 2. Import into empty target database.
        import_work_dir = Path(self.test_dir.name) / "edge_import_log"
        res_import = self.run_script(
            IMPORT_SCRIPT,
            ["--package", str(pkg_dir), "--work-dir", str(import_work_dir)],
            env_override={"PGDATABASE": tgt_db},
        )
        self.assertEqual(
            res_import.returncode,
            0,
            f"Import failed: {sanitize_error(res_import.stderr)}",
        )
        import_facts = self.parse_facts(res_import.stdout)

        # Assert table counts and fingerprints match identically between export and import.
        for table in ("agent_provider", "agent_model", "agent_definition"):
            self.assertEqual(
                import_facts[f"rows.{table}"],
                export_facts[f"rows.{table}"],
                f"Row count mismatch for {table}",
            )
            self.assertEqual(
                import_facts[f"fingerprint.{table}"],
                export_facts[f"fingerprint.{table}"],
                f"Fingerprint mismatch for {table}",
            )

        # 3. Verify in-database data integrity via boolean SQL predicates (no strings returned to Python).
        # Probe edge provider:
        edge_provider_ok = self.run_psql_command(
            tgt_db,
            r"""SELECT (
                description = E'line\nbreak\ttab\\backslash\\.dot"quote\'apostrophe-日本語-🚀'
                AND provider_type = 'openai'
                AND base_url = 'https://probe.invalid/v1?x="y"\\z'
                AND credential = E'credential\nwith\ttabs\\and"quotes'
                AND config = '{"modelCallTimeoutMillis": 600000}'::jsonb
                AND connection_generation_id = '88888888-8888-4888-8888-888888888888'::uuid
                AND created_at = '2024-03-03 00:00:00.001+00'::timestamptz
                AND updated_at = '2024-03-04 00:00:00.002+00'::timestamptz
                AND version = 1
            ) FROM agent_provider WHERE name = 'probe-edge-provider'""",
        )
        self.assertEqual(edge_provider_ok, "t", "Edge provider data corrupted")

        # Probe empty provider:
        empty_provider_ok = self.run_psql_command(
            tgt_db,
            """SELECT (
                description = ''
                AND provider_type = 'anthropic'
                AND base_url IS NULL
                AND credential = ''
                AND config = '{}'::jsonb
                AND connection_generation_id = '99999999-9999-4999-8999-999999999999'::uuid
                AND created_at = '2024-03-05 00:00:00+00'::timestamptz
                AND updated_at = '2024-03-05 00:00:00+00'::timestamptz
                AND version = 0
            ) FROM agent_provider WHERE name = 'probe-empty-provider'""",
        )
        self.assertEqual(empty_provider_ok, "t", "Empty provider data corrupted")

        # Probe null provider:
        null_provider_ok = self.run_psql_command(
            tgt_db,
            """SELECT (
                description IS NULL
                AND provider_type = 'google'
                AND base_url IS NULL
                AND credential IS NULL
                AND config = '{}'::jsonb
                AND connection_generation_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid
                AND created_at = '2024-03-06 00:00:00+00'::timestamptz
                AND updated_at = '2024-03-06 00:00:00+00'::timestamptz
                AND version = 0
            ) FROM agent_provider WHERE name = 'probe-null-provider'""",
        )
        self.assertEqual(null_provider_ok, "t", "Null provider data corrupted")

        # Probe edge model:
        edge_model_ok = self.run_psql_command(
            tgt_db,
            r"""SELECT (
                provider_name = 'probe-edge-provider'
                AND model_id = 'probe-edge-wire-model'
                AND description = E'line\nbreak\ttab\\backslash\\.dot"quote\'apostrophe-日本語-🚀'
                AND config = '{"variants": [{"id": "variant\nwith\nescape"}]}'::jsonb
                AND created_at = '2024-03-07 00:00:00.003+00'::timestamptz
                AND updated_at = '2024-03-08 00:00:00.004+00'::timestamptz
                AND version = 2
            ) FROM agent_model WHERE name = 'probe-edge-model'""",
        )
        self.assertEqual(edge_model_ok, "t", "Edge model data corrupted")

        # Probe edge agent:
        edge_agent_ok = self.run_psql_command(
            tgt_db,
            r"""SELECT (
                description = E'line\nbreak\ttab\\backslash\\.dot"quote\'apostrophe-日本語-🚀'
                AND system_prompt = E'line\nbreak\ttab\\backslash\\.dot"quote\'apostrophe-日本語-🚀'
                AND model_provider_name = 'probe-edge-provider'
                AND model_name = 'probe-edge-model'
                AND variant = 'probe-variant'
                AND config = '{"tools": ["read"], "skills": [{"packageName": "probe-edge-package", "name": "probe-skill"}], "subagents": [], "inheritParentEnvironment": false}'::jsonb
                AND created_at = '2024-03-12 00:00:00.123+02:00'::timestamptz
                AND updated_at = '2024-03-12 23:00:00.456-08:00'::timestamptz
                AND version = 9
            ) FROM agent_definition WHERE name = 'probe-edge-agent'""",
        )
        self.assertEqual(edge_agent_ok, "t", "Edge agent data corrupted")

        # Probe null agent:
        null_agent_ok = self.run_psql_command(
            tgt_db,
            """SELECT (
                description IS NULL
                AND system_prompt = ''
                AND model_provider_name = 'probe-edge-provider'
                AND model_name = 'probe-edge-model'
                AND variant IS NULL
                AND config = '{"tools": [], "skills": [], "subagents": [], "inheritParentEnvironment": true}'::jsonb
                AND created_at = '2024-03-13 00:00:00+00'::timestamptz
                AND updated_at = '2024-03-13 00:00:00+00'::timestamptz
                AND version = 0
            ) FROM agent_definition WHERE name = 'probe-null-agent'""",
        )
        self.assertEqual(null_agent_ok, "t", "Null agent data corrupted")

    # -------------------------------------------------------------------------
    # Test 3: Import guards
    # -------------------------------------------------------------------------

    def test_03_import_guards(self) -> None:
        # Test intent:
        # Prove that import preflight guards fail closed on dirty targets or invalid packages
        # without mutating the target database:
        #   1. Non-empty agent_provider refuses import and leaves data intact;
        #   2. flyway_schema_history with mismatched V1 checksum refuses import;
        #   3. Tampered catalog.sql failing sha256 checksum refuses import;
        #   4. Package directory or artifact with group/other readable bits refuses import.
        src_db = self.register_db(f"probe_guard_src_{uuid.uuid4().hex[:8]}")
        self.create_v1_database(src_db)
        self.run_psql_file(src_db, CURRENT_EDGE_SQL)

        export_work_dir = Path(self.test_dir.name) / "guard_export"
        res_export = self.run_script(
            EXPORT_SCRIPT,
            ["--work-dir", str(export_work_dir)],
            env_override={"PGDATABASE": src_db},
        )
        self.assertEqual(res_export.returncode, 0)
        export_facts = self.parse_facts(res_export.stdout)
        valid_pkg = Path(export_facts["package_dir"])

        # Guard 1: Non-empty agent_provider refuses import and target is untouched.
        with self.subTest("non_empty_agent_provider_refused"):
            target_1 = self.register_db(f"probe_guard_nonempty_{uuid.uuid4().hex[:8]}")
            self.create_v1_database(target_1)
            self.run_psql_command(
                target_1,
                """INSERT INTO agent_provider (name, provider_type, config, connection_generation_id)
                   VALUES ('existing-provider', 'openai', '{}'::jsonb, '11111111-2222-3333-4444-555555555555')""",
            )

            res = self.run_script(
                IMPORT_SCRIPT,
                [
                    "--package",
                    str(valid_pkg),
                    "--work-dir",
                    str(Path(self.test_dir.name) / "log1"),
                ],
                env_override={"PGDATABASE": target_1},
            )
            self.assertNotEqual(res.returncode, 0, "Import should refuse non-empty agent_provider")
            self.assertIn("import requires an empty agent_provider", res.stderr)
            # Assert target database was not mutated:
            self.assertEqual(
                int(self.run_psql_command(target_1, "SELECT count(*) FROM agent_provider")),
                1,
            )
            self.assertEqual(
                int(self.run_psql_command(target_1, "SELECT count(*) FROM agent_model")), 0
            )
            self.assertEqual(
                int(self.run_psql_command(target_1, "SELECT count(*) FROM agent_definition")),
                0,
            )

        # Guard 2: Mismatched flyway_schema_history V1 checksum refuses import.
        with self.subTest("mismatched_v1_checksum_refused"):
            target_2 = self.register_db(f"probe_guard_bad_cs_{uuid.uuid4().hex[:8]}")
            self.create_empty_db(target_2)
            self.run_psql_file(target_2, V1_SCHEMA_SQL)
            self.run_psql_file(
                target_2,
                FLYWAY_HISTORY_SQL,
                psql_vars={"v1_checksum": "12345678"},
            )

            res = self.run_script(
                IMPORT_SCRIPT,
                [
                    "--package",
                    str(valid_pkg),
                    "--work-dir",
                    str(Path(self.test_dir.name) / "log2"),
                ],
                env_override={"PGDATABASE": target_2},
            )
            self.assertNotEqual(
                res.returncode, 0, "Import should refuse mismatched Flyway checksum"
            )
            self.assertIn("differs from this checkout", res.stderr)
            for table in ("agent_provider", "agent_model", "agent_definition"):
                self.assertEqual(
                    int(self.run_psql_command(target_2, f"SELECT count(*) FROM {table}")),
                    0,
                )

        # Guard 3: Tampered catalog.sql checksum mismatch refuses import.
        with self.subTest("tampered_catalog_sql_refused"):
            target_3 = self.register_db(f"probe_guard_tampered_{uuid.uuid4().hex[:8]}")
            self.create_v1_database(target_3)

            tampered_pkg = Path(self.test_dir.name) / "tampered_pkg"
            shutil.copytree(valid_pkg, tampered_pkg)
            os.chmod(tampered_pkg, 0o700)
            for item in tampered_pkg.iterdir():
                os.chmod(item, 0o600)

            # Append a comment into catalog.sql without updating manifest checksum
            with open(tampered_pkg / "catalog.sql", "a", encoding="utf-8") as f:
                f.write("\n-- tampered trailing comment\n")

            res = self.run_script(
                IMPORT_SCRIPT,
                [
                    "--package",
                    str(tampered_pkg),
                    "--work-dir",
                    str(Path(self.test_dir.name) / "log3"),
                ],
                env_override={"PGDATABASE": target_3},
            )
            self.assertNotEqual(res.returncode, 0, "Import should refuse tampered catalog.sql")
            self.assertIn("package bundle does not match its manifest checksum", res.stderr)
            for table in ("agent_provider", "agent_model", "agent_definition"):
                self.assertEqual(
                    int(self.run_psql_command(target_3, f"SELECT count(*) FROM {table}")),
                    0,
                )

        # Guard 4: Group/other readable package dir or artifact refuses import.
        with self.subTest("group_other_readable_permissions_refused"):
            target_4 = self.register_db(f"probe_guard_perms_{uuid.uuid4().hex[:8]}")
            self.create_v1_database(target_4)

            perm_pkg = Path(self.test_dir.name) / "perm_pkg"
            shutil.copytree(valid_pkg, perm_pkg)
            os.chmod(perm_pkg, 0o755)

            # 4a: Directory mode 0755 rejected
            res = self.run_script(
                IMPORT_SCRIPT,
                [
                    "--package",
                    str(perm_pkg),
                    "--work-dir",
                    str(Path(self.test_dir.name) / "log4a"),
                ],
                env_override={"PGDATABASE": target_4},
            )
            self.assertNotEqual(
                res.returncode, 0, "Import should refuse group-readable package directory"
            )
            self.assertIn("group/other accessible", res.stderr)

            # 4b: Directory 0700 but artifact mode 0644 rejected
            os.chmod(perm_pkg, 0o700)
            os.chmod(perm_pkg / "manifest.json", 0o644)
            res = self.run_script(
                IMPORT_SCRIPT,
                [
                    "--package",
                    str(perm_pkg),
                    "--work-dir",
                    str(Path(self.test_dir.name) / "log4b"),
                ],
                env_override={"PGDATABASE": target_4},
            )
            self.assertNotEqual(
                res.returncode, 0, "Import should refuse group-readable manifest.json"
            )
            self.assertIn("group/other accessible", res.stderr)

            # Restore mode 0600 on manifest to facilitate cleanup
            os.chmod(perm_pkg / "manifest.json", 0o600)
            for table in ("agent_provider", "agent_model", "agent_definition"):
                self.assertEqual(
                    int(self.run_psql_command(target_4, f"SELECT count(*) FROM {table}")),
                    0,
                )

    # -------------------------------------------------------------------------
    # Test 4: Atomic late failure
    # -------------------------------------------------------------------------

    def test_04_atomic_late_failure(self) -> None:
        # Test intent:
        # Prove atomic rollback on late execution failure: when the last migrated table's
        # COPY stream fails (check constraint violation in agent_definition), the single transaction
        # ensures earlier successful COPY streams (agent_provider, agent_model) are rolled back,
        # leaving the database completely empty. Verifies that the failure log is mode 0600,
        # identifies agent_definition without leaking secondary detail lines, and confirms that
        # the target database remains cleanly reusable for an intact package import.
        src_db = self.register_db(f"probe_late_src_{uuid.uuid4().hex[:8]}")
        self.create_empty_db(src_db)
        self.run_psql_file(src_db, LEGACY_SOURCE_SQL)

        export_work_dir = Path(self.test_dir.name) / "late_export"
        res_export = self.run_script(
            EXPORT_SCRIPT,
            ["--work-dir", str(export_work_dir)],
            env_override={"PGDATABASE": src_db},
        )
        self.assertEqual(res_export.returncode, 0)
        export_facts = self.parse_facts(res_export.stdout)
        valid_pkg = Path(export_facts["package_dir"])

        # Create a corrupted package: rewrite the last row of agent_definition in catalog.sql
        # to a value violating ck_agent_definition_name (e.g. leading space and slash).
        corrupt_pkg = Path(self.test_dir.name) / "corrupt_pkg"
        shutil.copytree(valid_pkg, corrupt_pkg)
        os.chmod(corrupt_pkg, 0o700)
        for item in corrupt_pkg.iterdir():
            os.chmod(item, 0o600)

        sql_path = corrupt_pkg / "catalog.sql"
        sql_content = sql_path.read_text(encoding="utf-8")

        # Corrupt the last row of agent_definition
        # ck_agent_definition_name forbids leading whitespace and slashes
        marker = "copy public.agent_definition"
        idx = sql_content.rfind(marker)
        self.assertNotEqual(idx, -1, "agent_definition COPY block not found in catalog.sql")
        end_marker = "\n\\.\n"
        end_idx = sql_content.find(end_marker, idx)
        self.assertNotEqual(end_idx, -1, "End of agent_definition COPY block not found")

        block = sql_content[idx:end_idx]
        lines = block.splitlines()
        last_row = lines[-1]
        parts = last_row.split(",", 1)
        corrupted_row = '" invalid/definition/name",' + parts[1]
        lines[-1] = corrupted_row
        new_block = "\n".join(lines)
        corrupted_sql = sql_content[:idx] + new_block + sql_content[end_idx:]

        sql_path.write_text(corrupted_sql, encoding="utf-8")
        new_sha = hashlib.sha256(corrupted_sql.encode("utf-8")).hexdigest()

        # Update sha256sums.txt and manifest.json so package hash verification passes
        (corrupt_pkg / "sha256sums.txt").write_text(
            f"{new_sha}  catalog.sql\n", encoding="utf-8"
        )
        manifest_data = json.loads((corrupt_pkg / "manifest.json").read_text(encoding="utf-8"))
        manifest_data["bundle_sha256"] = new_sha
        (corrupt_pkg / "manifest.json").write_text(
            json.dumps(manifest_data, indent=2) + "\n", encoding="utf-8"
        )

        # Restore mode 0600 on all modified files
        for item in corrupt_pkg.iterdir():
            os.chmod(item, 0o600)

        target_db = self.register_db(f"probe_late_tgt_{uuid.uuid4().hex[:8]}")
        self.create_v1_database(target_db)

        # Import the corrupted package: must fail during SQL COPY of the last table
        failure_log_dir = Path(self.test_dir.name) / "late_fail_logs"
        res_fail = self.run_script(
            IMPORT_SCRIPT,
            ["--package", str(corrupt_pkg), "--work-dir", str(failure_log_dir)],
            env_override={"PGDATABASE": target_db},
        )
        self.assertNotEqual(
            res_fail.returncode, 0, "Corrupted SQL row import should exit non-zero"
        )

        # Assert atomic rollback: ALL three tables are empty
        for table in ("agent_provider", "agent_model", "agent_definition"):
            self.assertEqual(
                int(self.run_psql_command(target_db, f"SELECT count(*) FROM {table}")),
                0,
                f"Table {table} was not rolled back after late failure",
            )

        # Inspect failure log
        logs = list(failure_log_dir.glob("restore-*.log"))
        self.assertEqual(len(logs), 1, "Expected exactly one sanitized failure log")
        log_file = logs[0]
        self.assertEqual(log_file.stat().st_mode & 0o777, 0o600, "Failure log must be mode 0600")

        log_content = log_file.read_text(encoding="utf-8")
        # The retained log carries SQLSTATE codes and our own categories: never a row value and
        # never a message that could quote one, so the corrupted row must not appear anywhere.
        self.assertIn("(SQLSTATE ", log_content)
        self.assertNotIn("invalid/definition/name", log_content)
        self.assertNotIn("agent_definition", log_content, "the log must not name a catalog table")
        for line in log_content.splitlines():
            self.assertRegex(
                line.strip(),
                r"^(psql:.+: )?[a-z][a-z0-9 ,\-()]* \(SQLSTATE [0-9A-Z]{5}\)$|^\(\d+ further line",
                f"Sanitized log kept an unsafe line: {line}",
            )

        # Retry with the intact valid package into the same database: must succeed
        res_retry = self.run_script(
            IMPORT_SCRIPT,
            ["--package", str(valid_pkg), "--work-dir", str(failure_log_dir)],
            env_override={"PGDATABASE": target_db},
        )
        self.assertEqual(
            res_retry.returncode,
            0,
            f"Intact package import retry failed: {sanitize_error(res_retry.stderr)}",
        )
        self.assertEqual(
            int(self.run_psql_command(target_db, "SELECT count(*) FROM agent_provider")),
            1,
        )
        self.assertEqual(
            int(self.run_psql_command(target_db, "SELECT count(*) FROM agent_model")), 1
        )
        self.assertEqual(
            int(self.run_psql_command(target_db, "SELECT count(*) FROM agent_definition")),
            2,
        )

    # -------------------------------------------------------------------------
    # Test 8: Transaction-internal import guards
    # -------------------------------------------------------------------------

    def test_08_transaction_internal_import_guards(self) -> None:
        # Test intent:
        # The import's preflight checks are advisory; these subtests prove the restore transaction
        # itself refuses to commit anything when the tables are no longer empty, when the restored
        # rows do not match the package fingerprints, or when it is run outside its transaction.
        src_db = self.register_db(f"probe_guard_src_{uuid.uuid4().hex[:8]}")
        self.create_empty_db(src_db)
        self.run_psql_file(src_db, LEGACY_SOURCE_SQL)
        res_export = self.run_script(
            EXPORT_SCRIPT,
            ["--work-dir", str(Path(self.test_dir.name) / "guard_export")],
            env_override={"PGDATABASE": src_db},
        )
        self.assertEqual(res_export.returncode, 0, sanitize_error(res_export.stderr))
        package = Path(self.parse_facts(res_export.stdout)["package_dir"])

        def counts(database: str) -> List[int]:
            return [
                int(self.run_psql_command(database, f"SELECT count(*) FROM {table}"))
                for table in ("agent_provider", "agent_model", "agent_definition")
            ]

        with self.subTest("guard_refuses_a_table_that_became_dirty"):
            target = self.register_db(f"probe_guard_dirty_{uuid.uuid4().hex[:8]}")
            self.create_v1_database(target)
            # A writer reaches the tables after the preflight empty check but before the restore.
            self.run_psql_command(
                target,
                "INSERT INTO agent_provider (name, provider_type, config,"
                " connection_generation_id) VALUES ('late-writer', 'openai', '{}',"
                " '33333333-3333-4333-8333-333333333333')",
            )
            bundle = package / "catalog.sql"
            res = self.run_psql(
                ["--single-transaction", "-v", "ON_ERROR_STOP=1", "-f", str(bundle)],
                target,
            )
            self.assertNotEqual(res.returncode, 0, "A dirty table must fail the restore guard")
            self.assertIn("KK001", res.stderr)
            self.assertEqual(
                counts(target), [1, 0, 0], "The restore must not commit anything into a dirty target"
            )

            # Without --single-transaction LOCK TABLE is rejected, so nothing can be committed.
            self.run_psql_command(target, "DELETE FROM agent_provider")
            untransacted = self.run_psql(
                ["-v", "ON_ERROR_STOP=1", "-f", str(bundle)], target
            )
            self.assertNotEqual(untransacted.returncode, 0)
            self.assertIn("25P01", untransacted.stderr)
            self.assertEqual(counts(target), [0, 0, 0])

        with self.subTest("fingerprint_mismatch_rolls_the_import_back"):
            target = self.register_db(f"probe_guard_mismatch_{uuid.uuid4().hex[:8]}")
            self.create_v1_database(target)

            # A package whose bundle footer expects a different digest for one table: the layout,
            # the checksums and the row counts stay consistent, so only the transaction can catch it.
            mismatched = Path(self.test_dir.name) / "mismatched_pkg"
            shutil.copytree(package, mismatched)
            os.chmod(mismatched, 0o700)
            for artifact in mismatched.iterdir():
                os.chmod(artifact, 0o600)
            manifest = json.loads((mismatched / "manifest.json").read_text(encoding="utf-8"))
            entry = manifest["tables"][1]
            rows, digest = entry["fingerprint"].split(":", 1)
            flipped = ("0" if digest[0] != "0" else "1") + digest[1:]
            entry["fingerprint"] = f"{rows}:{flipped}"
            bundle_path = mismatched / "catalog.sql"
            bundle_path.write_text(
                bundle_path.read_text(encoding="utf-8").replace(digest, flipped), encoding="utf-8"
            )
            new_sha = hashlib.sha256(bundle_path.read_bytes()).hexdigest()
            manifest["bundle_sha256"] = new_sha
            (mismatched / "manifest.json").write_text(
                json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
            )
            (mismatched / "sha256sums.txt").write_text(f"{new_sha}  catalog.sql\n", encoding="utf-8")
            for artifact in mismatched.iterdir():
                os.chmod(artifact, 0o600)

            log_dir = Path(self.test_dir.name) / "guard_logs"
            res = self.run_script(
                IMPORT_SCRIPT,
                ["--package", str(mismatched), "--work-dir", str(log_dir)],
                env_override={"PGDATABASE": target},
            )
            self.assertNotEqual(res.returncode, 0, "A fingerprint mismatch must fail the import")
            self.assertIn("was rolled back", res.stderr)
            self.assertEqual(counts(target), [0, 0, 0], "A mismatch must commit nothing")
            logs = list(log_dir.glob("restore-*.log"))
            self.assertEqual(len(logs), 1)
            self.assertIn("KK002", logs[0].read_text(encoding="utf-8"))

            # The intact package still imports into the same, untouched target.
            res_ok = self.run_script(
                IMPORT_SCRIPT,
                ["--package", str(package), "--work-dir", str(log_dir)],
                env_override={"PGDATABASE": target},
            )
            self.assertEqual(res_ok.returncode, 0, sanitize_error(res_ok.stderr))
            self.assertEqual(counts(target), [1, 1, 2])

    # -------------------------------------------------------------------------
    # Test 5: Reset guards
    # -------------------------------------------------------------------------

    def test_05_reset_guards(self) -> None:
        # Test intent:
        # Prove that reset-database.sh protects data integrity across critical failure modes:
        #   1. --dry-run performs read-only checks without creating directories or altering state;
        #   2. Active concurrent sessions on the target abort the reset without mutating anything;
        #   3. A createdb failure triggers rollback: the frozen snapshot is renamed back and unfrozen,
        #      leaving the original database intact with datallowconn=t and retaining the full backup.

        # Guard 1: --dry-run creates no directories and changes nothing
        with self.subTest("dry_run_is_read_only"):
            db_dry = self.register_db(f"probe_reset_dry_{uuid.uuid4().hex[:8]}")
            self.create_empty_db(db_dry)
            self.run_psql_file(db_dry, LEGACY_SOURCE_SQL)

            nonexistent_work_dir = Path(self.test_dir.name) / "dry_run_dir"
            res = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(nonexistent_work_dir)],
                env_override={"PGDATABASE": db_dry},
            )
            self.assertEqual(res.returncode, 0, f"Dry-run failed: {sanitize_error(res.stderr)}")
            self.assertFalse(
                nonexistent_work_dir.exists(), "Dry-run must not create any work directory"
            )
            self.assertEqual(
                int(self.run_psql_command(db_dry, "SELECT count(*) FROM agent_definition")),
                2,
            )

        # Guard 2: Another connected session is refused and target is untouched
        with self.subTest("connected_session_refused"):
            db_busy = self.register_db(f"probe_reset_busy_{uuid.uuid4().hex[:8]}")
            self.create_empty_db(db_busy)
            self.run_psql_file(db_busy, LEGACY_SOURCE_SQL)

            # Start a background connection executing pg_sleep
            bg_cmd = [
                "psql",
                "-X",
                "-q",
                "-d",
                db_busy,
                "--no-password",
                "-c",
                "SELECT pg_sleep(30)",
            ]
            bg_proc = subprocess.Popen(
                bg_cmd,
                env=self.client_env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            try:
                # Wait until the background connection appears in pg_stat_activity
                deadline = time.time() + 10
                connected = False
                while time.time() < deadline:
                    active = int(
                        self.run_psql_command(
                            "postgres",
                            f"SELECT count(*) FROM pg_stat_activity WHERE datname = '{db_busy}' AND pid <> pg_backend_pid()",
                        )
                    )
                    if active > 0:
                        connected = True
                        break
                    time.sleep(0.1)
                self.assertTrue(connected, "Background sleep session did not register in time")

                busy_work_dir = Path(self.test_dir.name) / "busy_reset_dir"
                res = self.run_script(
                    RESET_SCRIPT,
                    ["--yes", "--work-dir", str(busy_work_dir)],
                    env_override={"PGDATABASE": db_busy},
                )
                self.assertNotEqual(
                    res.returncode, 0, "Reset should refuse target with active sessions"
                )
                self.assertIn("other session", res.stderr)
                self.assertEqual(
                    int(self.run_psql_command(db_busy, "SELECT count(*) FROM agent_definition")),
                    2,
                )
            finally:
                bg_proc.terminate()
                bg_proc.wait()

        # Guard 3: A failing createdb rolls the freeze back
        with self.subTest("failing_createdb_unfreezes_original"):
            db_fail = self.register_db(f"probe_reset_fail_cdb_{uuid.uuid4().hex[:8]}")
            self.create_empty_db(db_fail)
            self.run_psql_file(db_fail, LEGACY_SOURCE_SQL)

            stub_bin_dir = Path(self.test_dir.name) / "stub_bin"
            stub_bin_dir.mkdir(parents=True, exist_ok=True)
            stub_createdb = stub_bin_dir / "createdb"
            stub_createdb.write_text(
                "#!/bin/sh\necho 'simulated createdb failure' >&2\nexit 1\n",
                encoding="utf-8",
            )
            os.chmod(stub_createdb, 0o755)

            fail_work_dir = Path(self.test_dir.name) / "fail_reset_dir"
            res = self.run_script(
                RESET_SCRIPT,
                ["--yes", "--work-dir", str(fail_work_dir)],
                env_override={
                    "PATH": f"{stub_bin_dir}:{os.environ['PATH']}",
                    "PGDATABASE": db_fail,
                },
            )
            self.assertNotEqual(res.returncode, 0, "Reset should fail when createdb fails")

            # Assert original database exists under original name with allow_connections=true
            allow_conn = self.run_psql_command(
                "postgres",
                f"SELECT datallowconn FROM pg_database WHERE datname = '{db_fail}'",
            )
            self.assertEqual(allow_conn, "t", "Original database was not unfrozen")

            # Assert original data is intact
            self.assertEqual(
                int(self.run_psql_command(db_fail, "SELECT count(*) FROM agent_definition")),
                2,
            )

            # Assert no snapshot database remains
            snap_count = int(
                self.run_psql_command(
                    "postgres",
                    f"SELECT count(*) FROM pg_database WHERE datname LIKE '{db_fail}_pre_%'",
                )
            )
            self.assertEqual(snap_count, 0, "Snapshot database was not cleaned up after rollback")

            # Assert full backup was retained in work-dir and is valid mode 0600
            dumps = list(fail_work_dir.glob("*.dump"))
            self.assertEqual(len(dumps), 1, "Backup dump was not retained")
            self.assertEqual(dumps[0].stat().st_mode & 0o777, 0o600)
            res_restore = subprocess.run(
                ["pg_restore", "--no-password", "--list", str(dumps[0])],
                env=self.client_env,
                capture_output=True,
                check=False,
            )
            self.assertEqual(res_restore.returncode, 0)

        # Guard 4: A failure after createdb succeeded must not be misread as "never created":
        # the new database is dropped and the snapshot is renamed back over the target name.
        with self.subTest("post_create_failure_restores_original"):
            db_late = self.register_db(f"probe_reset_late_{uuid.uuid4().hex[:8]}")
            self.run_psql_command(
                "postgres", f'CREATE DATABASE "{db_late}" CONNECTION LIMIT 5'
            )
            self.run_psql_file(db_late, LEGACY_SOURCE_SQL)

            stub_bin_dir = Path(self.test_dir.name) / "late_stub_bin"
            stub_bin_dir.mkdir(parents=True, exist_ok=True)
            stub_psql = stub_bin_dir / "psql"
            # Only the connection-limit step fails: createdb and the rename into place succeed.
            stub_psql.write_text(
                "#!/bin/sh\n"
                "for arg in \"$@\"; do\n"
                "  case \"$arg\" in\n"
                "    *'connection limit'*) echo 'simulated late failure' >&2; exit 1 ;;\n"
                "  esac\n"
                "done\n"
                f"exec {shutil.which('psql')} \"$@\"\n",
                encoding="utf-8",
            )
            os.chmod(stub_psql, 0o755)

            late_work_dir = Path(self.test_dir.name) / "late_reset_dir"
            res = self.run_script(
                RESET_SCRIPT,
                ["--yes", "--work-dir", str(late_work_dir)],
                env_override={
                    "PATH": f"{stub_bin_dir}:{os.environ['PATH']}",
                    "PGDATABASE": db_late,
                },
            )
            self.assertNotEqual(res.returncode, 0, "Reset should fail on the late failure")
            self.assertIn("Dropped the incomplete database", res.stderr)
            self.assertIn("back in place", res.stderr)

            # The target name again points at the original data, unfrozen and complete.
            self.assertEqual(
                self.run_psql_command(
                    "postgres",
                    f"SELECT datallowconn FROM pg_database WHERE datname = '{db_late}'",
                ),
                "t",
            )
            self.assertEqual(
                int(self.run_psql_command(db_late, "SELECT count(*) FROM agent_definition")), 2
            )
            leftovers = self.run_psql_command(
                "postgres",
                "SELECT count(*) FROM pg_database WHERE datname LIKE '%kk_partial%'",
            )
            self.assertEqual(int(leftovers), 0, "A partial database was left behind")
            self.assertEqual(
                int(
                    self.run_psql_command(
                        "postgres",
                        f"SELECT count(*) FROM pg_database WHERE datname LIKE '{db_late}_pre_%'",
                    )
                ),
                0,
                "The snapshot name was left behind after a successful rollback",
            )

        # Guard 5: a connection that races in after preflight is detected after the old database
        # has been renamed and made non-connectable. The script must restore the original name
        # instead of proceeding to create an empty replacement.
        with self.subTest("post_freeze_session_recheck_restores_original"):
            db_race = self.register_db(f"probe_reset_race_{uuid.uuid4().hex[:8]}")
            self.create_empty_db(db_race)
            self.run_psql_file(db_race, LEGACY_SOURCE_SQL)

            stub_bin_dir = Path(self.test_dir.name) / "race_stub_bin"
            stub_bin_dir.mkdir(parents=True, exist_ok=True)
            stub_psql = stub_bin_dir / "psql"
            stub_psql.write_text(
                "#!/bin/sh\n"
                "previous=\n"
                "for arg in \"$@\"; do\n"
                "  if [ \"$previous\" = -c ]; then\n"
                f"    case \"$arg\" in *pg_stat_activity*\"datname = '{db_race}_pre_\"*)"
                " printf '1\\n'; exit 0 ;; esac\n"
                "  fi\n"
                "  previous=$arg\n"
                "done\n"
                f"exec {shutil.which('psql')} \"$@\"\n",
                encoding="utf-8",
            )
            os.chmod(stub_psql, 0o755)

            race_work_dir = Path(self.test_dir.name) / "race_reset_dir"
            res = self.run_script(
                RESET_SCRIPT,
                ["--yes", "--work-dir", str(race_work_dir)],
                env_override={
                    "PATH": f"{stub_bin_dir}:{os.environ['PATH']}",
                    "PGDATABASE": db_race,
                },
            )
            self.assertNotEqual(res.returncode, 0)
            self.assertIn("connected after preflight", res.stderr)
            self.assertIn("back in place", res.stderr)
            self.assertEqual(
                int(self.run_psql_command(db_race, "SELECT count(*) FROM agent_definition")), 2
            )
            self.assertEqual(
                int(
                    self.run_psql_command(
                        "postgres",
                        f"SELECT count(*) FROM pg_database WHERE datname LIKE '{db_race}_pre_%'",
                    )
                ),
                0,
            )

        # Guard 6: A successful reset keeps the metadata, freezes the snapshot and leaves no
        # temporary database name behind.
        with self.subTest("successful_reset_preserves_metadata"):
            db_ok = self.register_db(f"probe_reset_ok_{uuid.uuid4().hex[:8]}")
            self.run_psql_command(
                "postgres", f'CREATE DATABASE "{db_ok}" CONNECTION LIMIT 3'
            )
            self.run_psql_file(db_ok, LEGACY_SOURCE_SQL)

            ok_work_dir = Path(self.test_dir.name) / "ok_reset_dir"
            res = self.run_script(
                RESET_SCRIPT,
                ["--yes", "--work-dir", str(ok_work_dir)],
                env_override={"PGDATABASE": db_ok},
            )
            self.assertEqual(res.returncode, 0, f"Reset failed: {sanitize_error(res.stderr)}")
            self.assertIn("role:           superuser", res.stdout)

            metadata = self.run_psql_command(
                "postgres",
                "SELECT datallowconn || ' ' || datconnlimit || ' ' || pg_get_userbyid(datdba)"
                f" || ' ' || pg_encoding_to_char(encoding) FROM pg_database WHERE datname = '{db_ok}'",
            ).split()
            self.assertEqual(["true", "3", "postgres", "UTF8"], metadata)

            # A reset leaves a completely empty database: the application applies Flyway next.
            self.assertEqual(
                int(
                    self.run_psql_command(
                        db_ok,
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'",
                    )
                ),
                0,
            )

            snapshot = self.run_psql_command(
                "postgres",
                f"SELECT datname FROM pg_database WHERE datname LIKE '{db_ok}_pre_%'",
            )
            frozen = self.run_psql_command(
                "postgres",
                f"SELECT datallowconn FROM pg_database WHERE datname = '{snapshot}'",
            )
            # A frozen snapshot cannot be read without unfreezing it, which is the point: the
            # rows are proven to be there by the rollback guards above, and by the backup below.
            self.assertEqual("f", frozen, "The snapshot must stay frozen")
            self.register_db(snapshot)
            dumps = list(ok_work_dir.glob("*.dump"))
            self.assertEqual(len(dumps), 1, "The full backup was not retained")
            self.assertEqual(
                subprocess.run(
                    ["pg_restore", "--no-password", "--list", str(dumps[0])],
                    env=self.client_env,
                    capture_output=True,
                    check=False,
                ).returncode,
                0,
            )
            self.assertEqual(
                int(
                    self.run_psql_command(
                        "postgres",
                        "SELECT count(*) FROM pg_database WHERE datname LIKE '%kk_partial%'",
                    )
                ),
                0,
                "A partial database name was left behind",
            )

    # -------------------------------------------------------------------------
    # Test 7: Portable privileges and the PGSERVICE connection contract
    # -------------------------------------------------------------------------

    def test_07_portable_privileges_and_service_connection(self) -> None:
        # Test intent:
        # Prove reset does not require a superuser: an owner role with CREATEDB suffices, while a
        # role that cannot own or create the database fails read-only with a specific reason.
        # Also prove the documented PGSERVICE + PGPASSFILE contract works without PGHOST/PGPORT.
        suffix = uuid.uuid4().hex[:8]
        owner_role = f"probe_owner_{suffix}"
        plain_role = f"probe_plain_{suffix}"
        db_owned = self.register_db(f"probe_owned_{suffix}")
        db_foreign = self.register_db(f"probe_foreign_{suffix}")
        db_plain = self.register_db(f"probe_plain_owner_{suffix}")
        db_service = self.register_db(f"probe_service_{suffix}")

        def drop_roles() -> None:
            for role in (owner_role, plain_role):
                try:
                    self.run_psql_command("postgres", f'DROP ROLE IF EXISTS "{role}"')
                except Exception:  # noqa: BLE001 - cleanup must not mask the test result
                    pass

        self.addCleanup(drop_roles)
        self.run_psql_command(
            "postgres",
            f"CREATE ROLE \"{owner_role}\" LOGIN CREATEDB PASSWORD 'probe-owner-pw'"
            f"; CREATE ROLE \"{plain_role}\" LOGIN PASSWORD 'probe-plain-pw'",
        )
        for name, owner in ((db_owned, owner_role), (db_foreign, "postgres"), (db_plain, plain_role)):
            self.run_psql_command(
                "postgres", f'CREATE DATABASE "{name}" OWNER "{owner}" CONNECTION LIMIT 4'
            )
        self.create_empty_db(db_service)
        self.run_psql_file(db_service, LEGACY_SOURCE_SQL)

        def role_env(role: str, password: str) -> Dict[str, str]:
            """Environment for a specific role: the fixture passfile only knows postgres."""
            env = {key: value for key, value in self.client_env.items() if key != "PGPASSFILE"}
            env["PGUSER"] = role
            env["PGPASSWORD"] = password
            return env

        owner_env = role_env(owner_role, "probe-owner-pw")
        # The target role owns its tables, which is what pg_dump needs to read the whole database.
        self.run_psql_file(db_owned, LEGACY_SOURCE_SQL, env=owner_env)
        self.run_psql_file(
            db_plain, LEGACY_SOURCE_SQL, env=role_env(plain_role, "probe-plain-pw")
        )

        with self.subTest("owner_with_createdb_resets"):
            res = self.run_script(
                RESET_SCRIPT,
                ["--yes", "--work-dir", str(Path(self.test_dir.name) / "owner_dir")],
                env_override={"PGDATABASE": db_owned, "PGPASSFILE": None, **owner_env},
            )
            self.assertEqual(res.returncode, 0, f"Owner reset failed: {sanitize_error(res.stderr)}")
            self.assertIn("database owner with CREATEDB", res.stdout)
            metadata = self.run_psql_command(
                "postgres",
                f"SELECT datconnlimit || ' ' || pg_get_userbyid(datdba) || ' ' || datallowconn"
                f" FROM pg_database WHERE datname = '{db_owned}'",
            )
            self.assertEqual(f"4 {owner_role} true", metadata)
            self.register_db(
                self.run_psql_command(
                    "postgres",
                    f"SELECT datname FROM pg_database WHERE datname LIKE '{db_owned}_pre_%'",
                )
            )

        with self.subTest("a_role_that_does_not_own_the_target_is_refused"):
            res = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(Path(self.test_dir.name) / "foreign_dir")],
                env_override={"PGDATABASE": db_foreign, "PGPASSFILE": None, **owner_env},
            )
            self.assertNotEqual(res.returncode, 0)
            self.assertIn("cannot rename or freeze", res.stderr)
            self.assertEqual(
                self.run_psql_command(
                    "postgres",
                    f"SELECT datallowconn FROM pg_database WHERE datname = '{db_foreign}'",
                ),
                "t",
            )

        with self.subTest("a_role_without_createdb_is_refused"):
            res = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(Path(self.test_dir.name) / "plain_dir")],
                env_override={
                    "PGDATABASE": db_plain,
                    "PGPASSFILE": None,
                    **role_env(plain_role, "probe-plain-pw"),
                },
            )
            self.assertNotEqual(res.returncode, 0)
            self.assertIn("cannot create databases", res.stderr)
            self.assertIn("CREATEDB", res.stderr)

        with self.subTest("pgservice_and_pgpassfile_without_host_or_port"):
            # Only PGSERVICE + PGPASSFILE: no PGHOST, PGPORT, PGUSER or passfile path in PG*.
            res = self.run_script(
                EXPORT_SCRIPT,
                ["--dry-run", "--work-dir", str(Path(self.test_dir.name) / "service_dir")],
                env_override={
                    "PGHOST": None,
                    "PGPORT": None,
                    "PGUSER": None,
                    "PGSERVICE": "kk_studio_integration",
                    "PGSERVICEFILE": str(self.service_file),
                    "PGPASSFILE": str(self.passfile_path),
                    "PGDATABASE": db_service,
                },
            )
            self.assertEqual(
                res.returncode, 0, f"Service connection failed: {sanitize_error(res.stderr)}"
            )
            self.assertIn("source_schema=legacy-main", res.stdout)
            # The same pair must also carry reset, which uses the maintenance connection.
            res_reset = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(Path(self.test_dir.name) / "service_backup_dir")],
                env_override={
                    "PGHOST": None,
                    "PGPORT": None,
                    "PGUSER": None,
                    "PGSERVICE": "kk_studio_integration",
                    "PGSERVICEFILE": str(self.service_file),
                    "PGPASSFILE": str(self.passfile_path),
                    "PGDATABASE": db_service,
                },
            )
            self.assertEqual(
                res_reset.returncode, 0, f"Service reset preflight failed: {sanitize_error(res_reset.stderr)}"
            )
            self.assertIn("maintenance db: postgres", res_reset.stdout)

    # -------------------------------------------------------------------------
    # Test 6: Credential non-disclosure
    # -------------------------------------------------------------------------

    def test_06_credential_non_disclosure(self) -> None:
        # Test intent:
        # Prove that credentials and connection secrets are strictly withheld under failure:
        #   1. Incorrect PGPASSWORD fails without leaking the password string to stdout or stderr;
        #   2. Missing credentials fail immediately with --no-password instead of hanging or prompting;
        #   3. PGDATABASE with URI or libpq conninfo syntax is rejected without echoing the secret;
        #   4. Work directory inside the repository tree is rejected before creating any artifact.
        work_dir = Path(self.test_dir.name) / "disclosure_work"

        # 1. Wrong PGPASSWORD fails non-zero and password string is not disclosed.
        with self.subTest("wrong_pgpassword"):
            wrong_pw = "wrong-secret-password-val-987"
            res = self.run_script(
                EXPORT_SCRIPT,
                ["--work-dir", str(work_dir)],
                env_override={
                    "PGPASSWORD": wrong_pw,
                    "PGPASSFILE": "/dev/null",
                    "PGDATABASE": "postgres",
                },
            )
            self.assertNotEqual(res.returncode, 0, "Wrong password must fail")
            self.assertNotIn(wrong_pw, res.stdout, "Password leaked into stdout")
            self.assertNotIn(wrong_pw, res.stderr, "Password leaked into stderr")
            self.assert_no_fixture_values(res.stdout + res.stderr)

        # 2. No password available fails immediately instead of prompting.
        with self.subTest("no_password_supplied"):
            empty_passfile = Path(self.test_dir.name) / "empty_passfile"
            empty_passfile.touch(mode=0o600)
            res = self.run_script(
                EXPORT_SCRIPT,
                ["--work-dir", str(work_dir)],
                env_override={
                    "PGPASSWORD": "",
                    "PGPASSFILE": str(empty_passfile),
                    "PGDATABASE": "postgres",
                },
                timeout=10,
            )
            self.assertNotEqual(res.returncode, 0, "Missing password must fail")
            self.assertTrue(
                "no password supplied" in res.stderr.lower()
                or "fe_sendauth" in res.stderr.lower(),
                f"Expected fe_sendauth failure message, got: {res.stderr}",
            )
            self.assert_no_fixture_values(res.stdout + res.stderr)

        # 3. PGDATABASE containing a URI or conninfo is refused without echoing secrets.
        with self.subTest("pgdatabase_uri_conninfo_rejected"):
            uri_secret = "fake-uri-secret-token-123"
            res_uri = self.run_script(
                EXPORT_SCRIPT,
                ["--work-dir", str(work_dir)],
                env_override={"PGDATABASE": f"postgres://user:{uri_secret}@127.0.0.1/db"},
            )
            self.assertNotEqual(res_uri.returncode, 0, "URI in PGDATABASE must be rejected")
            self.assertNotIn(uri_secret, res_uri.stdout, "URI secret leaked to stdout")
            self.assertNotIn(uri_secret, res_uri.stderr, "URI secret leaked to stderr")
            self.assertIn("plain PostgreSQL identifier", res_uri.stderr)
            self.assert_no_fixture_values(res_uri.stdout + res_uri.stderr)

            conninfo_secret = "fake-conninfo-secret-token-456"
            res_conninfo = self.run_script(
                EXPORT_SCRIPT,
                ["--work-dir", str(work_dir)],
                env_override={"PGDATABASE": f"dbname=x password={conninfo_secret}"},
            )
            self.assertNotEqual(
                res_conninfo.returncode, 0, "Conninfo in PGDATABASE must be rejected"
            )
            self.assertNotIn(
                conninfo_secret, res_conninfo.stdout, "Conninfo secret leaked to stdout"
            )
            self.assertNotIn(
                conninfo_secret, res_conninfo.stderr, "Conninfo secret leaked to stderr"
            )
            self.assertIn("plain PostgreSQL identifier", res_conninfo.stderr)
            self.assert_no_fixture_values(res_conninfo.stdout + res_conninfo.stderr)

        # 4. Output directory inside repository is refused.
        with self.subTest("in_repository_workdir_refused"):
            in_repo_dir = WORKTREE_ROOT / "scripts" / "ops" / "tests" / "inside_repo_output_dir"
            res_repo = self.run_script(
                EXPORT_SCRIPT,
                ["--work-dir", str(in_repo_dir)],
                env_override={"PGDATABASE": "postgres"},
            )
            self.assertNotEqual(
                res_repo.returncode, 0, "In-repository work-dir must be rejected"
            )
            self.assertIn("must be outside the repository", res_repo.stderr)
            self.assertFalse(in_repo_dir.exists(), "In-repo directory must not be created")
            self.assert_no_fixture_values(res_repo.stdout + res_repo.stderr)


if __name__ == "__main__":
    unittest.main()
